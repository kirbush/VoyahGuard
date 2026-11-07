package local.codex.voyahcameralab;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One ZIP for the developer, in the FM report layout: environment.txt, diagnostics.txt and the
 * event logs. No video, no pictures, no location: the app knows none of them as text anyway.
 * The tester code is not in it either; it lives in its own preferences.
 */
final class DiagnosticReport {
  static final String PREFIX = "Voyah-Guard-report-";
  static final String NAME = PREFIX + "[0-9]{8}-[0-9]{6}\\.zip";
  private static final int LOGCAT_BYTES = 4 * 1024 * 1024, ADAPTIVE_BYTES = 256 * 1024;

  private DiagnosticReport() {}

  static synchronized File create(Context context, String reason) throws IOException {
    AppLog.flush();
    File folder = new File(context.getFilesDir(), "reports");
    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Нет места для отчёта");
    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    long stamp = System.currentTimeMillis();
    File file;
    do { file = new File(folder, PREFIX + format.format(new Date(stamp)) + ".zip"); stamp += 1000; } while (file.exists());
    File temporary = new File(folder, file.getName() + ".partial");
    try {
      try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temporary))) {
        text(zip, "environment.txt", environment(context, reason));
        text(zip, "diagnostics.txt", diagnostics(context));
        for (String name : new String[]{AppLog.PREVIOUS, AppLog.CURRENT}) {
          File log = new File(context.getFilesDir(), name);
          if (!log.isFile()) continue;
          zip.putNextEntry(new ZipEntry(name));
          try (InputStream in = new FileInputStream(log)) { copy(in, zip); }
          zip.closeEntry();
        }
      }
      if (!temporary.renameTo(file)) throw new IOException("Не удалось завершить ZIP");
    } finally { temporary.delete(); }
    return file;
  }

  private static String environment(Context context, String reason) {
    StringBuilder out = new StringBuilder();
    String version = "?";
    long code = 0;
    try { PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0); version = info.versionName; code = info.getLongVersionCode(); }
    catch (Exception ignored) {}
    out.append("VoyahGuard ").append(version).append(" (").append(code).append(")\n");
    out.append("ReportFormat=1\nReason=").append(reason).append('\n');
    out.append("Logging=").append(AppLog.enabled() ? "on" : "off").append('\n');
    out.append("Time=").append(new Date()).append("\nTimeZone=").append(TimeZone.getDefault().getID()).append('\n');
    out.append("Model=").append(Build.MODEL).append("\nProduct=").append(Build.PRODUCT).append("\nDevice=").append(Build.DEVICE).append('\n');
    out.append("Display=").append(Build.DISPLAY).append("\nFingerprint=").append(Build.FINGERPRINT).append('\n');
    out.append("SDK=").append(Build.VERSION.SDK_INT).append("\nAppUID=").append(android.os.Process.myUid()).append('\n');
    out.append("\nSettings:\n");
    Map<String, ?> settings = new TreeMap<>(context.getSharedPreferences("camera", Context.MODE_PRIVATE).getAll());
    for (Map.Entry<String, ?> entry : settings.entrySet()) out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
    return out.toString();
  }

  private static String diagnostics(Context context) {
    StringBuilder out = new StringBuilder();
    File external = context.getExternalFilesDir(null);
    section(out, "State");
    out.append(external == null ? "no external files" : tail(new File(external, "state.json"), 64 * 1024));
    section(out, "Storage");
    try {
      RecordingStore.Snapshot s = new RecordingStore(context).snapshot();
      out.append(String.format(Locale.ROOT, "used=%d%% available=%d recordings=%d videos=%d%n", s.usedPercent, s.availableBytes, s.recordingBytes, s.clips));
    } catch (Exception e) { out.append("unavailable: ").append(e).append('\n'); }
    section(out, "Process exits");
    try {
      ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
      for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(null, 0, 16)) {
        out.append(time.format(new Date(exit.getTimestamp()))).append(" reason=").append(exit.getReason()).append(" status=").append(exit.getStatus())
            .append(" importance=").append(exit.getImportance()).append(" pss=").append(exit.getPss()).append(" rss=").append(exit.getRss())
            .append(" process=").append(exit.getProcessName()).append(" description=").append(exit.getDescription()).append('\n');
      }
    } catch (Exception e) { out.append("unavailable: ").append(e).append('\n'); }
    section(out, "Camera follow log (tail)");
    out.append(external == null ? "" : tail(new File(external, "adaptive-log.csv"), ADAPTIVE_BYTES));
    section(out, "Logcat of this app");
    out.append(logcat());
    return out.toString();
  }

  /** Only this process: a firmware with relaxed log access must not hand other apps' lines over. */
  private static String logcat() {
    Process process = null;
    try {
      process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + android.os.Process.myPid(), "-t", "20000").redirectErrorStream(true).start();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      Process running = process;
      Thread reader = new Thread(() -> {
        byte[] buffer = new byte[16384];
        try (InputStream in = running.getInputStream()) {
          int n;
          while ((n = in.read(buffer)) != -1) synchronized (bytes) { if (bytes.size() < LOGCAT_BYTES) bytes.write(buffer, 0, Math.min(n, LOGCAT_BYTES - bytes.size())); }
        } catch (IOException ignored) {}
      }, "guard-logcat");
      reader.start();
      if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroy();
      reader.join(2000);
      synchronized (bytes) { return bytes.toString("UTF-8"); }
    } catch (Exception e) {
      return "unavailable: " + e + "\n";
    } finally { if (process != null) process.destroy(); }
  }

  private static String tail(File file, int max) {
    if (!file.isFile()) return "absent\n";
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      long start = Math.max(0, in.length() - max);
      byte[] data = new byte[(int) (in.length() - start)];
      in.seek(start);
      in.readFully(data);
      return (start > 0 ? "…\n" : "") + new String(data, StandardCharsets.UTF_8) + "\n";
    } catch (IOException e) { return "unreadable: " + e + "\n"; }
  }

  private static void section(StringBuilder out, String title) { out.append("\n== ").append(title).append(" ==\n"); }

  private static void text(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
  }
}
