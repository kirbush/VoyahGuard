package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.function.BooleanSupplier;

/** Runs the optional behaviour of a locked car while the camera is open. */
final class ParkedMode {
  private static final long TICK_MS = 10000;
  private static final String DARK = "screens_dark";

  private final SharedPreferences preferences;
  private final BooleanSupplier holding;
  private final ScreenControl screens;
  private final VoiceWarning voice;
  private final ParkedPolicy policy = new ParkedPolicy();
  private final WarningPolicy warning = new WarningPolicy();
  private final HandlerThread thread = new HandlerThread("SentryParked");
  private final Handler handler;
  private final Runnable tick = this::tick;
  private volatile boolean armed, screensOption, climateOption, voiceOption, running;

  /** @param holding whether this recording holds the power: the climate option belongs to it */
  ParkedMode(Context context, BooleanSupplier holding) {
    preferences = context.getSharedPreferences("camera", Context.MODE_PRIVATE);
    this.holding = holding;
    screens = new ScreenControl(context);
    voice = new VoiceWarning(context);
    thread.start();
    handler = new Handler(thread.getLooper());
    screens.connect();
    // A run killed with dark screens gives them back at the next start.
    handler.postDelayed(() -> { if (preferences.getBoolean(DARK, false)) { screens.restore(); preferences.edit().putBoolean(DARK, false).apply(); } }, 2000);
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
    handler.post(() -> { screens.disconnect(); thread.quitSafely(); });
  }

  void testVoice() { handler.post(voice::play); }

  /** Analysis thread. Only a locked car speaks: nobody is shouted at from inside their own car. */
  void onMotion(long now) {
    if (voiceOption && armed && warning.onMotion(now)) handler.post(voice::play);
  }

  private void tick() {
    if (!running) return;
    ParkedPolicy.Reading reading = GuardControl.read();
    armed = reading != null && reading.armed;
    apply(policy.update(reading, screensOption, climateOption && holding.getAsBoolean()));
    handler.postDelayed(tick, TICK_MS);
  }

  private void undo() { armed = false; apply(policy.stop()); }

  private void apply(java.util.List<ParkedPolicy.Action> actions) {
    for (ParkedPolicy.Action action : actions) {
      switch (action) {
        case DARKEN: preferences.edit().putBoolean(DARK, true).apply(); screens.darken(); break;
        case KEEP_DARK: screens.keepDark(); break;
        case RESTORE_SCREENS: screens.restore(); preferences.edit().putBoolean(DARK, false).apply(); break;
        case CLIMATE_OFF: GuardControl.climate(false); break;
        case CLIMATE_ON: GuardControl.climate(true); break;
        default: break;
      }
    }
  }
}
