package local.codex.voyahcameralab;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Joins chunks into one MP4 without re-encoding. Runs on one thread for the whole process, so a
 * video started before the activity went away still finishes. A run killed halfway leaves its
 * chunks behind, and the next start joins them.
 */
final class VideoPublisher {
  private static final String TAG = "SentryVideo";
  private static final int SAMPLE_BYTES = 4 * 1024 * 1024;
  private static final long DEFAULT_FRAME_US = 40000;
  private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(work -> {
    Thread thread = new Thread(work, "SentryVideoPublisher");
    thread.setPriority(Thread.MIN_PRIORITY);
    return thread;
  });
  private static final AtomicInteger PENDING = new AtomicInteger();
  /** Sessions still writing or waiting for their last video: recovery leaves them alone. */
  private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();

  private VideoPublisher() { }

  static boolean busy() { return PENDING.get() > 0; }

  static void live(String session) { LIVE.add(session); }

  static void submit(Runnable job) {
    PENDING.incrementAndGet();
    JOBS.execute(() -> {
      try { job.run(); }
      catch (RuntimeException failure) { Log.w(TAG, "Video job", failure); }
      finally { PENDING.decrementAndGet(); }
    });
  }

  /** Runs after every video queued so far: a hold must outlast the file it protects. */
  static void afterPending(Runnable work) { JOBS.execute(work); }

  /** Joins and removes the chunks. Failed chunks stay on disk for the next start. */
  static Uri join(RecordingStore store, List<File> parts, long wallMs, boolean event) throws IOException {
    long bytes = 0;
    for (File part : parts) bytes += part.length();
    // Without room for a second copy each chunk goes as soon as it is in, at the cost of a video
    // that a crash here would lose.
    boolean consume = !store.canHoldCopy(bytes);
    Uri uri = store.createPending(StoragePolicy.videoName(wallMs, event), wallMs);
    try (ParcelFileDescriptor file = store.openPending(uri)) {
      join(parts, file.getFileDescriptor(), consume);
    } catch (IOException | RuntimeException failure) {
      store.discardPending(uri);
      throw failure instanceof IOException ? (IOException) failure : new IOException("Не удалось собрать видео", failure);
    }
    store.publishPending(uri);
    for (File part : parts) part.delete();
    return uri;
  }

  private static void join(List<File> parts, FileDescriptor target, boolean consume) throws IOException {
    MediaMuxer muxer = new MediaMuxer(target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BYTES);
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    int track = -1;
    long offset = 0, samples = 0;
    try {
      for (File part : parts) {
        MediaExtractor source = new MediaExtractor();
        long last = -1, frame = DEFAULT_FRAME_US;
        try {
          source.setDataSource(part.getPath());
          int index = videoTrack(source);
          if (index < 0) continue;
          source.selectTrack(index);
          if (track < 0) { track = muxer.addTrack(source.getTrackFormat(index)); muxer.start(); }
          for (int size; (size = source.readSampleData(buffer, 0)) >= 0; source.advance()) {
            long time = source.getSampleTime();
            boolean key = (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            info.set(0, size, offset + time, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, buffer, info);
            samples++;
            if (last >= 0 && time > last) frame = time - last;
            last = Math.max(last, time);
          }
        } catch (IOException damaged) {
          Log.w(TAG, "Chunk skipped: " + part.getName(), damaged); // One bad chunk must not cost the video.
        } finally {
          source.release();
        }
        if (last >= 0) offset += last + frame;
        if (consume) part.delete();
      }
      if (samples == 0) throw new IOException("Запись не содержит кадров");
      muxer.stop();
    } finally {
      muxer.release();
    }
  }

  private static int videoTrack(MediaExtractor source) {
    for (int i = 0; i < source.getTrackCount(); i++) {
      String type = source.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
      if (type != null && type.startsWith("video/")) return i;
    }
    return -1;
  }

  /** At start: chunks a killed run left, and the one-minute clips of 0.6 and earlier. */
  static void recover(Context context, Consumer<Uri> saved) {
    Context app = context.getApplicationContext();
    submit(() -> {
      try {
        RecordingStore store = new RecordingStore(app);
        store.dropAbandonedPending();
        for (File session : store.sessions()) if (!LIVE.contains(session.getName())) recoverSession(store, session, saved);
        migrateLegacy(store, saved);
      } catch (IOException failure) { Log.w(TAG, "Recovery", failure); }
    });
  }

  private static void recoverSession(RecordingStore store, File session, Consumer<Uri> saved) {
    if (!StoragePolicy.isSession(session.getName())) return;
    try {
      List<File> chunks = StoragePolicy.chunks(session);
      if (!chunks.isEmpty()) saved.accept(join(store, chunks, StoragePolicy.chunkWall(chunks.get(0).getName()), false));
      removeLeftovers(session);
    } catch (IOException failure) { Log.w(TAG, "Recovery of " + session.getName(), failure); }
  }

  private static void removeLeftovers(File session) {
    File[] files = session.listFiles();
    if (files != null) for (File file : files) if (file.getName().endsWith(".part")) file.delete();
    session.delete();
  }

  private static void migrateLegacy(RecordingStore store, Consumer<Uri> saved) throws IOException {
    File folder = store.legacyFolder();
    File[] files = folder == null ? null : folder.listFiles();
    if (files == null) return;
    Map<String, List<File>> sessions = new HashMap<>();
    for (File file : files) {
      String name = file.getName();
      if (StoragePolicy.isLegacy(name)) sessions.computeIfAbsent(StoragePolicy.legacySession(name), key -> new ArrayList<>()).add(file);
      else if (name.endsWith(".mp4.part")) file.delete(); // MediaMuxer writes no index before stop: it never plays.
    }
    for (List<File> clips : sessions.values()) {
      clips.sort((a, b) -> Long.compare(StoragePolicy.legacyWall(a.getName()), StoragePolicy.legacyWall(b.getName())));
      saved.accept(join(store, clips, StoragePolicy.legacyWall(clips.get(0).getName()), false));
    }
    folder.delete();
  }
}
