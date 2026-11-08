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
  private static final String PENDING = "pending", TAG = "PowerHold", BLOCKED_BOOT = "blocked_boot";
  private static final String SERVICE_PACKAGE = "com.qinggan.canbus.service";
  /** At the start: the status line is one line and loses its end. */
  private static final String UNVERIFIED_PREFIX = "Power Hold (прошивка не из проверенных): ";
  private static final int RELEASE_CHECKS = 6;
  private final Context context;
  private final SharedPreferences journal;
  /** Separate from the journal, which finishJournal() clears whole. */
  private final SharedPreferences firmwareState;
  /** What the bridge last reported about the installed firmware; null until it answered once. */
  private volatile PowerHoldPolicy.Firmware firmware;
  private volatile String servicePath = "";
  /** Boot id on which an unverified request failed; kept here too in case the preference was not saved. */
  private volatile String blockedBoot = "";
  // Under the process lock: the bridge answers of the running operation, the last logged text,
  // and whether the bridge's own undo sent its disable in the latest run.
  private final StringBuilder replies = new StringBuilder();
  private String lastLogged = "";
  private boolean undoSent;
  private final String session = UUID.randomUUID().toString();
  private final PowerHoldPolicy.OwnershipGuard ownership = new PowerHoldPolicy.OwnershipGuard(session);
  private volatile String cached = "Power Hold: штатное состояние не проверено";
  private volatile boolean owned;
  private volatile int releaseChecks;
  private boolean recovered, releaseStarted, releaseAttempted, leaseMatched, acquireFailed, cleanupSubmitted;
  private PowerHoldPolicy.Lease lease;

  private static PowerHoldController shared;

  /**
   * One per process: a release may wait minutes for a video, and an activity opened meanwhile
   * must see the same lease instead of a journal it does not own.
   */
  static synchronized PowerHoldController shared(Context context) {
    if (shared == null) shared = new PowerHoldController(context);
    return shared;
  }

  private PowerHoldController(Context context) {
    Context application = context.getApplicationContext();
    this.context = application == null ? context : application;
    journal = this.context.getSharedPreferences("sentry_power_hold_ownership", Context.MODE_PRIVATE);
    firmwareState = this.context.getSharedPreferences("sentry_power_hold_firmware", Context.MODE_PRIVATE);
    recovered = journal.getBoolean(PENDING, false);
    if (recovered) cached = recoveredWarning();
  }

  /** For the tester report: the status, the firmware and whether a request was refused this boot. */
  String reportText() {
    StringBuilder out = new StringBuilder();
    out.append("status=").append(cached).append('\n');
    PowerHoldPolicy.Firmware seen = firmware;
    String path = servicePath;
    if (seen == null) { // the bridge has not answered in this process: read the same files here
      path = installedServicePath();
      seen = PowerHoldBridge.firmware(PowerHoldBridge.serviceLocation(path));
      out.append("firmware (read by the app)=");
    } else out.append("firmware=");
    out.append(seen.describe()).append("\nservice=").append(path.isEmpty() ? "not installed" : path).append('\n');
    String blocked = firmwareState.getString(BLOCKED_BOOT, "");
    String boot;
    try { boot = PowerHoldBridge.readBoot(); } catch (Exception unreadable) { boot = ""; }
    out.append("refusedThisBoot=").append(!boot.isEmpty() && (boot.equals(blocked) || boot.equals(blockedBoot))).append('\n');
    out.append("journalPending=").append(journalPending()).append('\n');
    return out.toString();
  }

  private String installedServicePath() {
    try {
      String path = context.getPackageManager().getApplicationInfo(SERVICE_PACKAGE, 0).sourceDir;
      return path == null ? "" : path;
    } catch (Exception missing) { return ""; }
  }

  private boolean unverified() { PowerHoldPolicy.Firmware seen = firmware; return seen != null && !seen.verified; }

  /**
   * Under the process lock, after every operation. Acquire and release are always written, the
   * 10 s status only when its text changes, so a report shows each step without repeating itself.
   */
  private String finish(String operation, boolean always, String text) {
    if (unverified() && text.startsWith("Power Hold: ")) cached = text = UNVERIFIED_PREFIX + text.substring("Power Hold: ".length());
    String answers = replies.toString();
    replies.setLength(0);
    if (always || !text.equals(lastLogged)) AppLog.i(TAG, operation + ": " + text + (answers.isEmpty() ? "" : " [" + answers + "]"));
    lastLogged = text;
    return text;
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
    return ownership.serialized(deadline, () -> finish("acquire " + minutes + " min", true, acquireLocked(minutes, deadline)), this::busy);
  }
  String refresh() {
    long deadline = System.nanoTime() + CALL_NANOS;
    return ownership.serialized(deadline, () -> finish("status", false, refreshLocked(deadline)), this::busy);
  }
  String release() {
    long deadline = System.nanoTime() + CALL_NANOS;
    return ownership.serialized(deadline, () -> finish("release", true, releaseLocked(deadline)), this::busy);
  }
  private String busy() {
    cached = "Power Hold: недоступен — другая операция не завершилась в отведённое время; повторные команды не отправлены";
    AppLog.w(TAG, "operation skipped: another one still running");
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
      if (blockedOn(before.boot)) {
        cached = "Power Hold: не включается — на этой прошивке запрос уже не подтвердился; до перезагрузки ГУ не повторяется";
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
      Reply result;
      try { result = invoke("acquire", deadline, before, minutes); }
      catch (NotSent nothingWritten) { // the bridge wrote nothing: no lease to keep, the next recording may try
        finishJournal("Power Hold: не включён — запрос не отправлен; повтор со следующей записью");
        return cached;
      }
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
      } else if ("REJECTED".equals(result.code)) {
        // Unverified firmware did not read the request back as ours; the bridge already took it back.
        block(before.boot);
        AppLog.w(TAG, "unverified firmware did not confirm the request: taken back, not retried until the ГУ reboots");
        if (restored(result.snapshot)) {
          finishJournal("Power Hold: не включён — прошивка не подтвердила запрос, команда отменена; до перезагрузки ГУ не повторяется");
        } else disableSentUnconfirmed();
      } else throw new IOException("Unexpected acquire result");
    } catch (Exception failure) {
      if (lease != null) {
        acquireFailed = true;
        cached = "Power Hold: недоступен — ошибка запроса; изменение могло быть частичным, отключение требует проверки";
        if (unverified()) takeBackUnverified();
      } else cached = "Power Hold: недоступен — нет доступа к поддерживаемому API";
    }
    return cached;
  }

  /** The disable went out but the status has not shown it yet: confirmed read-only, never re-sent. */
  private void disableSentUnconfirmed() {
    owned = false;
    acquireFailed = cleanupSubmitted = releaseStarted = releaseAttempted = true;
    releaseChecks = RELEASE_CHECKS;
    cached = "Power Hold: прошивка не подтвердила запрос — отмена отправлена, штатный статус её ещё не показал";
  }

  /**
   * Unverified firmware, request broken off half-way (bridge killed, failed read, timeout). It is
   * not kept for the recording: blocked for this boot and taken back now, unless the bridge's own
   * undo already sent the disable.
   */
  private void takeBackUnverified() {
    block(lease.original.boot);
    AppLog.w(TAG, "unverified firmware: request broken off, taking it back; not retried until the ГУ reboots");
    if (undoSent) { disableSentUnconfirmed(); return; }
    String result = releaseLocked(System.nanoTime() + CALL_NANOS);
    cached = "Power Hold: запрос на непроверенной прошивке прерван, отмена — " + result.replaceFirst("^Power Hold: ", "");
  }

  private void block(String boot) {
    blockedBoot = boot;
    if (!firmwareState.edit().putString(BLOCKED_BOOT, boot).commit()) AppLog.w(TAG, "block not saved: kept for this process only");
  }
  private boolean blockedOn(String boot) {
    return unverified() && boot != null && !boot.isEmpty()
        && (boot.equals(blockedBoot) || boot.equals(firmwareState.getString(BLOCKED_BOOT, "")));
  }

  private String refreshLocked(long deadline) {
    try {
      retireExpiredJournal(deadline);
      if (recoveredJournal()) return cached;
      Reply result = invoke("status", deadline, null, 0);
      PowerHoldPolicy.Snapshot current = result.snapshot;
      if (lease == null) cached = display(PowerHoldPolicy.state(current))
          + (blockedOn(current.boot) ? " — включение на этой прошивке не подтвердилось, до перезагрузки ГУ не повторяется" : "");
      else if (releaseAttempted) {
        if (restored(current) && (!acquireFailed || cleanupSubmitted)) finishJournal("Power Hold: выключен");
        else if (releaseChecks == 0) cached = "Power Hold: ошибка отключения — результат не подтверждён; проверьте штатный режим вручную";
      } else {
        PowerHoldPolicy.ReleaseDecision decision = lease.releaseDecision(current);
        if (decision == PowerHoldPolicy.ReleaseDecision.RELEASE && acquireFailed && blockedOn(lease.original.boot)) {
          // A broken-off unverified request whose take-back has not gone out yet: never adopted, sent again.
          String retry = releaseLocked(System.nanoTime() + CALL_NANOS);
          cached = "Power Hold: запрос на непроверенной прошивке прерван, отмена — " + retry.replaceFirst("^Power Hold: ", "");
        } else if (decision == PowerHoldPolicy.ReleaseDecision.RELEASE) {
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

  private static final class NotSent extends IOException {
    NotSent(String reason) { super("not sent" + (reason == null ? "" : ", bridge " + reason)); }
  }

  private static final class Reply {
    final String code;
    final PowerHoldPolicy.Snapshot snapshot;
    Reply(String code, PowerHoldPolicy.Snapshot snapshot) { this.code = code; this.snapshot = snapshot; }
  }
  /** Every bridge answer and failure of the running operation goes to the app log with it. */
  private Reply invoke(String operation, long deadline, PowerHoldPolicy.Snapshot original, int minutes) throws Exception {
    if (replies.length() > 0) replies.append("; ");
    replies.append(operation).append('=');
    try {
      Reply reply = call(operation, deadline, original, minutes);
      replies.append(reply.code).append(' ').append(reply.snapshot.log());
      return reply;
    } catch (Exception failure) {
      String message = failure.getMessage();
      replies.append("failed: ").append(failure instanceof IOException && message != null ? message : failure.getClass().getSimpleName());
      throw failure;
    }
  }

  private Reply call(String operation, long deadline, PowerHoldPolicy.Snapshot original, int minutes) throws Exception {
    // Installed elsewhere or under another name, the service is still reached by its Binder name;
    // only the firmware check cannot hash it, so that firmware counts as unverified.
    undoSent = false;
    String path = installedServicePath();
    servicePath = path;
    int location = PowerHoldBridge.serviceLocation(path);
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
      String text = output.text();
      // Firmware first: a run that fails later has still said what it found installed.
      for (String line : text.split("\r?\n")) {
        if (PowerHoldBridge.UNDO_SENT.equals(line)) undoSent = true;
        if (!line.startsWith("PH1F\t")) continue;
        try {
          PowerHoldPolicy.Firmware seen = PowerHoldPolicy.Firmware.parse(line);
          if (!seen.sameAs(firmware)) AppLog.i(TAG, "firmware " + seen.describe() + " service=" + (path.isEmpty() ? "not installed" : path));
          firmware = seen;
        } catch (IllegalArgumentException cut) { /* an interrupted run; the checks below fail it */ }
      }
      String reason = null;
      for (String line : text.split("\r?\n")) {
        if (!line.startsWith("PH1\tERROR\t")) continue;
        reason = PowerHoldPolicy.bridgeReason(line.substring("PH1\tERROR\t".length()));
      }
      if (process.exitValue() == PowerHoldBridge.NOT_SENT) throw new NotSent(reason);
      if (reason != null) throw new IOException("bridge " + reason + " exit=" + process.exitValue());
      if (reader.isAlive() || output.overflow || process.exitValue() != 0) throw new IOException("Incomplete helper reply exit=" + process.exitValue());
      Reply reply = null;
      for (String line : text.split("\r?\n")) {
        if (!line.startsWith("PH1\t")) continue;
        String[] fields = line.split("\t", 3);
        if (fields.length != 3) throw new IOException("Helper unavailable");
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
