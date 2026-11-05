package local.codex.voyahcameralab;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * Plays the bundled warning on the alarm stream at full volume, then puts the volume back.
 * The caller's thread needs a Looper: the player reports its end there.
 */
final class VoiceWarning {
  private static final String TAG = "SentryVoice";
  private static final int STREAM = AudioManager.STREAM_ALARM, REPEATS = 2;
  private final Context context;
  private final AudioManager audio;
  private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
  private boolean playing;

  VoiceWarning(Context context) {
    this.context = context.getApplicationContext();
    audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
  }

  void play() {
    if (playing) return;
    int before;
    AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build();
    try {
      before = audio.getStreamVolume(STREAM);
      audio.setStreamVolume(STREAM, audio.getStreamMaxVolume(STREAM), 0);
    } catch (RuntimeException failure) { Log.w(TAG, "Volume", failure); return; }
    audio.requestAudioFocus(focus);
    MediaPlayer player = null;
    try { player = MediaPlayer.create(context, R.raw.step_away, attributes, audio.generateAudioSessionId()); }
    catch (RuntimeException failure) { Log.w(TAG, "Player", failure); }
    if (player == null) { finish(null, focus, before); return; }
    playing = true;
    int[] left = {REPEATS};
    player.setOnCompletionListener(done -> {
      if (--left[0] > 0) done.start();
      else finish(done, focus, before);
    });
    player.setOnErrorListener((failed, what, extra) -> { Log.w(TAG, "Playback " + what); finish(failed, focus, before); return true; });
    player.start();
  }

  private void finish(MediaPlayer player, AudioFocusRequest focus, int before) {
    playing = false;
    if (player != null) player.release();
    audio.abandonAudioFocusRequest(focus);
    try { audio.setStreamVolume(STREAM, before, 0); }
    catch (RuntimeException failure) { Log.w(TAG, "Volume back", failure); }
  }
}
