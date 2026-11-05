package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs the optional behaviour of a locked car while the camera is open. */
final class ParkedMode {
  private static final long TICK_MS = 10000, START_RESTORE_MS = 2000;
  private static final int RESTORE_ATTEMPTS = 30;
  private static final String DARK = "screens_dark";

  private final SharedPreferences preferences;
  private final BooleanSupplier holding;
  private final ScreenControl screens;
  private final VoiceWarning voice;
  private final ParkedPolicy policy = new ParkedPolicy();
  private final WarningPolicy warning = new WarningPolicy();
  private final HandlerThread thread = new HandlerThread("SentryParked");
  private final Handler handler;
  private final Runnable tick = this::tick, retry = this::retryRestore;
  private volatile boolean guarded, screensOption, climateOption, voiceOption, running;
  private boolean screensPending, climatePending;
  private int attempts;

  /** @param holding whether this recording holds the power: the climate option belongs to it */
  ParkedMode(Context context, BooleanSupplier holding) {
    preferences = context.getSharedPreferences("camera", Context.MODE_PRIVATE);
    this.holding = holding;
    screens = new ScreenControl(context);
    voice = new VoiceWarning(context, preferences);
    thread.start();
    handler = new Handler(thread.getLooper());
    screens.connect();
    // A run killed in the dark gives the screens back at the next start, once the service is bound.
    screensPending = preferences.getBoolean(DARK, false);
    handler.post(voice::restoreAfterKill);
    if (screensPending) handler.postDelayed(retry, START_RESTORE_MS);
  }

  /** Main thread, once the camera delivers frames, and after the settings change. */
  void start() {
    screensOption = preferences.getBoolean("screens_off", false);
    climateOption = preferences.getBoolean("climate_off", false);
    voiceOption = preferences.getBoolean("voice_warning", false);
    boolean wanted = screensOption || climateOption || voiceOption;
    if (wanted == running) return;
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
    handler.post(() -> { voice.stop(); retryRestore(); screens.disconnect(); thread.quitSafely(); });
  }

  void testVoice() { handler.post(voice::play); }

  /** Analysis thread. Only a guarded car speaks: nobody is shouted at from inside their own car. */
  void onMotion(long now) {
    if (voiceOption && guarded && warning.onMotion(now)) handler.post(this::warn);
  }

  /** The owner at the door may have unlocked since the last reading. */
  private void warn() {
    if (ParkedPolicy.guarded(GuardControl.read())) voice.play();
  }

  private void tick() {
    if (!running) return;
    ParkedPolicy.Reading reading = GuardControl.read();
    guarded = ParkedPolicy.guarded(reading);
    apply(policy.update(reading, screensOption, climateOption && holding.getAsBoolean()));
    handler.postDelayed(tick, TICK_MS);
  }

  private void undo() { guarded = false; apply(policy.stop()); }

  private void apply(List<ParkedPolicy.Action> actions) {
    for (ParkedPolicy.Action action : actions) {
      switch (action) {
        case DARKEN: screensPending = false; preferences.edit().putBoolean(DARK, true).apply(); screens.darken(); break;
        case KEEP_DARK: screens.keepDark(); break;
        case RESTORE_SCREENS: screensPending = true; break;
        case CLIMATE_OFF: climatePending = false; GuardControl.climate(false); break;
        case CLIMATE_ON: climatePending = true; break;
        default: break;
      }
    }
    if (screensPending || climatePending) { attempts = 0; retryRestore(); }
  }

  /** A restore is not done until it worked: a dim screen or a stopped climate must not stay. */
  private void retryRestore() {
    handler.removeCallbacks(retry);
    if (screensPending && screens.restore()) { screensPending = false; preferences.edit().putBoolean(DARK, false).apply(); }
    if (climatePending && GuardControl.climate(true)) climatePending = false;
    if ((screensPending || climatePending) && ++attempts < RESTORE_ATTEMPTS) handler.postDelayed(retry, TICK_MS);
  }
}
