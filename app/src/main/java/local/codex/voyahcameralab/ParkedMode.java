package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Runs the optional behaviour of a locked car while the camera is open: screens, climate and the alarm. */
final class ParkedMode {
  /** Preference keys of the alarm distance, in AvmLayout zone order: front, left, rear, right. */
  static final String[] REACH_KEYS = {"alarm_reach_front_cm", "alarm_reach_left_cm", "alarm_reach_rear_cm", "alarm_reach_right_cm"};
  private static final long TICK_MS = 10000, START_RESTORE_MS = 2000, WATCH_MS = 1000, LIGHTS_SETTLE_MS = 3000;
  /** Screens retry for five minutes; the climate for one, so it does not fight an owner who switches it off. */
  private static final int RESTORE_ATTEMPTS = 30, CLIMATE_ATTEMPTS = 6, LIGHT_ATTEMPTS = 10;
  private static final String DARK = "screens_dark", TAG = "SentryParked";

  private final SharedPreferences preferences;
  private final BooleanSupplier holding;
  private final Runnable unlocked;
  private final ScreenControl screens;
  private final VoiceWarning voice;
  private final ParkedPolicy policy = new ParkedPolicy();
  private final HandlerThread thread = new HandlerThread("SentryParked");
  private final Handler handler;
  private final Runnable tick = this::tick, retry = this::retryRestore, watch = this::watchVoice;
  /** The alarm lights block for their flashes, so they have their own thread; one run at a time. */
  private final ExecutorService lights = Executors.newSingleThreadExecutor(r -> new Thread(r, "SentryLight"));
  private final AtomicBoolean flashing = new AtomicBoolean();
  /** Where the bridge keeps the owner's lights while an alarm flashes them. */
  private final File savedLights;
  /** Without it the car refuses the light commands, so the alarm does not even try. */
  private final boolean lightsAllowed;

  // Settings, read on the main thread and used on the handler and analysis threads.
  private volatile boolean screensOption, climateOption, voiceOption, lightOption, unlockOption, alarmOption, running, guarded;
  private volatile int lampsOption, phrases = WarningPolicy.DEFAULT_PHRASES;
  /** The detector sees the alarm's own lights; until then its motion does not count for the alarm. */
  private volatile long lightsQuietUntil;
  private volatile int[] reachCm = WarningPolicy.DEFAULT_REACH_CM.clone();
  private volatile WarningPolicy warning = new WarningPolicy(WarningPolicy.DEFAULT_DELAY, WarningPolicy.DEFAULT_REPEAT, WarningPolicy.DEFAULT_PER_HOUR);

  // Handler thread only.
  private boolean screensPending, climatePending, climateHeldOff, lastLocked;
  private int attempts, climateAttempts;

  /**
   * @param holding whether this recording holds the power: the climate option belongs to it
   * @param unlocked told once per unlock of a car that was locked: never for an unreadable status
   */
  ParkedMode(Context context, BooleanSupplier holding, Runnable unlocked) {
    this.unlocked = unlocked;
    preferences = context.getSharedPreferences("camera", Context.MODE_PRIVATE);
    this.holding = holding;
    lightsAllowed = GuardControl.canFlashLights(context);
    AvmViewControl.setApk(context.getApplicationInfo().sourceDir); // the bridges below need it before the camera starts
    screens = new ScreenControl(context);
    voice = new VoiceWarning(context, preferences);
    thread.start();
    handler = new Handler(thread.getLooper());
    screens.connect();
    // A run killed in the dark gives the screens back at the next start, once the service is bound.
    screensPending = preferences.getBoolean(DARK, false);
    handler.post(voice::restoreAfterKill);
    if (screensPending) handler.postDelayed(retry, START_RESTORE_MS);
    // A run killed while flashing gives the owner's lights back.
    savedLights = new File(GuardBridge.SAVED_PATH);
    if (savedLights.exists()) lights.execute(this::restoreSavedLights);
  }

  /** Main thread, about once a second while frames come, and after the settings change. Must be cheap and keep state. */
  void start() {
    screensOption = preferences.getBoolean("screens_off", false);
    climateOption = preferences.getBoolean("climate_off", false);
    voiceOption = preferences.getBoolean("voice_warning", false);
    unlockOption = preferences.getBoolean("stop_on_unlock", false);
    lightOption = lightsAllowed && preferences.getBoolean("alarm_light", false);
    lampsOption = lightsAllowed ? preferences.getInt("alarm_lamps", GuardControl.LAMPS_NONE) : GuardControl.LAMPS_NONE;
    int[] reach = new int[REACH_KEYS.length];
    for (int zone = 0; zone < reach.length; zone++) reach[zone] = WarningPolicy.pick(WarningPolicy.REACH_CM, preferences.getInt(REACH_KEYS[zone], WarningPolicy.DEFAULT_REACH_CM[zone]), WarningPolicy.DEFAULT_REACH_CM[zone]);
    reachCm = reach;
    alarmOption = voiceOption || lightOption || lampsOption != GuardControl.LAMPS_NONE;
    int delay = preferences.getInt("voice_delay_seconds", WarningPolicy.DEFAULT_DELAY), repeat = preferences.getInt("voice_repeat_seconds", WarningPolicy.DEFAULT_REPEAT),
        perHour = preferences.getInt("voice_per_hour", WarningPolicy.DEFAULT_PER_HOUR);
    if (!warning.sameSettings(delay, repeat, perHour)) { warning = new WarningPolicy(delay, repeat, perHour); AppLog.i(TAG, "voice delay=" + delay + " repeat=" + repeat + " perHour=" + perHour); }
    phrases = WarningPolicy.pick(WarningPolicy.PHRASES, preferences.getInt("voice_phrases", WarningPolicy.DEFAULT_PHRASES), WarningPolicy.DEFAULT_PHRASES);
    boolean wanted = screensOption || climateOption || alarmOption || unlockOption;
    if (wanted == running) return;
    AppLog.i(TAG, (wanted ? "on" : "off") + " screens=" + screensOption + " climate=" + climateOption + " voice=" + voiceOption + " light=" + lightOption + " lamps=" + lampsOption + " stopOnUnlock=" + unlockOption);
    running = wanted;
    handler.removeCallbacks(tick);
    if (wanted) handler.post(tick);
    else handler.post(this::undo);
  }

  void stop() {
    running = false;
    handler.removeCallbacks(tick);
    handler.post(this::undo);
  }

  void shutdown() {
    stop();
    handler.post(() -> { voice.stop(); voice.endScenario(); retryRestore(); screens.disconnect(); thread.quitSafely(); });
    lights.execute(this::restoreSavedLights);
    lights.shutdown();
  }

  void testVoice() { handler.post(() -> voice.play(false, phrases)); }

  /** Analysis thread. Only a guarded car speaks: nobody is shouted at from inside their own car. */
  void onMotion(long now, double[] gaps) {
    if (!alarmOption || !guarded || flashing.get() || now < lightsQuietUntil) return;
    if (WarningPolicy.alarming(gaps, reachCm) && warning.onMotion(now)) handler.post(this::alarm);
  }

  /** The owner at the door may have unlocked since the last reading. */
  private void alarm() {
    ParkedPolicy.Reading reading = GuardControl.read();
    observe(reading);
    boolean still = ParkedPolicy.guarded(reading);
    boolean ambient = lightOption;
    int lamps = lampsOption;
    AppLog.i(TAG, still ? "alarm: voice=" + voiceOption + " phrases=" + phrases + " light=" + ambient + " lamps=" + lamps : "alarm skipped: no longer locked");
    if (!still) { guarded = false; return; }
    if (voiceOption) {
      voice.play(true, phrases);
      handler.removeCallbacks(watch);
      handler.postDelayed(watch, WATCH_MS);
    }
    if ((ambient || lamps != GuardControl.LAMPS_NONE) && flashing.compareAndSet(false, true)) {
      try {
        lights.execute(() -> {
          try {
            if (!GuardControl.alarmLights(ambient, lamps)) AppLog.w(TAG, "alarm lights did not end cleanly");
            restoreSavedLights();
          } finally { lightsQuietUntil = android.os.SystemClock.elapsedRealtime() + LIGHTS_SETTLE_MS; flashing.set(false); }
        });
      } catch (RejectedExecutionException closed) { flashing.set(false); }
    }
  }

  /** Lights executor: the owner's lights come back, retried while the saved file says they are not. */
  private void restoreSavedLights() {
    for (int attempt = 1; savedLights.exists() && attempt <= LIGHT_ATTEMPTS; attempt++) {
      if (GuardControl.restoreLights()) return;
      AppLog.w(TAG, "lights not restored, attempt " + attempt);
      try { Thread.sleep(TICK_MS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
    }
  }

  /** The one place that turns readings into "the car was unlocked"; an unreadable reading proves nothing. */
  private void observe(ParkedPolicy.Reading reading) {
    if (reading == null) return;
    if (reading.armed) lastLocked = true;
    else if (lastLocked) { lastLocked = false; unlocked.run(); }
  }

  /** While the phrase sounds, the lock is read every second: the owner at the door must not be shouted at. */
  private void watchVoice() {
    if (!voice.playing()) return;
    ParkedPolicy.Reading reading = GuardControl.read();
    if (ParkedPolicy.guarded(reading)) { handler.postDelayed(watch, WATCH_MS); return; }
    AppLog.i(TAG, "unlocked during the warning: voice stopped");
    guarded = false;
    observe(reading);
    voice.endScenario();
    handler.removeCallbacks(tick);
    if (running) handler.post(tick);
  }

  private void tick() {
    if (!running) return;
    ParkedPolicy.Reading reading = GuardControl.read();
    boolean was = guarded;
    guarded = ParkedPolicy.guarded(reading);
    if (guarded != was) AppLog.i(TAG, (guarded ? "locked" : "unlocked") + (reading == null ? " (status unreadable)" : " armed=" + reading.armed + " air=" + reading.airOn + " care=" + reading.careMode));
    observe(reading);
    if (!guarded) voice.endScenario();
    // Seen on the car: the climate unit may ignore a command, so an off that did not take is sent again.
    boolean stillAiring = climateHeldOff && guarded && reading != null && reading.airOn;
    apply(policy.update(reading, screensOption, climateOption && holding.getAsBoolean()));
    if (stillAiring && climateHeldOff) { AppLog.i(TAG, "climate still on: off again"); GuardControl.climate(false); }
    handler.postDelayed(tick, TICK_MS);
  }

  private void undo() { guarded = false; lastLocked = false; voice.stop(); voice.endScenario(); apply(policy.stop()); }

  private void apply(List<ParkedPolicy.Action> actions) {
    for (ParkedPolicy.Action action : actions) {
      if (action != ParkedPolicy.Action.KEEP_DARK) AppLog.i(TAG, "action " + action);
      switch (action) {
        case DARKEN: screensPending = false; preferences.edit().putBoolean(DARK, true).apply(); screens.darken(); break;
        case KEEP_DARK: screens.keepDark(); break;
        case RESTORE_SCREENS: screensPending = true; break;
        case CLIMATE_OFF: climatePending = false; climateHeldOff = true; GuardControl.climate(false); break;
        case CLIMATE_ON: climatePending = true; climateHeldOff = false; climateAttempts = 0; break;
        default: break;
      }
    }
    if (screensPending || climatePending) { attempts = 0; retryRestore(); }
  }

  /** A restore is not done until it worked: a dim screen or a stopped climate must not stay. */
  private void retryRestore() {
    handler.removeCallbacks(retry);
    if (screensPending && screens.restore()) { screensPending = false; preferences.edit().putBoolean(DARK, false).apply(); }
    // Seen on the car: an on sent right after the unlock was ignored, the unit was still asleep. It is
    // repeated until the climate reads on, which also ends it if the owner switched it on by hand.
    if (climatePending) {
      ParkedPolicy.Reading reading = GuardControl.read();
      if (reading != null && reading.airOn) { climatePending = false; AppLog.i(TAG, "climate back on"); }
      else if (++climateAttempts > CLIMATE_ATTEMPTS) { climatePending = false; AppLog.w(TAG, "climate did not come back on"); }
      else GuardControl.climate(true);
    }
    if ((screensPending || climatePending) && ++attempts < RESTORE_ATTEMPTS) handler.postDelayed(retry, TICK_MS);
    else if (screensPending || climatePending) AppLog.w(TAG, "restore gave up: screens=" + screensPending + " climate=" + climatePending);
  }
}
