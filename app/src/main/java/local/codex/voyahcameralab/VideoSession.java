package local.codex.voyahcameralab;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One recording: takes chunks from the encoder and motion from the detector, on their threads. */
final class VideoSession {
  private static final String TAG = "SentryVideo";
  private static final ExecutorService SPACE = Executors.newSingleThreadExecutor();
  /** How long after the start of a recording the detector may take to begin. */
  private static final long DETECTOR_START_MS = 10000;

  private final RecordingStore store;
  private final File directory;
  private final String session = RecordingStore.newSession();
  private final VideoTimeline timeline;
  private final Consumer<Uri> saved;
  private final AtomicBoolean spaceCheck = new AtomicBoolean();
  private volatile IOException spaceFailure;
  private long watchedSince = -1, lastStamp;
  private int sequence, closed;

  VideoSession(Context context, boolean keepQuiet, Consumer<Uri> saved) throws IOException {
    store = new RecordingStore(context);
    timeline = new VideoTimeline(keepQuiet);
    this.saved = saved;
    VideoPublisher.live(session);
    directory = store.sessionDirectory(session);
    store.ensureSpace(this);
  }

  /** The hidden file of a new chunk. */
  synchronized File newChunk() throws IOException {
    long stamp = StoragePolicy.nextStamp(System.currentTimeMillis(), lastStamp);
    lastStamp = stamp;
    File part = new File(directory, StoragePolicy.chunkName(stamp, ++sequence) + ".part");
    if (!part.createNewFile()) throw new IOException("Не удалось создать файл записи");
    return part;
  }

  void discard(File part) { if (part != null) part.delete(); }

  /** A chunk is closed: only then may it be read back. */
  synchronized void chunk(File part, long startMs, long endMs) throws IOException {
    File finished = new File(directory, part.getName().substring(0, part.getName().length() - ".part".length()));
    if (!part.renameTo(finished)) throw new IOException("Не удалось завершить файл записи");
    boolean first = ++closed == 1;
    boolean watched = watchedSince >= 0 && watchedSince <= startMs + (first ? DETECTOR_START_MS : 0);
    apply(timeline.chunk(new VideoTimeline.Chunk(finished, startMs, endMs, StoragePolicy.chunkWall(finished.getName()), finished.length(), watched)));
  }

  /** The detector can be believed from now on, or no longer. */
  synchronized void watching(boolean on, long now) {
    if (on && watchedSince < 0) watchedSince = now;
    else if (!on) watchedSince = -1;
  }

  synchronized void motion(long now) { apply(timeline.motion(now)); }

  synchronized boolean inEvent() { return timeline.inEvent(); }

  /** Queued after the last video, so the session stays protected from recovery until then. */
  synchronized void finish() {
    apply(timeline.finish());
    VideoPublisher.submit(() -> removeDirectory());
  }

  /** Finished chunks still here failed to join: they stay, and the next start tries again. */
  private void removeDirectory() {
    File[] files = directory.listFiles();
    if (files != null) for (File file : files) if (file.getName().endsWith(".part")) file.delete();
    if (directory.delete()) Log.d(TAG, "Session done: " + session);
  }

  private void apply(VideoTimeline.Step step) {
    for (VideoTimeline.Chunk chunk : step.dropped) chunk.file.delete();
    for (VideoTimeline.Video video : step.videos) publish(video);
  }

  private void publish(VideoTimeline.Video video) {
    VideoPublisher.submit(() -> {
      List<File> parts = new ArrayList<>();
      for (VideoTimeline.Chunk chunk : video.chunks) parts.add(chunk.file);
      try { VideoPublisher.join(store, parts, video.kind == VideoTimeline.Kind.EVENT, saved); }
      catch (IOException failure) { Log.w(TAG, "Video not joined, chunks kept", failure); }
    });
  }

  /** Space is checked beside the encoder: deleting a video must not delay a frame. */
  void checkSpace() throws IOException {
    IOException failure = spaceFailure;
    if (failure != null) throw failure;
    if (!spaceCheck.compareAndSet(false, true)) return;
    SPACE.execute(() -> {
      try { store.ensureSpace(this); }
      catch (IOException error) { spaceFailure = error; }
      catch (RuntimeException error) { Log.w(TAG, "Space check", error); } // MediaStore must not end the recording.
      finally { spaceCheck.set(false); }
    });
  }

  /** The start of the running video, oldest first. The newest chunk is never offered. */
  synchronized List<StoragePolicy.Reclaimable> heldStart() {
    List<VideoTimeline.Chunk> held = timeline.held();
    List<StoragePolicy.Reclaimable> start = new ArrayList<>();
    for (int i = 0; i < held.size() - 1; i++) {
      VideoTimeline.Chunk chunk = held.get(i);
      start.add(new StoragePolicy.Reclaimable() {
        @Override public long bytes() { return chunk.bytes; }
        @Override public boolean reclaim() { return trim(chunk); }
      });
    }
    return start;
  }

  private synchronized boolean trim(VideoTimeline.Chunk chunk) { return timeline.trim(chunk) && chunk.file.delete(); }
}
