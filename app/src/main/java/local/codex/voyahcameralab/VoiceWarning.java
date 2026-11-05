package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * Plays the bundled warning on the alarm stream at full volume, then puts the volume back. The
 * old volume is stored first, so a run killed mid-sentence does not leave the alarm at maximum.
 * All calls come from one thread with a Looper: the player reports its end there.
 */
final class VoiceWarning {
  private static final String TAG = "SentryVoice";
  private static final String SAVED = "alarm_volume_before";
  private static final int STREAM = AudioManager.STREAM_ALARM, REPEATS = 2;
  private final Context context;
  private final SharedPreferences preferences;
  private final AudioManager audio;
  private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
  private final AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build();
  private MediaPlayer player;
  private boolean playing;

  VoiceWarning(Context context, SharedPreferences preferences) {
    this.context = context.getApplicationContext();
    this.preferences = preferences;
    audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
  }

  void restoreAfterKill() {
    if (!playing && preferences.contains(SAVED)) volumeBack();
  }

  void play() {
    if (playing) return;
    try {
      preferences.edit().putInt(SAVED, audio.getStreamVolume(STREAM)).commit();
      audio.setStreamVolume(STREAM, audio.getStreamMaxVolume(STREAM), 0);
    } catch (RuntimeException failure) { Log.w(TAG, "Volume", failure); volumeBack(); return; }
    playing = true;
    audio.requestAudioFocus(focus);
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
    volumeBack();
  }

  private void volumeBack() {
    try { audio.setStreamVolume(STREAM, preferences.getInt(SAVED, audio.getStreamVolume(STREAM)), 0); }
    catch (RuntimeException failure) { Log.w(TAG, "Volume back", failure); }
    preferences.edit().remove(SAVED).apply();
  }
}
