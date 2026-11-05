package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Called by the serial control executor, only after the user opted in.
 * Release must follow completed video finalization. No method acquires automatically.
 * OEM has no ownership token: an identical external off/on cycle cannot be detected.
 */
final class PowerHoldController {
  private static final long CALL_NANOS = TimeUnit.MILLISECONDS.toNanos(7500);
  private static final String PENDING = "pending";
  private static final int RELEASE_CHECKS = 6;
  private final Context context;
  private final SharedPreferences journal;
  private final String session = UUID.randomUUID().toString();
  private final PowerHoldPolicy.OwnershipGuard ownership = new PowerHoldPolicy.OwnershipGuard(session);
  private volatile String cached = "Power Hold: штатное состояние не проверено";
  private volatile boolean owned;
  private volatile int releaseChecks;
  private boolean recovered, releaseStarted, releaseAttempted, leaseMatched, acquireFailed, cleanupSubmitted;
  private PowerHoldPolicy.Lease lease;

  PowerHoldController(Context context) {
    Context application = context.getApplicationContext();
    this.context = application == null ? context : application;
    journal = this.context.getSharedPreferences("sentry_power_hold_ownership", Context.MODE_PRIVATE);
    recovered = journal.getBoolean(PENDING, false);
    if (recovered) cached = recoveredWarning();
  }

  // These two UI reads deliberately do not share the executor's blocking monitor.
  String statusText() { return cached; }
  boolean ownsHold() { return owned; }
  boolean journalPending() { return journal.getBoolean(PENDING, false); }
  /** True while release() is still worth repeating: it stays read-only once a disable was sent. */
  boolean awaitingRelease() { return releaseChecks > 0; }

  private static String recoveredWarning() {
    return "Power Hold: недоступен — незавершённый журнал прошлого процесса; проверьте штатный режим вручную. Журнал снимется после перезагрузки ГУ";
  }
  /** Read-only: a process that died with the ГУ must not block Power Hold until app data is wiped. */
  private void retireExpiredJournal(long deadline) {
    if (lease != null || !journal.getBoolean(PENDING, false)) return;
    try {
      PowerHoldPolicy.Snapshot current = invoke("status", deadline, null, 0).snapshot;
      if (PowerHoldPolicy.journalExpired(journal.getString("original", ""), current) && journal.edit().clear().commit()) {
        recovered = false;
        cached = display(PowerHoldPolicy.state(current));
      }
    } catch (Exception failure) { /* the journal stays and recoveredJournal() reports it */ }
  }
  private boolean recoveredJournal() {
    // Re-read on every operation, even if this controller was constructed before
    // another Activity committed its pending journal. Never adopt that lease.
    boolean pending = journal.getBoolean(PENDING, false);
    boolean own = ownership.ownsJournal(pending, journal.getString("session", ""));
    if (lease != null && !own) {
      lease.revokeWriteAuthority();
      owned = false;
      recovered = true;
      cached = "Power Hold: недоступен — журнал собственного изменения утрачен или принадлежит другой сессии; проверьте режим вручную";
      return true;
    }
    if (pending && (lease == null || !own)) {
      recovered = true;
      owned = false;
      cached = recoveredWarning();
      return true;
    }
    if (recovered && !pending) cached = "Power Hold: штатное состояние не проверено";
    recovered = false;
    return false;
  }

  String acquire(int minutes) {
    long deadline = System.nanoTime() + CALL_NANOS;
    return ownership.serialized(deadline, () -> acquireLocked(minutes, deadline), this::busy);
  }
  String refresh() {
    long deadline = System.nanoTime() + CALL_NANOS;
    return ownership.serialized(deadline, () -> refreshLocked(deadline), this::busy);
  }
  String release() {
    long deadline = System.nanoTime() + CALL_NANOS;
    return ownership.serialized(deadline, () -> releaseLocked(deadline), this::busy);
  }
  private String busy() {
    cached = "Power Hold: недоступен — другая операция не завершилась в отведённое время; повторные команды не отправлены";
    return cached;
  }

  private String acquireLocked(int minutes, long deadline) {
    try {
      PowerHoldPolicy.rawMinutes(minutes);
      retireExpiredJournal(deadline);
      if (lease != null && releaseAttempted) refreshLocked(deadline); // the queued disable may have landed since Stop
      if (recoveredJournal() || lease != null) return cached;
      Reply status = invoke("status", deadline, null, 0);
      PowerHoldPolicy.Snapshot before = status.snapshot;
      PowerHoldPolicy.State state = PowerHoldPolicy.state(before);
      if (state == PowerHoldPolicy.State.ARMED || state == PowerHoldPolicy.State.ACTIVE) {
        cached = display(state) + " — штатный режим, приложение его не отключает";
        return cached;
      }
      if (!PowerHoldPolicy.canAcquire(before)) {
        cached = "Power Hold: недоступен — нужны известное выключенное состояние, P и заряд 15–100%, без штатного предупреждения";
        return cached;
      }
      PowerHoldPolicy.Lease candidate = new PowerHoldPolicy.Lease(before, minutes);
      if (!ownership.mayStart(journal.getBoolean(PENDING, false))) {
        recoveredJournal();
        return cached;
      }
      // commit(), not apply(): persistence must precede even the first queued Binder write.
      if (!journal.edit().putBoolean(PENDING, true).putString("session", session)
          .putString("original", before.wire()).putInt("minutes", minutes)
          .putString("phase", "pending").commit()) {
        cached = "Power Hold: недоступен — не удалось сохранить журнал; запрос не отправлен";
        return cached;
      }
      lease = candidate;
      owned = false;
      leaseMatched = releaseAttempted = acquireFailed = cleanupSubmitted = false;
      cached = "Power Hold: запрос отправляется — активность ещё не подтверждена";
      if (!ownJournalForMutation()) return cached;
      Reply result = invoke("acquire", deadline, before, minutes);
      if ("STALE".equals(result.code)) {
        finishJournal("Power Hold: недоступен — состояние изменилось до запроса; включение отменено");
      } else if ("QUEUED".equals(result.code)) {
        leaseMatched = candidate.releaseDecision(result.snapshot) == PowerHoldPolicy.ReleaseDecision.RELEASE;
        owned = leaseMatched;
        cached = display(PowerHoldPolicy.stateAfterQueued(result.snapshot));
        if (candidate.writeAuthorityRevoked())
          cached += " — право управления отозвано; журнал сохранён, проверьте штатный режим вручную";
        if (!ownJournalForMutation()) return cached;
        if (!journal.edit().putString("phase", "queued").commit())
          cached += " — ошибка журнала, требуется проверка отключения";
      } else throw new IOException("Unexpected acquire result");
    } catch (Exception failure) {
      if (lease != null) {
        acquireFailed = true;
        cached = "Power Hold: недоступен — ошибка запроса; изменение могло быть частичным, отключение требует проверки";
      } else cached = "Power Hold: недоступен — нет доступа к поддерживаемому API";
    }
    return cached;
  }

  private String refreshLocked(long deadline) {
    try {
      retireExpiredJournal(deadline);
      if (recoveredJournal()) return cached;
      Reply result = invoke("status", deadline, null, 0);
      PowerHoldPolicy.Snapshot current = result.snapshot;
      if (lease == null) cached = display(PowerHoldPolicy.state(current));
      else if (releaseAttempted) {
        if (restored(current) && (!acquireFailed || cleanupSubmitted)) finishJournal("Power Hold: выключен");
        else if (releaseChecks == 0) cached = "Power Hold: ошибка отключения — результат не подтверждён; проверьте штатный режим вручную";
      } else {
        PowerHoldPolicy.ReleaseDecision decision = lease.releaseDecision(current);
        if (decision == PowerHoldPolicy.ReleaseDecision.RELEASE) {
          owned = leaseMatched = true;
          cached = display(PowerHoldPolicy.state(current));
        } else if (decision == PowerHoldPolicy.ReleaseDecision.ENDED && !acquireFailed) {
          finishJournal("Power Hold: выключен — завершён штатно, повторное включение не выполняется");
        } else if (decision == PowerHoldPolicy.ReleaseDecision.CHANGED && leaseMatched && !acquireFailed) {
          finishJournal(display(PowerHoldPolicy.state(current)) + " — изменён вне приложения, управление отпущено");
        } else {
          owned = false;
          cached = "Power Hold: недоступен — собственное состояние не подтверждено; журнал сохранён";
        }
      }
    } catch (Exception failure) {
      cached = lease == null ? "Power Hold: недоступен — нет ответа поддерживаемого API"
          : "Power Hold: недоступен — состояние неизвестно; журнал отключения сохранён";
    }
    return cached;
  }

  private String releaseLocked(long deadline) {
    try {
      if (recoveredJournal() || lease == null) {
        releaseChecks = 0;
        return cached;
      }
      if (!releaseStarted) {
        releaseStarted = true;
        releaseChecks = RELEASE_CHECKS;
      } else if (releaseChecks > 0) releaseChecks--;
      if (lease.writeAuthorityRevoked()) {
        owned = false;
        releaseChecks = 0;
        cached = "Power Hold: недоступен — после отмены или внешнего изменения право управления отозвано; журнал сохранён, отключение вручную";
        return cached;
      }
      if (releaseAttempted) return refreshLocked(deadline); // read-only confirmation, never repeat a queued disable
      // Use this process's observed-duration history before invoking the write helper.
      // A fresh helper cannot know that TIME returned to the original value after it
      // had previously matched our request. Both calls share the same 7.5s deadline.
      Reply beforeRelease = invoke("status", deadline, null, 0);
      PowerHoldPolicy.ReleaseDecision decision = lease.releaseDecision(beforeRelease.snapshot);
      if (decision != PowerHoldPolicy.ReleaseDecision.RELEASE) {
        owned = false;
        if (decision == PowerHoldPolicy.ReleaseDecision.ENDED && !acquireFailed)
          finishJournal("Power Hold: выключен — уже завершён штатно, восстановление не требуется");
        else if (decision == PowerHoldPolicy.ReleaseDecision.CHANGED && leaseMatched && !acquireFailed)
          finishJournal(display(PowerHoldPolicy.state(beforeRelease.snapshot)) + " — изменён вне приложения, оставлен без изменений");
        else cached = "Power Hold: ошибка отключения — собственное состояние не подтверждено или право управления отозвано; журнал сохранён";
        return cached;
      }
      owned = leaseMatched = true;
      if (!ownJournalForMutation()) return cached;
      if (!journal.edit().putString("phase", "release-pending").commit()) {
        cached = "Power Hold: ошибка отключения — журнал недоступен, состояние не изменено";
        return cached;
      }
      if (!ownJournalForMutation()) return cached;
      // Set only here: a lagging TIME or a failed status read has sent nothing and may be checked again.
      releaseAttempted = true;
      Reply result;
      try { result = invoke("release", System.nanoTime() + CALL_NANOS, lease.original, lease.minutes); }
      catch (NotSent nothingWritten) { return releaseNotSent(); }
      if ("UNKNOWN".equals(result.code)) return releaseNotSent();
      if ("ENDED".equals(result.code) || "CHANGED".equals(result.code) || "UNKNOWN".equals(result.code))
        lease.releaseDecision(result.snapshot); // reclassify with history; a fresh helper has never seen our requested TIME
      cleanupSubmitted = "RELEASED".equals(result.code) || "RELEASE_QUEUED".equals(result.code);
      if ("RELEASED".equals(result.code) && restored(result.snapshot)) {
        finishJournal("Power Hold: выключен");
      } else if ("ENDED".equals(result.code) && !acquireFailed) {
        finishJournal("Power Hold: выключен — уже завершён штатно, восстановление не требуется");
      } else if ("CHANGED".equals(result.code) && leaseMatched && !acquireFailed) {
        finishJournal(display(PowerHoldPolicy.state(result.snapshot)) + " — изменён вне приложения, оставлен без изменений");
      } else if ("RELEASE_QUEUED".equals(result.code)) {
        owned = false;
        cached = "Power Hold: отключение запрошено — штатный статус ещё не подтвердил";
      } else {
        owned = false;
        cached = "Power Hold: ошибка отключения — результат или владение не подтверждены; проверьте штатный режим вручную";
      }
    } catch (Exception failure) {
      owned = false;
      cached = releaseAttempted
          ? "Power Hold: ошибка отключения — частичное изменение возможно; журнал сохранён, проверьте штатный режим вручную"
          : "Power Hold: ошибка отключения — состояние не прочитано, команда не отправлена; журнал сохранён";
    }
    return cached;
  }

  /** The helper wrote nothing, so the disable is still owed; tick() retries within releaseChecks. */
  private String releaseNotSent() {
    releaseAttempted = false;
    cached = releaseChecks > 0 ? "Power Hold: отключение ещё не отправлено — повтор"
        : "Power Hold: ошибка отключения — команда не отправлена; журнал сохранён, отключите режим вручную";
    return cached;
  }

  private boolean restored(PowerHoldPolicy.Snapshot current) {
    return lease != null && current != null && current.switchValue == 0
        && current.rawTime == lease.original.rawTime
        && PowerHoldPolicy.sameProperty(lease.original.property, current.property) && lease.original.boot.equals(current.boot);
  }
  private void finishJournal(String text) {
    if (!ownJournalForMutation()) return;
    owned = false;
    if (journal.edit().clear().commit()) {
      lease = null;
      releaseStarted = releaseAttempted = leaseMatched = acquireFailed = cleanupSubmitted = false;
      releaseChecks = 0;
      cached = text;
    } else {
      releaseAttempted = true;
      cached = text + " — ошибка отключения: журнал не очищен, требуется ручная проверка";
    }
  }
  private boolean ownJournalForMutation() {
    if (ownership.ownsJournal(journal.getBoolean(PENDING, false), journal.getString("session", ""))) return true;
    // Retain the local lease as diagnostic evidence, but never clear or overwrite
    // another session's journal and never renew authority if its values later match.
    if (lease != null) lease.revokeWriteAuthority();
    owned = false;
    recovered = true;
    cached = "Power Hold: недоступен — владелец журнала изменился; собственное изменение требует ручной проверки";
    return false;
  }
  private static String display(PowerHoldPolicy.State state) {
    switch (state) {
      case OFF: return "Power Hold: выключен";
      case REQUESTED: return "Power Hold: запрошен — активность ещё не подтверждена";
      case ARMED: return "Power Hold: подготовлен — ожидается запирание снаружи";
      case ACTIVE: return "Power Hold: активен по штатному статусу";
      default: return "Power Hold: недоступен — неизвестное состояние";
    }
  }

  private static final class NotSent extends IOException {}

  private static final class Reply {
    final String code;
    final PowerHoldPolicy.Snapshot snapshot;
    Reply(String code, PowerHoldPolicy.Snapshot snapshot) { this.code = code; this.snapshot = snapshot; }
  }
  private Reply invoke(String operation, long deadline, PowerHoldPolicy.Snapshot original, int minutes) throws Exception {
    String servicePath = context.getPackageManager()
        .getApplicationInfo("com.qinggan.canbus.service", 0).sourceDir;
    int location = PowerHoldBridge.serviceLocation(servicePath);
    String apk = context.getApplicationInfo().sourceDir;
    if (apk == null || !apk.startsWith("/") || apk.indexOf(':') >= 0 || apk.indexOf('\n') >= 0)
      throw new IOException("Invalid own APK path");
    List<String> command = new ArrayList<>();
    // Runs with the app's own UID: su is unreachable for apps here and CanBusService needs no more.
    command.add("/system/bin/app_process");
    command.add("-Djava.class.path=" + apk + ":" + PowerHoldBridge.API_PATH + ":" + PowerHoldBridge.VEHICLE_PATH);
    command.add("/system/bin"); command.add("local.codex.voyahcameralab.PowerHoldBridge");
    command.add(operation); command.add(Integer.toString(location));
    long remaining = deadline - System.nanoTime();
    if (remaining <= TimeUnit.MILLISECONDS.toNanos(200)) throw new IOException("Deadline");
    command.add(Long.toString(android.os.SystemClock.elapsedRealtime()
        + TimeUnit.NANOSECONDS.toMillis(remaining) - 150));
    if (original != null) {
      PowerHoldPolicy.rawMinutes(minutes);
      command.add(Integer.toString(minutes)); command.add(original.wire());
    }
    remaining = deadline - System.nanoTime();
    if (remaining <= TimeUnit.MILLISECONDS.toNanos(200)) throw new IOException("Deadline");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    Output output = new Output(process.getInputStream());
    Thread reader = new Thread(output, "power-hold-output");
    reader.setDaemon(true); reader.start();
    try {
      remaining = deadline - System.nanoTime();
      if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new IOException("Timeout");
      remaining = deadline - System.nanoTime();
      if (remaining > 0) reader.join(Math.max(1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(remaining))));
      if (process.exitValue() == PowerHoldBridge.NOT_SENT) throw new NotSent();
      if (reader.isAlive() || output.overflow || process.exitValue() != 0) throw new IOException("Incomplete helper reply");
      Reply reply = null;
      for (String line : output.text().split("\r?\n")) {
        if (!line.startsWith("PH1\t")) continue;
        String[] fields = line.split("\t", 3);
        if (fields.length != 3 || "ERROR".equals(fields[1])) throw new IOException("Helper unavailable");
        if (reply != null) throw new IOException("Duplicate helper reply");
        reply = new Reply(fields[1], PowerHoldPolicy.Snapshot.parse(fields[2]));
      }
      if (reply == null) throw new IOException("Missing helper reply");
      return reply;
    } finally {
      process.destroyForcibly();
      try { process.getInputStream().close(); } catch (IOException ignored) {}
      try { process.getOutputStream().close(); } catch (IOException ignored) {}
    }
  }
  private static final class Output implements Runnable {
    private final InputStream stream;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    volatile boolean overflow;
    Output(InputStream stream) { this.stream = stream; }
    public void run() {
      byte[] buffer = new byte[1024];
      try {
        int count;
        while ((count = stream.read(buffer)) != -1) {
          synchronized (bytes) {
            if (bytes.size() + count > 8192) overflow = true;
            else bytes.write(buffer, 0, count);
          }
        }
      } catch (IOException ignored) { /* incomplete output fails parsing */ }
    }
    String text() { synchronized (bytes) { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); } }
  }
}
