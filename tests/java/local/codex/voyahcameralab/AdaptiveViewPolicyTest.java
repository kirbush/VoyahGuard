package local.codex.voyahcameralab;

import local.codex.voyahcameralab.AdaptiveViewPolicy.Reason;
import local.codex.voyahcameralab.AdaptiveViewPolicy.Stop;

/** View selection against the behaviour the owner asked for; no hardware involved. */
public final class AdaptiveViewPolicyTest {
  private static final int NO = AvmLayout.VIEW_UNKNOWN, STEP = 200, F = AvmLayout.FRONT, L = AvmLayout.LEFT, B = AvmLayout.REAR, R = AvmLayout.RIGHT;
  private static final boolean[] QUIET = new boolean[AvmLayout.VIEWS];

  private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }

  /** Simulated car: applies requests after a latency and reports the glyph. */
  private static final class Car {
    final AdaptiveViewPolicy policy; long now = 1000; int marker, pending = NO; long applyAt; int requests; int lastRequest = NO;
    boolean deaf;
    Car(int start) { marker = start; policy = new AdaptiveViewPolicy(now, F, AvmLayout.VIEWS, false); }
    int step(boolean[] near) {
      now += STEP;
      if (pending != NO && now >= applyAt) { marker = pending; pending = NO; }
      int request = policy.onSample(now, marker, marker != NO, near);
      if (request != NO) { requests++; lastRequest = request; if (!deaf) { pending = request == AvmLayout.TOGGLE_2D ? F : request; applyAt = now + 500; } }
      return request;
    }
    void run(long ms, boolean[] near) { for (long end = now + ms; now < end; ) step(near); }
  }

  private static boolean[] near(int... views) { boolean[] zones = new boolean[AvmLayout.VIEWS]; for (int view : views) zones[view] = true; return zones; }

  public static void main(String[] args) {
    leftoverThreeDModeIsFixedOnce();
    glyphStillMissingAfterTheFixStops();
    startsOnTheFrontViewWhateverWasLeft();
    startupIgnoresBackgroundLearning();
    quietSceneNeverSwitches();
    motionNearTheShownSideDoesNotSwitch();
    followsNearMotionAndReturnsHome();
    staysWhileSomebodyRemainsNear();
    singleSampleDoesNotSwitch();
    transitionFramesAreIgnored();
    twoSidesShareTheCamera();
    viewPickedByHandBecomesHome();
    unconfirmedViewStopsAfterOneRetry();
    lostGlyphStops();
    failedCommandStops();
    threeDStartsLookingThroughTheCarAtTheFront();
    threeDLeavesTwoDScreenFirst();
    threeDHiddenButtonsAreNotALostView();
    threeDUnconfirmedButtonStops();
    sectorsAndOppositeButtons();
    zonesMapToDirections();
    markerDetectionRequiresExactlyOne();
    System.out.println("PASS: adaptive view policy");
  }

  private static void leftoverThreeDModeIsFixedOnce() {
    Car car = new Car(NO);
    car.run(AdaptiveViewPolicy.MODE_FIX_MS - 400, near(B));
    check(car.requests == 0, "no command while the screen may still be opening");
    car.run(800, near(B));
    check(car.requests == 1 && car.lastRequest == AvmLayout.TOGGLE_2D, "3D must be switched to 2D");
    car.run(3000, QUIET);
    check(car.marker == F && car.requests == 1, "front view expected after the fix");
  }

  private static void glyphStillMissingAfterTheFixStops() {
    Car car = new Car(NO);
    car.deaf = true;
    car.run(20000, QUIET);
    check(car.requests == 1 && car.policy.stopped() == Stop.VIEW_LOST, "one toggle at most, then stop");
  }

  private static void startsOnTheFrontViewWhateverWasLeft() {
    Car car = new Car(B);
    car.run(2000, QUIET);
    check(car.lastRequest == F && car.requests == 1 && car.marker == F, "every run starts on the front view");
    car.run(60000, QUIET);
    check(car.requests == 1, "the start must stay a single command");
  }

  private static void startupIgnoresBackgroundLearning() {
    Car car = new Car(F);
    car.run(AdaptiveViewPolicy.WARMUP_MS - 400, near(L));
    check(car.requests == 0, "the first frames only teach the background model");
  }

  private static void quietSceneNeverSwitches() {
    Car car = new Car(F);
    car.run(600000, QUIET);
    check(car.requests == 0 && car.marker == F, "the camera must stay on the home view, got " + car.requests + " commands");
  }

  private static void motionNearTheShownSideDoesNotSwitch() {
    Car car = new Car(F);
    car.run(1000, QUIET);
    car.run(30000, near(F));
    check(car.requests == 0, "motion on the shown side needs no command");
  }

  private static void followsNearMotionAndReturnsHome() {
    Car car = new Car(F);
    car.run(3400, QUIET);
    car.run(1000, near(B));
    check(car.lastRequest == B && car.policy.lastReason() == Reason.FOLLOW, "did not follow rear motion");
    car.run(1000, near(B));
    check(car.marker == B, "rear not reached");
    car.run(AdaptiveViewPolicy.HOLD_TAIL_MS - 1000, QUIET);
    check(car.marker == B, "left the side before the tail ended");
    car.run(2500, QUIET);
    check(car.marker == F && car.policy.lastReason() == Reason.RETURN, "did not return home");
    int requests = car.requests;
    car.run(60000, QUIET);
    check(car.requests == requests, "kept switching after returning home");
  }

  private static void staysWhileSomebodyRemainsNear() {
    Car car = new Car(F);
    car.run(3400, QUIET);
    car.run(120000, near(L));
    check(car.marker == L && car.requests == 1, "moved away from a continuing event");
  }

  private static void singleSampleDoesNotSwitch() {
    Car car = new Car(F);
    car.run(3400, QUIET);
    car.step(near(R));
    car.run(600, QUIET);
    check(car.requests == 0, "one noisy sample switched the view");
  }

  private static void transitionFramesAreIgnored() {
    Car car = new Car(F);
    car.run(3400, QUIET);
    car.run(600, near(L));
    check(car.lastRequest == L, "precondition");
    check(!car.policy.settled(car.now), "unsettled view reported settled");
    int requests = car.requests;
    car.run(400, near(R));
    check(car.requests == requests, "motion during a transition caused a switch");
  }

  private static void twoSidesShareTheCamera() {
    Car car = new Car(F);
    car.run(3400, QUIET);
    boolean left = false, right = false;
    for (long end = car.now + 30000; car.now < end; ) { car.step(near(L, R)); left |= car.marker == L; right |= car.marker == R; }
    check(left && right, "one of two sides was never shown");
    check(car.requests <= 8, "two sides switched too often: " + car.requests);
  }

  private static void viewPickedByHandBecomesHome() {
    Car car = new Car(F);
    car.run(1000, QUIET);
    car.marker = B;
    car.run(10000, QUIET);
    check(car.policy.home() == B && car.requests == 0, "the view picked by hand must stay");
    car.run(1000, near(L));
    car.run(14000, QUIET);
    check(car.marker == B, "must return to the view picked by hand");
  }

  private static void unconfirmedViewStopsAfterOneRetry() {
    Car car = new Car(F);
    car.deaf = true;
    car.run(3400, QUIET);
    car.run(15000, near(R));
    check(car.policy.stopped() == Stop.NOT_CONFIRMED, "kept commanding without confirmation");
    check(car.requests == 2, "expected one retry, got " + car.requests);
    int requests = car.requests;
    car.run(30000, near(B));
    check(car.requests == requests, "commands after stop");
  }

  private static void lostGlyphStops() {
    Car car = new Car(F);
    car.run(1000, QUIET);
    car.marker = NO;
    car.run(AdaptiveViewPolicy.UNKNOWN_LIMIT_MS + 1000, QUIET);
    check(car.policy.stopped() == Stop.VIEW_LOST, "unknown view must stop automation");
  }

  private static void failedCommandStops() {
    Car car = new Car(F);
    car.run(3400, QUIET);
    car.run(1000, near(L));
    car.policy.commandFailed();
    int requests = car.requests;
    car.run(10000, near(R));
    check(car.requests == requests && car.policy.stopped() == Stop.COMMAND_FAILED, "failed command must stop automation");
  }

  /** 3D car: the lit button shows only for a few seconds after a touch. */
  private static final class Car3D {
    final AdaptiveViewPolicy policy; long now = 1000, litUntil, applyAt; int shown = 2, pending = NO, requests, lastRequest = NO; boolean twoD, deaf;
    Car3D(boolean twoD) { this.twoD = twoD; policy = new AdaptiveViewPolicy(now, AvmLayout.HOME_3D, AvmLayout.BUTTONS, true); }
    void step(boolean[] near) {
      now += STEP;
      if (pending != NO && now >= applyAt) { shown = pending; pending = NO; litUntil = now + 4000; }
      int request = policy.onSample(now, now < litUntil ? shown : NO, twoD, near);
      if (request == NO) return;
      requests++; lastRequest = request;
      if (request == AvmLayout.TOGGLE_2D) twoD = false;
      else if (!deaf) { pending = request; applyAt = now + 900; }
    }
    void run(long ms, boolean[] near) { for (long end = now + ms; now < end; ) step(near); }
  }

  private static void threeDStartsLookingThroughTheCarAtTheFront() {
    Car3D car = new Car3D(false);
    car.run(5500, new boolean[8]);
    check(car.shown == AvmLayout.HOME_3D && car.requests == 1, "3D must start on the rear button, which watches the front");
  }

  private static void threeDLeavesTwoDScreenFirst() {
    Car3D car = new Car3D(true);
    car.run(4000, new boolean[8]);
    check(car.lastRequest == AvmLayout.TOGGLE_2D, "2D screen must be switched to 3D first");
    car.run(4000, new boolean[8]);
    check(car.shown == AvmLayout.HOME_3D && car.requests == 2, "then the home button");
  }

  private static void threeDHiddenButtonsAreNotALostView() {
    Car3D car = new Car3D(false);
    car.run(60000, new boolean[8]);
    check(car.policy.stopped() == Stop.NONE && car.requests == 1, "hidden buttons must not stop 3D");
    boolean[] near = new boolean[8]; near[6] = true;
    car.run(2000, near);
    check(car.lastRequest == 6, "motion must move the camera to the opposite button");
    car.run(1000, near);
    car.run(AdaptiveViewPolicy.HOLD_TAIL_MS + 3000, new boolean[8]);
    check(car.shown == AvmLayout.HOME_3D, "3D must return home");
  }

  private static void threeDUnconfirmedButtonStops() {
    Car3D car = new Car3D(false);
    car.deaf = true;
    car.run(15000, new boolean[8]);
    check(car.policy.stopped() == Stop.NOT_CONFIRMED && car.requests == 2, "3D needs its button lit after a touch");
  }

  private static void sectorsAndOppositeButtons() {
    check(AvmLayout.sector(440, 60) == 0, "ahead");
    check(AvmLayout.sector(680, 90) == 1, "ahead right");
    check(AvmLayout.sector(700, 600) == 2, "right");
    check(AvmLayout.sector(680, 1110) == 3, "behind right");
    check(AvmLayout.sector(440, 1150) == 4, "behind");
    check(AvmLayout.sector(200, 1110) == 5, "behind left");
    check(AvmLayout.sector(180, 600) == 6, "left");
    check(AvmLayout.sector(200, 90) == 7, "ahead left");
    check(AvmLayout.sector(440, 600) == NO, "the car has no direction");
    check(AvmLayout.throughCar(0) == AvmLayout.HOME_3D && AvmLayout.throughCar(6) == 2, "the camera stands on the opposite side");
  }

  private static void zonesMapToDirections() {
    check(AvmLayout.zone(440, 100) == AvmLayout.FRONT, "front zone");
    check(AvmLayout.zone(440, 1100) == AvmLayout.REAR, "rear zone");
    check(AvmLayout.zone(200, 600) == AvmLayout.LEFT, "left zone");
    check(AvmLayout.zone(680, 600) == AvmLayout.RIGHT, "right zone");
    check(AvmLayout.zone(440, 600) == NO, "car body has no direction");
  }

  private static void paintArc(byte[] rgba, int x0, int y0, int x1, int y1) {
    for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
      int p = ((AvmLayout.SAMPLE_HEIGHT + y) * AvmLayout.SAMPLE_WIDTH + x) * 4;
      rgba[p] = 25; rgba[p + 1] = (byte) 150; rgba[p + 2] = 100; rgba[p + 3] = (byte) 255;
    }
  }

  private static void markerDetectionRequiresExactlyOne() {
    byte[] rgba = new byte[AvmLayout.BUFFER_BYTES];
    check(AvmLayout.selectedView(rgba) == NO, "dark frame has no glyph");
    check(AvmLayout.selectedView(new byte[16]) == NO, "short buffer");
    paintArc(rgba, 104, 30, 112, 74);
    check(AvmLayout.selectedView(rgba) == AvmLayout.RIGHT, "right arc");
    paintArc(rgba, 62, 20, 100, 30);
    check(AvmLayout.selectedView(rgba) == NO, "two arcs are ambiguous");
    byte[] front = new byte[AvmLayout.BUFFER_BYTES];
    paintArc(front, 62, 22, 100, 32);
    check(AvmLayout.selectedView(front) == AvmLayout.FRONT, "front arc");
    byte[] rear = new byte[AvmLayout.BUFFER_BYTES];
    paintArc(rear, 62, 64, 100, 74);
    check(AvmLayout.selectedView(rear) == AvmLayout.REAR, "rear arc");
    byte[] left = new byte[AvmLayout.BUFFER_BYTES];
    paintArc(left, 52, 30, 60, 74);
    check(AvmLayout.selectedView(left) == AvmLayout.LEFT, "left arc");
  }
}
