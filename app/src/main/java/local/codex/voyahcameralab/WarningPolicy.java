package local.codex.voyahcameralab;

import java.util.ArrayDeque;

/**
 * When the voice warning may play. Pure logic, fed with every moment the detector sees somebody
 * next to the car. A passer-by stays a few seconds and hears nothing; only somebody who keeps
 * walking around does. A shadow that never leaves must not shout all night, so plays are limited.
 */
final class WarningPolicy {
  static final long PRESENCE_MS = 8000, GAP_MS = 5000, COOLDOWN_MS = 60000, WINDOW_MS = 3600000;
  static final int MAX_PER_WINDOW = 6;

  private final ArrayDeque<Long> played = new ArrayDeque<>();
  private long firstSeen, lastSeen;
  private boolean present;

  boolean onMotion(long now) {
    if (!present || now - lastSeen > GAP_MS) { present = true; firstSeen = now; }
    lastSeen = now;
    if (now - firstSeen < PRESENCE_MS) return false;
    while (!played.isEmpty() && now - played.peekFirst() >= WINDOW_MS) played.removeFirst();
    if (played.size() >= MAX_PER_WINDOW) return false;
    if (!played.isEmpty() && now - played.peekLast() < COOLDOWN_MS) return false;
    played.addLast(now);
    return true;
  }
}
