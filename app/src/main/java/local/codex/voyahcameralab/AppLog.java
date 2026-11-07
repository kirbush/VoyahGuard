package local.codex.voyahcameralab;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The app's event log, in the FM report format: "yyyy-MM-dd HH:mm:ss.SSS TAG message" lines in
 * events.log, rotated to events.previous.log at 1 MiB. Written only while the owner turned it on;
 * logcat always gets the line. Files go from one background thread, so camera and GL threads
 * never wait for storage. Outside the app process (the app_process bridges) it is logcat only.
 */
final class AppLog {
  static final String CURRENT = "events.log", PREVIOUS = "events.previous.log";
  static final long MAX_BYTES = 1024 * 1024;
  private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "guard-log"); t.setDaemon(true); return t; });
  private static volatile File directory;
  private static volatile boolean enabled;
  private static boolean crashHook;

  private AppLog() {}

  static synchronized void init(Context context, boolean on) {
    directory = context.getApplicationContext().getFilesDir();
    setEnabled(on);
    if (crashHook) return;
    crashHook = true;
    Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      if (enabled && directory != null) {
        WRITER.shutdown(); // the lines already queued come first
        try { WRITER.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        append(line("CRASH", "thread=" + thread.getName() + "\n" + Log.getStackTraceString(error)));
      }
      if (previous != null) previous.uncaughtException(thread, error);
    });
  }

  /** The switch itself is logged on both sides, so a report shows where the gaps are. */
  static synchronized void setEnabled(boolean on) {
    if (on == enabled) return;
    if (!on) add("Log", "recording of logs off");
    enabled = on;
    if (on) add("Log", "recording of logs on");
  }

  static boolean enabled() { return enabled; }

  static void i(String tag, String message) { Log.i(tag, message); add(tag, message); }
  static void d(String tag, String message) { Log.d(tag, message); add(tag, message); }
  static void w(String tag, String message) { Log.w(tag, message); add(tag, "WARN " + message); }
  static void w(String tag, String message, Throwable error) { Log.w(tag, message, error); add(tag, "WARN " + message + "\n" + Log.getStackTraceString(error)); }

  /** Everything written so far is on storage before a report reads the files. */
  static void flush() {
    try { WRITER.submit(() -> {}).get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
  }

  private static void add(String tag, String message) {
    if (!enabled || directory == null) return;
    String text = line(tag, message);
    try { WRITER.execute(() -> append(text)); } catch (RejectedExecutionException ignored) {}
  }

  private static String line(String tag, String message) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + tag + " " + message + "\n";
  }

  private static synchronized void append(String text) {
    File dir = directory;
    if (dir == null) return;
    File file = new File(dir, CURRENT);
    if (file.length() > MAX_BYTES) { File old = new File(dir, PREVIOUS); old.delete(); file.renameTo(old); }
    try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    catch (IOException ignored) {}
  }
}
