package local.codex.voyahcameralab;

import java.util.ArrayList;
import java.util.List;

/**
 * Motion in the camera panel: only things that travel count. Pure logic, fed with the centres of
 * the foreground blobs of each sample. Blobs are followed from sample to sample; a track counts
 * while it has lasted a few samples, got far enough from where it began and travelled mostly in
 * one direction. That is checked at every sample, not latched: flickering windows and lights stay
 * in place, clouds crawl, and a chain of rain drops or snowflakes wanders back and forth, so its
 * straight distance falls behind its path; people, cars and animals keep going. Tuned on
 * recordings from the car; rain and snow still need real footage.
 */
final class CameraTracker {
  static final int MIN_SAMPLES = 4;
  /** In sample pixels (320x200 of the whole frame), a little under a second at 4-5 samples a second. */
  static final double MIN_SHIFT = 5, MATCH = 18, STRAIGHT = 0.5;
  static final long GAP_MS = 700;

  private static final class Track {
    final double startX, startY; double lastX, lastY, path; int samples; long lastMs;
    Track(double x, double y, long now) { startX = lastX = x; startY = lastY = y; samples = 1; lastMs = now; }
  }

  private final List<Track> tracks = new ArrayList<>();

  void reset() { tracks.clear(); }

  /** @param centres x0, y0, x1, y1, ... of the blobs worth following; true when something is moving */
  boolean update(long now, double[] centres) {
    int count = centres.length / 2;
    boolean[] used = new boolean[count];
    boolean moving = false;
    List<Track> alive = new ArrayList<>();
    for (Track track : tracks) {
      int best = -1;
      double bestDistance = MATCH;
      for (int i = 0; i < count; i++) {
        if (used[i]) continue;
        double d = Math.hypot(centres[2 * i] - track.lastX, centres[2 * i + 1] - track.lastY);
        if (d < bestDistance) { best = i; bestDistance = d; }
      }
      if (best < 0) { if (now - track.lastMs < GAP_MS) alive.add(track); continue; }
      used[best] = true;
      double x = centres[2 * best], y = centres[2 * best + 1];
      track.path += bestDistance;
      track.lastX = x; track.lastY = y; track.samples++; track.lastMs = now;
      double net = Math.hypot(x - track.startX, y - track.startY);
      moving |= track.samples >= MIN_SAMPLES && net >= MIN_SHIFT && net >= STRAIGHT * track.path;
      alive.add(track);
    }
    for (int i = 0; i < count; i++) if (!used[i]) alive.add(new Track(centres[2 * i], centres[2 * i + 1], now));
    tracks.clear();
    tracks.addAll(alive);
    return moving;
  }
}
