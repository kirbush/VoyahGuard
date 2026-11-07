package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * Plays the bundled warning through the media channel at full volume. On this head unit the
 * alarm usage goes to a system bus that never reaches the speakers, and nothing plays without
 * audio focus. The volume stays at maximum until the locked-car scenario ends, then goes back;
 * the old value is stored first, so a run killed in between still restores it at the next start.
 * All calls come from one thread with a Looper: the player reports its end there.
 */
final class VoiceWarning {
  private static final String TAG = "SentryVoice";
  private static final String SAVED = "media_volume_before";
  private static final int STREAM = AudioManager.STREAM_MUSIC, REPEATS = 2;
  private final Context context;
  private final SharedPreferences preferences;
  private final AudioManager audio;
  private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
  private final AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build();
  private MediaPlayer player;
  private boolean playing, keepLoud;

  VoiceWarning(Context context, SharedPreferences preferences) {
    this.context = context.getApplicationContext();
    this.preferences = preferences;
    audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
  }

  /** @param holdVolume keep the volume up for later warnings until {@link #endScenario()} */
  void play(boolean holdVolume) {
    if (playing) return;
    keepLoud = holdVolume;
    try {
      if (!preferences.contains(SAVED)) preferences.edit().putInt(SAVED, audio.getStreamVolume(STREAM)).commit();
      audio.setStreamVolume(STREAM, audio.getStreamMaxVolume(STREAM), 0);
    } catch (RuntimeException failure) { Log.w(TAG, "Volume", failure); restoreVolume(); return; }
    playing = true;
    if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "Audio focus refused");
    try { player = MediaPlayer.create(context, R.raw.step_away, attributes, audio.generateAudioSessionId()); }
    catch (RuntimeException failure) { Log.w(TAG, "Player", failure); }
    if (player == null) { stop(); return; }
    int[] left = {REPEATS};
    player.setOnCompletionListener(done -> {
      if (--left[0] > 0) done.start();
      else stop();
    });
    player.setOnErrorListener((failed, what, extra) -> { Log.w(TAG, "Playback " + what); stop(); return true; });
    player.start();
  }

  void stop() {
    if (!playing) return;
    playing = false;
    if (player != null) { player.release(); player = null; }
    audio.abandonAudioFocusRequest(focus);
    if (!keepLoud) restoreVolume();
  }

  /** The car is no longer guarded, or the app goes away: the owner gets the old volume back. */
  void endScenario() {
    keepLoud = false;
    if (!playing) restoreVolume();
  }

  void restoreAfterKill() { if (!playing) restoreVolume(); }

  private void restoreVolume() {
    if (!preferences.contains(SAVED)) return;
    try { audio.setStreamVolume(STREAM, preferences.getInt(SAVED, audio.getStreamVolume(STREAM)), 0); }
    catch (RuntimeException failure) { Log.w(TAG, "Volume back", failure); return; }
    preferences.edit().remove(SAVED).apply();
  }
}
