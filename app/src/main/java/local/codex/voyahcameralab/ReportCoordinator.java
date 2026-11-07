package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Отправить логи": builds a report, queues it and sends it on its own thread, never on the
 * camera or main thread. Reports that could not go out are retried every five minutes while the
 * app runs, and at the next start. Nothing is sent unless the owner pressed the button.
 */
final class ReportCoordinator {
  interface Listener { void onReportStatus(String text); }

  private static final String TAG = "SentryReport", CODE = "code", STATUS = "status";
  private static final long RETRY_MINUTES = 5;
  private static ReportCoordinator instance;

  private final Context context;
  private final SharedPreferences preferences;
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "guard-reports"); t.setDaemon(true); return t; });
  private final AtomicBoolean busy = new AtomicBoolean();
  private volatile String status;
  private volatile Listener listener;
  private ScheduledFuture<?> retry;

  static synchronized ReportCoordinator shared(Context context) {
    if (instance == null) instance = new ReportCoordinator(context.getApplicationContext());
    return instance;
  }

  private ReportCoordinator(Context context) {
    this.context = context;
    preferences = context.getSharedPreferences("reports", Context.MODE_PRIVATE);
    status = preferences.getString(STATUS, "Логи ещё не отправлялись");
    worker.schedule(this::retryPending, 30, TimeUnit.SECONDS);
  }

  void setListener(Listener value) { listener = value; }
  String status() { return status; }

  /** The saved tester code, normalized; empty when there is none. */
  String shownCode() { return preferences.getString(CODE, ""); }
  void saveCode(TesterCode code) { preferences.edit().putString(CODE, code == null ? "" : code.secret).apply(); }

  /** Comes with the build (guard-upload.properties, not in Git), so the public source holds no server address. */
  private String endpoint() { return context.getString(R.string.upload_endpoint).trim(); }

  boolean send() {
    TesterCode code = TesterCode.parse(preferences.getString(CODE, ""));
    if (code == null) { set("Введите код тестера"); return false; }
    if (endpoint().isEmpty()) { set("В этой сборке отправка логов недоступна"); return false; }
    if (!busy.compareAndSet(false, true)) return false;
    set("Собираю логи…");
    try { worker.execute(() -> perform(code)); return true; }
    catch (RejectedExecutionException closed) { busy.set(false); return false; }
  }

  private void perform(TesterCode code) {
    try {
      File folder = new File(context.getFilesDir(), "reports");
      File[] leftovers = folder.listFiles();
      if (leftovers != null) for (File old : leftovers) old.delete(); // Queued copies live in the queue.
      File report = DiagnosticReport.create(context, "button");
      AppLog.i(TAG, "report " + report.getName() + " bytes=" + report.length());
      UploadQueue queue = queue();
      boolean admitted = queue.enqueue(report);
      set("Отправляю…");
      int sent = queue.flush(endpoint(), code);
      if (!admitted && queue.lastError().isEmpty()) { // A full queue drained meanwhile.
        admitted = queue.enqueue(report);
        if (admitted) sent += queue.flush(endpoint(), code);
      }
      report.delete();
      if (!admitted) { set("Очередь отправки заполнена: старые отчёты ещё не ушли"); scheduleRetry(); return; }
      finish(queue, sent);
    } catch (Exception | Error failure) { // whatever happens, the status and the busy flag must not hang
      AppLog.w(TAG, "Report", failure);
      set("Не удалось собрать логи: " + failure.getMessage());
    } finally { busy.set(false); }
  }

  private void retryPending() {
    synchronized (this) { retry = null; }
    TesterCode code = TesterCode.parse(preferences.getString(CODE, ""));
    if (code == null || endpoint().isEmpty()) return;
    if (!busy.compareAndSet(false, true)) { scheduleRetry(); return; }
    try {
      UploadQueue queue = queue();
      if (queue.pending() == 0) return;
      finish(queue, queue.flush(endpoint(), code));
    } catch (Exception | Error failure) { AppLog.w(TAG, "Retry", failure); scheduleRetry(); }
    finally { busy.set(false); }
  }

  private void finish(UploadQueue queue, int sent) {
    int pending = queue.pending();
    String error = queue.lastError();
    AppLog.i(TAG, "upload sent=" + sent + " pending=" + pending + (error.isEmpty() ? "" : " reason=" + error));
    if (pending == 0) { set("Логи отправлены в " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())); return; }
    if ("HTTP_401".equals(error)) { set("Сервер не принял код тестера. Проверьте код и отправьте снова."); return; }
    set(describe(error) + ": в очереди " + pending + ", повтор через " + RETRY_MINUTES + " минут");
    scheduleRetry();
  }

  private static String describe(String error) {
    if ("HTTP_429".equals(error)) return "Сервер просит подождать";
    if ("HTTP_507".equals(error)) return "На сервере нет места";
    if ("HTTP_413".equals(error)) return "Отчёт слишком большой";
    if (error.startsWith("HTTP_5")) return "Ошибка сервера";
    if ("RECEIPT_INVALID".equals(error)) return "Сервер ответил неверно";
    if (error.startsWith("HTTP_")) return "Сервер отказал (" + error.substring(5) + ")";
    return "Нет связи с сервером";
  }

  private synchronized void scheduleRetry() {
    if (retry != null && !retry.isDone()) return;
    try { retry = worker.schedule(this::retryPending, RETRY_MINUTES, TimeUnit.MINUTES); } catch (RejectedExecutionException ignored) {}
  }

  private UploadQueue queue() throws java.io.IOException {
    String version = "unknown";
    try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName.replaceAll("[^0-9A-Za-z._-]", "_"); }
    catch (Exception ignored) {}
    if (version.isEmpty() || version.length() > 32) version = version.isEmpty() ? "unknown" : version.substring(0, 32);
    return new UploadQueue(new File(context.getFilesDir(), "upload-queue"), version);
  }

  private void set(String text) {
    status = text;
    preferences.edit().putString(STATUS, text).apply();
    Listener current = listener;
    if (current != null) current.onReportStatus(text);
  }
}
