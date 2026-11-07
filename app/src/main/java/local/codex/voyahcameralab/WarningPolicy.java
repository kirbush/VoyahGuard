package local.codex.voyahcameralab;

import java.util.ArrayDeque;

/**
 * When the voice warning plays. Pure logic, fed with every moment the detector sees somebody
 * next to the car. A passer-by is gone before the delay and hears nothing. Somebody who stays
 * is warned, and warned again at the chosen interval for as long as they stay. They count as
 * gone once the area around the car has been quiet for a while. A shadow that never leaves
 * must not shout all night, so plays per hour are limited. The owner picks the numbers.
 */
final class WarningPolicy {
  static final long GAP_MS = 8000, WINDOW_MS = 3600000;
  static final int[] DELAY_SECONDS = {10, 15, 30, 60};
  /** 0 means once per stay. */
  static final int[] REPEAT_SECONDS = {10, 20, 30, 60, 0};
  static final int[] PER_HOUR = {10, 30, 60};
  static final int[] PHRASES = {1, 2, 3};
  static final int DEFAULT_DELAY = 15, DEFAULT_REPEAT = 10, DEFAULT_PER_HOUR = 60, DEFAULT_PHRASES = 2;

  private final long delayMs, repeatMs;
  private final int perHour;
  private final ArrayDeque<Long> played = new ArrayDeque<>();
  private long firstSeen, lastSeen, lastPlayed;
  private boolean present, warned;

  WarningPolicy(int delaySeconds, int repeatSeconds, int perHour) {
    delayMs = pick(DELAY_SECONDS, delaySeconds, DEFAULT_DELAY) * 1000L;
    repeatMs = pick(REPEAT_SECONDS, repeatSeconds, DEFAULT_REPEAT) * 1000L;
    this.perHour = pick(PER_HOUR, perHour, DEFAULT_PER_HOUR);
  }

  /** A stored value that is not on offer falls back to the default. */
  static int pick(int[] allowed, int value, int fallback) {
    for (int candidate : allowed) if (candidate == value) return value;
    return fallback;
  }

  boolean onMotion(long now) {
    if (!present || now - lastSeen > GAP_MS) { present = true; warned = false; firstSeen = now; }
    lastSeen = now;
    if (now - firstSeen < delayMs) return false;
    if (warned && (repeatMs == 0 || now - lastPlayed < repeatMs)) return false;
    while (!played.isEmpty() && now - played.peekFirst() >= WINDOW_MS) played.removeFirst();
    if (played.size() >= perHour) return false;
    played.addLast(now);
    warned = true;
    lastPlayed = now;
    return true;
  }
}
