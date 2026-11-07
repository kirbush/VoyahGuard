package local.codex.voyahcameralab;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
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
 * video started before the activity went away still finishes. A source goes only after the video
 * holding its frames is published: a run killed or failed halfway leaves its chunks behind, and
 * the next start joins them.
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
      catch (RuntimeException failure) { AppLog.w(TAG, "Video job", failure); }
      finally { PENDING.decrementAndGet(); }
    });
  }

  /** Runs after every video queued so far: a hold must outlast the file it protects. */
  static void afterPending(Runnable work) { JOBS.execute(work); }

  /** One video, or several in a row when a single copy does not fit on the volume. */
  static void join(RecordingStore store, List<File> parts, VideoTimeline.Kind kind, Consumer<Uri> saved) throws IOException {
    int from = 0;
    while (from < parts.size()) {
      long room = store.roomForCopy(), bytes = 0;
      int to = from;
      while (to < parts.size() && (to == from || bytes + parts.get(to).length() <= room)) bytes += parts.get(to++).length();
      saved.accept(publish(store, new ArrayList<>(parts.subList(from, to)), kind));
      from = to;
    }
  }

  private static Uri publish(RecordingStore store, List<File> parts, VideoTimeline.Kind kind) throws IOException {
    File first = parts.get(0);
    long wallMs = StoragePolicy.wallOf(first.getName(), first.lastModified());
    Uri uri = store.createPending(StoragePolicy.videoName(wallMs, kind), wallMs);
    List<File> joined;
    try {
      try (ParcelFileDescriptor file = store.openPending(uri)) { joined = join(parts, file.getFileDescriptor()); }
      store.publishPending(uri);
    } catch (IOException | RuntimeException failure) {
      store.discardPending(uri);
      throw failure instanceof IOException ? (IOException) failure : new IOException("Не удалось собрать видео", failure);
    }
    for (File part : joined) part.delete();
    return uri;
  }

  /** @return the parts whose frames are in the video: a skipped one stays for the next start */
  private static List<File> join(List<File> parts, FileDescriptor target) throws IOException {
    MediaMuxer muxer = new MediaMuxer(target, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BYTES);
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    int track = -1;
    long offset = 0;
    List<File> joined = new ArrayList<>();
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
            if (last >= 0 && time > last) frame = time - last;
            last = Math.max(last, time);
          }
        } catch (IOException damaged) {
          AppLog.w(TAG, "Chunk skipped: " + part.getName(), damaged); // One bad chunk must not cost the video.
        } finally {
          source.release();
        }
        if (last >= 0) { offset += last + frame; joined.add(part); }
      }
      if (joined.isEmpty()) throw new IOException("Запись не содержит кадров");
      muxer.stop();
      return joined;
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
      } catch (IOException failure) { AppLog.w(TAG, "Recovery", failure); }
    });
  }

  private static void recoverSession(RecordingStore store, File session, Consumer<Uri> saved) {
    if (!StoragePolicy.isSession(session.getName())) return;
    try {
      List<File> chunks = StoragePolicy.chunks(session);
      if (!chunks.isEmpty()) join(store, chunks, VideoTimeline.Kind.RECORD, saved);
      removeLeftovers(session);
    } catch (IOException failure) { AppLog.w(TAG, "Recovery of " + session.getName(), failure); }
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
    Comparator<File> byTime = Comparator.comparingLong(clip -> StoragePolicy.legacyWall(clip.getName()));
    List<List<File>> ordered = new ArrayList<>(sessions.values());
    for (List<File> clips : ordered) clips.sort(byTime);
    ordered.sort((a, b) -> byTime.compare(a.get(0), b.get(0)));
    for (List<File> clips : ordered) join(store, clips, VideoTimeline.Kind.RECORD, saved);
    folder.delete();
  }
}
