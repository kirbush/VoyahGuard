package local.codex.voyahcameralab;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the view following and the motion detector beside the camera. Frames arrive at most at
 * the sampling rate into one slot, so a slow analysis drops frames and can never delay the
 * encoder. Any failure here stops the automatic views and leaves the recording alone. Closing
 * the 360 screen stops only the following: the detector keeps looking at the panorama, but what
 * it misses then proves nothing, because the panorama is not known to stay live on a hidden screen.
 */
final class AdaptiveController implements GlCameraRenderer.FrameSampler {
  interface Listener {
    void onAdaptiveStatus(String text);
    /** Somebody was near the car at this moment; gaps in metres per zone, for the alarm. Analysis thread. */
    void onMotion(long now, double[] gaps);
    /** Something travels across the camera panel, further away. Analysis thread. */
    void onCameraMotion(long now);
    /** Whether the absence of onMotion means anything. Any thread. */
    void onWatching(boolean trusted, long now);
  }
  private static final String TAG = "SentryAdaptive";
  private static final long GUARD_MS = 5000;
  /** A fresh camera-panel model needs a moment to learn the new picture as background. */
  private static final long CAMERA_WARMUP_MS = 3000;
  private final CameraTracker tracker = new CameraTracker();
  private int cameraMarker, markerCandidate, markerVotes;
  private long cameraSince;
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
  private volatile boolean active, detecting, wanted, threeD, halted;
  private boolean opened, commanded;
  private AdaptiveViewPolicy policy;
  private MotionAnalyzer analyzer;
  private BufferedWriter log;
  private int logged;
  private long lastGuard, dropped, samples, detectFrom;
  private int motionStreak;
  private volatile int home = AvmLayout.FRONT;
  private String status = "";

  AdaptiveController(Context context, Listener listener) {
    this.context = context; this.listener = listener;
    AvmViewControl.setApk(context.getApplicationInfo().sourceDir);
    analysisThread.start();
    analysis = new Handler(analysisThread.getLooper());
  }

  /** Main thread, once the camera delivers frames. */
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
    wanted = false; active = false; detecting = false;
    generation++;
    distrust();
    analysis.post(this::finishRun);
    boolean wasThreeD = threeD;
    int back = home;
    control.execute(() -> {
      try {
        // Never command the car outside P: a 360 this run opened then stays for the driver to close.
        if ((commanded || opened) && AvmViewControl.parked() && AvmViewControl.showing()) {
          if (commanded) { if (wasThreeD) AvmViewControl.sendButton(back); else AvmViewControl.send(back); }
          if (opened) AvmViewControl.close();
        }
      } catch (Exception error) { AppLog.w(TAG, "Return", error); }
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
      boolean detect = reason.isEmpty() && AvmViewControl.parked();
      if (reason.isEmpty() && !detect) reason = "нужен режим P";
      if (run != generation) return;
      if (reason.isEmpty() && !AvmViewControl.showing()) {
        publish("Адаптивный вид: открытие штатного приложения камер 360");
        opened = true;
        AvmViewControl.open();
        if (run != generation) return;
        if (!AvmViewControl.showing()) reason = "штатное приложение камер 360 не открылось";
      }
      if (run != generation) return;
      boolean follow = reason.isEmpty();
      if (!follow) halt(run, reason, false);
      if (detect) analysis.post(() -> beginRun(run, follow));
    } catch (Exception error) { halt(run, error.toString(), false); }
  }

  private void beginRun(int run, boolean follow) {
    if (run != generation) return;
    try {
      System.loadLibrary("opencv_java4");
      releaseAnalyzer();
      analyzer = new MotionAnalyzer(threeD);
    } catch (Throwable error) { halt(run, "детектор не загрузился: " + error.getMessage(), false); return; }
    long now = SystemClock.elapsedRealtime();
    int start = threeD ? AvmLayout.HOME_3D : AvmLayout.FRONT;
    policy = new AdaptiveViewPolicy(now, start, threeD ? AvmLayout.BUTTONS : AvmLayout.VIEWS, threeD);
    lastGuard = now; samples = 0; dropped = 0; home = start; motionStreak = 0; detectFrom = now + AdaptiveViewPolicy.WARMUP_MS; cameraMarker = Integer.MIN_VALUE;
    openLog();
    detecting = true;
    if (follow) { active = true; publish("Адаптивный вид: определение ракурса"); }
    if (run != generation) { active = false; detecting = false; } // stop() ran while this was starting
  }

  private void finishRun() {
    policy = null; closeLog();
  }

  private void releaseAnalyzer() { if (analyzer != null) { analyzer.release(); analyzer = null; } }

  @Override public boolean wantsFrame() { return active || detecting; }

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

  @Override public void samplerFailed(String error) { halt(generation, "выборка кадров: " + error, false); }

  private void analyse() {
    byte[] frame = pending.getAndSet(null);
    if (frame == null) return;
    System.arraycopy(frame, 0, working, 0, working.length);
    spare.set(frame);
    int run = generation;
    AdaptiveViewPolicy current = policy;
    if (!detecting || current == null) return;
    try {
      long started = SystemClock.elapsedRealtime();
      int glyph = AvmLayout.selectedView(working);
      int marker = threeD ? AvmLayout.selectedButton(working) : glyph;
      MotionAnalyzer.Reading reading = analyzer.process(working);
      boolean following = active;
      int request = following ? current.onSample(started, marker, glyph != AvmLayout.VIEW_UNKNOWN, reading.near) : AvmLayout.VIEW_UNKNOWN;
      home = current.home();
      long took = SystemClock.elapsedRealtime() - started;
      samples++;
      record(started, marker, current, reading, request, took);
      detect(started, reading, !following || current.settled(started));
      detectCamera(started, marker, reading);
      if (request != AvmLayout.VIEW_UNKNOWN) command(run, current, request);
      if (following) {
        if (current.stopped() != AdaptiveViewPolicy.Stop.NONE) halt(run, reason(current.stopped()), false);
        else report(current, started);
        if (active && current.settled(started)) listener.onWatching(true, started);
      }
      if (started - lastGuard >= GUARD_MS) { lastGuard = started; control.execute(() -> guard(run)); }
    } catch (Exception error) {
      AppLog.w(TAG, "Analysis", error);
      halt(run, "анализ: " + error.getMessage(), false);
    }
  }

  /** A view being switched repaints part of the panorama, so those samples say nothing. */
  private void detect(long now, MotionAnalyzer.Reading reading, boolean settled) {
    boolean near = false;
    for (boolean side : reading.near) near |= side;
    motionStreak = near && settled && !reading.rejected && now >= detectFrom ? motionStreak + 1 : 0;
    if (motionStreak >= AdaptiveViewPolicy.CONFIRM_SAMPLES) listener.onMotion(now, reading.gaps);
  }

  /** The panel counts only while it keeps showing the same camera; a switch starts it over. */
  private void detectCamera(long now, int marker, MotionAnalyzer.Reading reading) {
    if (marker != cameraMarker) {
      if (marker != markerCandidate) { markerCandidate = marker; markerVotes = 0; }
      if (++markerVotes < 2) return; // a glyph read once is not a switch of camera
      cameraMarker = marker; cameraSince = now; tracker.reset(); analyzer.resetCamera(); return;
    }
    markerVotes = 0;
    if (now < detectFrom || now - cameraSince < CAMERA_WARMUP_MS) return;
    if (reading.cameraNoisy) { tracker.reset(); return; }
    if (tracker.update(now, reading.cameraCentres)) listener.onCameraMotion(now);
  }

  private void command(int run, AdaptiveViewPolicy current, int view) {
    control.execute(() -> {
      if (run != generation || !active) return;
      if (!AvmViewControl.parked()) { halt(run, "машина не в P", false); return; }
      try {
        commanded = true;
        boolean sent = view == AvmLayout.TOGGLE_2D ? AvmViewControl.toggleMode() : threeD ? AvmViewControl.sendButton(view) : AvmViewControl.send(view);
        if (!sent) analysis.post(current::commandFailed);
      } catch (Exception error) { analysis.post(current::commandFailed); }
    });
  }

  /** Stops the commands when the car leaves P or the 360 screen goes away. */
  private void guard(int run) {
    if (run != generation || !detecting) return;
    if (!AvmViewControl.parked()) halt(run, "машина не в P", false);
    else if (active && !AvmViewControl.showing()) halt(run, "штатное приложение камер 360 закрыто", true);
  }

  /** @param keepDetecting the frames still show the panorama, so people near the car still count */
  private void halt(int run, String reason, boolean keepDetecting) {
    if (run != generation) return;
    active = false; halted = true;
    if (!keepDetecting) detecting = false;
    distrust();
    publish("Адаптивный вид остановлен: " + reason);
  }

  /** On the analysis thread, so a sample in progress cannot report trust after this. */
  private void distrust() { analysis.post(() -> listener.onWatching(false, SystemClock.elapsedRealtime())); }

  private static String reason(AdaptiveViewPolicy.Stop stop) {
    switch (stop) {
      case NOT_CONFIRMED: return "вид не подтвердился";
      case VIEW_LOST: return "ракурс не определён, нужен режим 2D в штатном приложении камер 360";
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
  boolean following() { return active; }

  private void openLog() {
    closeLog();
    try {
      File dir = context.getExternalFilesDir(null);
      if (dir == null) return;
      File file = new File(dir, "adaptive-log.csv"), previous = new File(dir, "adaptive-log.1.csv");
      if (file.exists() && !file.renameTo(previous)) AppLog.w(TAG, "Log rotation failed");
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
