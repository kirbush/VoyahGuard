package local.codex.voyahcameralab;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the adaptive view mode beside a recording. Frames arrive at most at the sampling rate
 * into one slot, so a slow analysis drops frames and can never delay the encoder. Any failure
 * here stops the automatic views and leaves the recording alone.
 */
final class AdaptiveController implements GlCameraRenderer.FrameSampler {
  interface Listener {void onAdaptiveStatus(String text);}
  private static final String TAG = "SentryAdaptive";
  private static final long GUARD_MS = 5000;
  private static final int LOG_LIMIT_BYTES = 4 * 1024 * 1024;

  private final Context context;
  private final Listener listener;
  private final ExecutorService control = Executors.newSingleThreadExecutor();
  private final HandlerThread analysisThread = new HandlerThread("SentryAnalysis");
  private final Handler analysis;
  private final AtomicReference<byte[]> pending = new AtomicReference<>(), spare = new AtomicReference<>();
  private final byte[] working = new byte[AvmLayout.BUFFER_BYTES];
  private final Runnable analyse = this::analyse;

  private volatile int generation;
  private volatile boolean active, wanted, threeD, halted;
  private boolean opened, commanded;
  private AdaptiveViewPolicy policy;
  private MotionAnalyzer analyzer;
  private BufferedWriter log;
  private int logged;
  private long lastGuard, dropped, samples;
  private volatile int home = AvmLayout.FRONT;
  private String status = "";

  AdaptiveController(Context context, Listener listener) {
    this.context = context; this.listener = listener;
    analysisThread.start();
    analysis = new Handler(analysisThread.getLooper());
  }

  /** Main thread, after a recording has started. */
  void start(boolean threeD) {
    if (wanted) return;
    wanted = true; this.threeD = threeD; halted = false;
    int run = ++generation;
    publish("Адаптивный вид: подготовка");
    control.execute(() -> prepare(run));
  }

  /** Main thread. Ends the owned control: home view restored, 360 closed if this run opened it. */
  void stop() {
    if (!wanted) return;
    wanted = false; active = false;
    generation++;
    analysis.post(this::finishRun);
    control.execute(() -> {
      try {
        if (commanded) { if (threeD) AvmViewControl.sendButton(home); else AvmViewControl.send(home); }
        if (opened) AvmViewControl.close();
      } catch (Exception error) { Log.w(TAG, "Return", error); }
      commanded = false; opened = false;
    });
    publish("");
  }

  void shutdown() {
    stop();
    control.shutdown();
    analysis.post(() -> { releaseAnalyzer(); analysisThread.quitSafely(); });
  }

  private void prepare(int run) {
    try {
      String reason = AvmViewControl.unavailableReason();
      if (reason.isEmpty() && !AvmViewControl.parked()) reason = "нужен режим P";
      if (reason.isEmpty() && !AvmViewControl.showing()) {
        publish("Адаптивный вид: открытие 360");
        opened = true;
        AvmViewControl.open();
        if (run != generation) return;
        if (!AvmViewControl.showing()) reason = "360 не открылся";
      }
      if (run != generation) return;
      if (!reason.isEmpty()) { halt(run, reason); return; }
      analysis.post(() -> beginRun(run));
    } catch (Exception error) { halt(run, error.toString()); }
  }

  private void beginRun(int run) {
    if (run != generation) return;
    try {
      System.loadLibrary("opencv_java4");
      releaseAnalyzer();
      analyzer = new MotionAnalyzer(threeD);
    } catch (Throwable error) { halt(run, "детектор не загрузился: " + error.getMessage()); return; }
    long now = SystemClock.elapsedRealtime();
    int start = threeD ? AvmLayout.HOME_3D : AvmLayout.FRONT;
    policy = new AdaptiveViewPolicy(now, start, threeD ? AvmLayout.BUTTONS : AvmLayout.VIEWS, threeD);
    lastGuard = now; samples = 0; dropped = 0; home = start;
    openLog();
    active = true;
    publish("Адаптивный вид: определение ракурса");
  }

  private void finishRun() {
    policy = null; closeLog();
  }

  private void releaseAnalyzer() { if (analyzer != null) { analyzer.release(); analyzer = null; } }

  @Override public boolean wantsFrame() { return active; }

  /** Capture thread: a free buffer, or null while the previous frames still wait. */
  @Override public byte[] frameBuffer() {
    byte[] buffer = spare.getAndSet(null);
    return buffer != null ? buffer : new byte[working.length];
  }

  /** Capture thread: replaces any frame the analysis has not reached. */
  @Override public void frameReady(byte[] rgba) {
    byte[] stale = pending.getAndSet(rgba);
    if (stale != null) { dropped++; spare.set(stale); }
    analysis.removeCallbacks(analyse);
    analysis.post(analyse);
  }

  @Override public void samplerFailed(String error) { halt(generation, "выборка кадров: " + error); }

  private void analyse() {
    byte[] frame = pending.getAndSet(null);
    if (frame == null) return;
    System.arraycopy(frame, 0, working, 0, working.length);
    spare.set(frame);
    int run = generation;
    AdaptiveViewPolicy current = policy;
    if (!active || current == null) return;
    try {
      long started = SystemClock.elapsedRealtime();
      int glyph = AvmLayout.selectedView(working);
      int marker = threeD ? AvmLayout.selectedButton(working) : glyph;
      MotionAnalyzer.Reading reading = analyzer.process(working);
      int request = current.onSample(started, marker, glyph != AvmLayout.VIEW_UNKNOWN, reading.near);
      home = current.home();
      long took = SystemClock.elapsedRealtime() - started;
      samples++;
      record(started, marker, current, reading, request, took);
      if (request != AvmLayout.VIEW_UNKNOWN) command(run, current, request);
      if (current.stopped() != AdaptiveViewPolicy.Stop.NONE) halt(run, reason(current.stopped()));
      else report(current, started);
      if (started - lastGuard >= GUARD_MS) { lastGuard = started; control.execute(() -> guard(run)); }
    } catch (Exception error) {
      Log.w(TAG, "Analysis", error);
      halt(run, "анализ: " + error.getMessage());
    }
  }

  private void command(int run, AdaptiveViewPolicy current, int view) {
    control.execute(() -> {
      if (run != generation) return;
      try {
        commanded = true;
        boolean sent = view == AvmLayout.TOGGLE_2D ? AvmViewControl.toggleMode() : threeD ? AvmViewControl.sendButton(view) : AvmViewControl.send(view);
        if (!sent) analysis.post(current::commandFailed);
      } catch (Exception error) { analysis.post(current::commandFailed); }
    });
  }

  /** Stops the commands when the car leaves P or the 360 screen goes away. */
  private void guard(int run) {
    if (run != generation || !active) return;
    if (!AvmViewControl.parked()) halt(run, "машина не в P");
    else if (!AvmViewControl.showing()) halt(run, "360 закрыт");
  }

  private void halt(int run, String reason) {
    if (run != generation) return;
    active = false; halted = true;
    publish("Адаптивный вид остановлен: " + reason + " · запись продолжается");
  }

  private static String reason(AdaptiveViewPolicy.Stop stop) {
    switch (stop) {
      case NOT_CONFIRMED: return "вид не подтвердился";
      case VIEW_LOST: return "ракурс не определён, нужен режим 2D в 360";
      case COMMAND_FAILED: return "команда не отправлена";
      default: return "неизвестно";
    }
  }

  private void report(AdaptiveViewPolicy current, long now) {
    int view = current.current();
    if (view == AvmLayout.VIEW_UNKNOWN) return;
    publish("Адаптивный вид: " + (threeD ? AvmLayout.WATCH_3D[view] : AvmLayout.NAMES[view]) + (current.active(now) ? " · рядом движение" : " · ожидание"));
  }

  private void publish(String text) {
    if (text.equals(status)) return;
    status = text;
    listener.onAdaptiveStatus(text);
  }

  String status() { return status; }
  /** Stopped by itself, for example because the owner closed the 360 screen. */
  boolean halted() { return halted; }

  private void openLog() {
    closeLog();
    try {
      File dir = context.getExternalFilesDir(null);
      if (dir == null) return;
      File file = new File(dir, "adaptive-log.csv"), previous = new File(dir, "adaptive-log.1.csv");
      if (file.exists() && !file.renameTo(previous)) Log.w(TAG, "Log rotation failed");
      log = new BufferedWriter(new FileWriter(file));
      log.write("elapsed_ms,marker,current,home,active,settled,top_blob,near,rejected,request,reason,took_ms,dropped\n");
      logged = 0;
    } catch (IOException error) { log = null; }
  }

  private void record(long now, int marker, AdaptiveViewPolicy current, MotionAnalyzer.Reading reading, int request, long took) {
    if (log == null || logged >= LOG_LIMIT_BYTES) return;
    StringBuilder near = new StringBuilder();
    for (boolean side : reading.near) near.append(side ? '1' : '0');
    String row = String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%s,%d,%d\n", now, marker, current.current(),
        current.home(), current.active(now) ? 1 : 0, current.settled(now) ? 1 : 0, reading.largestBlob,
        near, reading.rejected ? 1 : 0, request,
        request == AvmLayout.VIEW_UNKNOWN ? "" : current.lastReason().name(), took, dropped);
    try {
      log.write(row);
      logged += row.length();
      if (samples % 25 == 0) log.flush();
    } catch (IOException error) { closeLog(); }
  }

  private void closeLog() {
    if (log == null) return;
    try { log.close(); } catch (IOException ignored) { }
    log = null;
  }
}
