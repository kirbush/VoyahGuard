package local.codex.voyahcameralab;

/**
 * Decides which AVM view to request. Pure logic, driven by one call per analysed sample.
 *
 * Every run starts from the same picture: 2D panorama and the front camera, whatever the 360
 * screen kept from last time. The camera stays on this home view, or on one the owner picks in
 * the 360 screen later. Only motion around the car in the stitched top view moves it to that
 * side, and it returns home once nothing is near. Motion that the top view does not show, such
 * as somebody passing at a distance, never switches anything.
 *
 * A sent command proves nothing: only the glyph in the image confirms a view, and a failed
 * confirmation stops automatic control instead of retrying forever.
 */
final class AdaptiveViewPolicy {
  static final long WARMUP_MS = 3000, SETTLE_MS = 800, HOLD_TAIL_MS = 10000, MIN_DWELL_MS = 5000, MIN_SWITCH_MS = 1500;
  static final long CONFIRM_TIMEOUT_MS = 2500, UNKNOWN_LIMIT_MS = 6000, MODE_FIX_MS = 5000;
  static final int CONFIRM_SAMPLES = 2, RETRIES = 1;
  private static final int NONE = AvmLayout.VIEW_UNKNOWN;

  enum Stop {NONE, NOT_CONFIRMED, VIEW_LOST, COMMAND_FAILED}
  enum Reason {NONE, FOLLOW, RETURN, RETRY, TWO_D}

  private final long[] lastShown = new long[AvmLayout.VIEWS];
  private final int[] streak = new int[AvmLayout.VIEWS];
  private int home, current = NONE, target = NONE, retries;
  private final long warmupEnd;
  private long settledAt, confirmedAt, requestedAt, lastSwitch, lastMotion, unknownSince;
  private boolean motionSeen, modeFixed;
  private Stop stop = Stop.NONE;
  private Reason lastReason = Reason.NONE;

  AdaptiveViewPolicy(long now, int home) { this.home = home; unknownSince = now; warmupEnd = now + WARMUP_MS; }

  int home() { return home; }
  int current() { return current; }
  Stop stopped() { return stop; }
  Reason lastReason() { return lastReason; }
  /** Somebody is near the side shown now, or was within the hold tail. */
  boolean active(long now) { return motionSeen && now - lastMotion < HOLD_TAIL_MS; }
  boolean settled(long now) { return current != NONE && target == NONE && now >= settledAt; }

  /** The control thread reports that the view command could not be sent. */
  void commandFailed() { stop = Stop.COMMAND_FAILED; target = NONE; }

  /**
   * @param near per view, whether the top view shows motion on that side of the car in this sample
   * @return view to request now, or VIEW_UNKNOWN for no command
   */
  int onSample(long now, int marker, boolean[] near) {
    if (stop != Stop.NONE) return NONE;
    if (current == NONE && marker == NONE && !modeFixed && now - unknownSince >= MODE_FIX_MS) {
      modeFixed = true; unknownSince = now; lastReason = Reason.TWO_D; // No glyph from the start: left in 3D.
      return AvmLayout.TOGGLE_2D;
    }
    if (!track(now, marker)) return target != NONE && stop == Stop.NONE && now - requestedAt >= CONFIRM_TIMEOUT_MS ? timeout(now) : NONE;
    if (!settled(now)) return NONE;
    if (now < warmupEnd) return current != home ? request(now, home, Reason.RETURN) : NONE; // Frames only teach the background.
    lastShown[current] = now;
    for (int view = 0; view < streak.length; view++) streak[view] = near[view] ? streak[view] + 1 : 0;
    if (streak[current] >= CONFIRM_SAMPLES) markMotion(now);
    if (now - lastSwitch < MIN_SWITCH_MS) return NONE;

    int side = otherSide();
    if (side != NONE) {
      if (active(now) && now - confirmedAt < MIN_DWELL_MS) return NONE;
      markMotion(now);
      return request(now, side, Reason.FOLLOW);
    }
    return current != home && !active(now) ? request(now, home, Reason.RETURN) : NONE;
  }

  private void markMotion(long now) { motionSeen = true; lastMotion = now; }

  private boolean track(long now, int marker) {
    if (marker == NONE) {
      if (now - unknownSince >= UNKNOWN_LIMIT_MS) { stop = Stop.VIEW_LOST; target = NONE; }
      return false;
    }
    unknownSince = now;
    if (target != NONE) {
      if (marker != target) return false;
      confirm(now, marker);
      return true;
    }
    if (marker != current) {
      if (current != NONE) home = marker; // picked by hand
      confirm(now, marker);
    }
    return true;
  }

  private void confirm(long now, int view) {
    current = view; target = NONE; retries = 0; confirmedAt = now; settledAt = now + SETTLE_MS;
    java.util.Arrays.fill(streak, 0);
  }

  private int timeout(long now) {
    if (retries >= RETRIES) { stop = Stop.NOT_CONFIRMED; target = NONE; return NONE; }
    retries++; requestedAt = now; lastReason = Reason.RETRY;
    return target;
  }

  /** A side with confirmed motion, the one shown longest ago when there are several. */
  private int otherSide() {
    int best = NONE;
    for (int view = 0; view < streak.length; view++) {
      if (view == current || streak[view] < CONFIRM_SAMPLES) continue;
      if (best == NONE || lastShown[view] < lastShown[best]) best = view;
    }
    return best;
  }

  private int request(long now, int view, Reason reason) {
    target = view; requestedAt = now; lastSwitch = now; retries = 0; lastReason = reason;
    return view;
  }
}
