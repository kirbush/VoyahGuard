package local.codex.voyahcameralab;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which hidden chunks of a recording become one whole video. Pure logic.
 *
 * MediaMuxer cannot extend or cut a finished MP4, so a recording is kept as short chunks that
 * nobody sees and a video is joined from them once its end is known. That gives both things a
 * single file cannot: a video is never split in the middle of an event, and the oldest chunks of
 * a running video can be dropped when the storage limit is reached.
 *
 * Only footage the detector really watched may be discarded: if it was not running, nobody knows
 * whether anything happened there, so that footage is kept whatever the option says.
 */
final class VideoTimeline {
  static final long PRE_ROLL_MS = 30000, POST_ROLL_MS = 30000;

  enum Kind {EVENT, RECORD}

  static final class Chunk {
    final File file; final long startMs, endMs, wallMs, bytes; final boolean watched;
    Chunk(File file, long startMs, long endMs, long wallMs, long bytes, boolean watched) {
      this.file = file; this.startMs = startMs; this.endMs = endMs; this.wallMs = wallMs; this.bytes = bytes; this.watched = watched;
    }
  }

  static final class Video {
    final Kind kind; final List<Chunk> chunks; final boolean trimmed;
    Video(Kind kind, List<Chunk> chunks, boolean trimmed) { this.kind = kind; this.chunks = chunks; this.trimmed = trimmed; }
    long wallMs() { return chunks.get(0).wallMs; }
  }

  /** What one call decided: videos to publish and chunks nobody will need. */
  static final class Step {
    final List<Video> videos = new ArrayList<>();
    final List<Chunk> dropped = new ArrayList<>();
  }

  private final boolean keepQuiet;
  private final List<Chunk> open = new ArrayList<>();
  private boolean inEvent, trimmed;
  private long lastMotion;

  /** @param keepQuiet false when the owner chose not to keep footage without events */
  VideoTimeline(boolean keepQuiet) { this.keepQuiet = keepQuiet; }

  boolean inEvent() { return inEvent; }
  List<Chunk> held() { return new ArrayList<>(open); }

  Step motion(long now) {
    Step step = new Step();
    if (!inEvent) {
      inEvent = true;
      int first = 0;
      while (first < open.size() && open.get(first).endMs <= now - PRE_ROLL_MS) first++;
      List<Chunk> before = new ArrayList<>(open.subList(0, first));
      open.subList(0, first).clear();
      settleQuiet(before, step);
    }
    lastMotion = now;
    return step;
  }

  Step chunk(Chunk chunk) {
    Step step = new Step();
    open.add(chunk);
    if (inEvent) {
      if (chunk.endMs - lastMotion >= POST_ROLL_MS) { inEvent = false; emit(Kind.EVENT, step); }
    } else if (!keepQuiet) {
      dropQuiet(chunk.endMs, step);
    }
    return step;
  }

  /** A stretch the detector did not see stays whole and leaves as soon as it has ended. */
  private void dropQuiet(long now, Step step) {
    while (!open.isEmpty()) {
      if (open.get(0).watched) {
        if (open.get(0).endMs > now - PRE_ROLL_MS) return;
        step.dropped.add(open.remove(0));
        continue;
      }
      int end = 1;
      while (end < open.size() && !open.get(end).watched) end++;
      if (end == open.size()) return;
      step.videos.add(new Video(Kind.RECORD, new ArrayList<>(open.subList(0, end)), trimmed));
      trimmed = false;
      open.subList(0, end).clear();
    }
  }

  /** The recording ended: whatever is held becomes its last video, or goes if nothing needs it. */
  Step finish() {
    Step step = new Step();
    if (inEvent) { inEvent = false; emit(Kind.EVENT, step); }
    else { List<Chunk> rest = new ArrayList<>(open); open.clear(); settleQuiet(rest, step); }
    return step;
  }

  /** Storage pressure: drops the start of the running video, never its only chunk. */
  boolean trim(Chunk oldest) {
    if (open.size() < 2 || open.get(0) != oldest) return false;
    open.remove(0);
    trimmed = true;
    return true;
  }

  private void settleQuiet(List<Chunk> chunks, Step step) {
    if (chunks.isEmpty()) return;
    boolean keep = keepQuiet;
    for (Chunk chunk : chunks) keep |= !chunk.watched;
    if (keep) { step.videos.add(new Video(Kind.RECORD, chunks, trimmed)); trimmed = false; }
    else step.dropped.addAll(chunks);
  }

  private void emit(Kind kind, Step step) {
    if (open.isEmpty()) return;
    step.videos.add(new Video(kind, new ArrayList<>(open), trimmed));
    trimmed = false;
    open.clear();
  }
}
