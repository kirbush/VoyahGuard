package local.codex.voyahcameralab;

import java.util.Random;

public final class CameraTrackerTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    CameraTracker walker = new CameraTracker();
    boolean fired = false; int at = -1;
    for (int i = 0; i < 6 && !fired; i++) { fired = walker.update(i * 220L, new double[]{40 + 3 * i, 120}); at = i; }
    check(fired && at <= 4, "Somebody walking across is caught within a second");

    CameraTracker window = new CameraTracker();
    boolean flicker = false;
    for (int i = 0; i < 200; i++) flicker |= window.update(i * 220L, new double[]{100 + (i % 2), 90 - (i % 3 == 0 ? 1 : 0)});
    check(!flicker, "A flickering window stays in place and never counts");

    CameraTracker cloud = new CameraTracker();
    boolean crawl = false;
    for (int i = 0; i < 200; i++) crawl |= cloud.update(i * 220L, new double[]{60 + i * 0.02, 80});
    check(!crawl, "A cloud crawling a pixel in ten seconds never counts");

    CameraTracker rain = new CameraTracker();
    Random drops = new Random(7);
    int wet = 0;
    for (int i = 0; i < 500; i++) {
      double[] centres = new double[2 * 2];
      for (int k = 0; k < centres.length; k += 2) { centres[k] = drops.nextDouble() * 194; centres[k + 1] = 66 + drops.nextDouble() * 134; }
      if (rain.update(i * 220L, centres)) wet++;
    }
    check(wet <= 25, "A few scattered drops rarely line up into a moving track: " + wet + " of 500");

    CameraTracker jitter = new CameraTracker();
    boolean back = false;
    for (int i = 0; i < 100; i++) back |= jitter.update(i * 220L, new double[]{100 + (i % 2 == 0 ? 8 : -8), 120});
    check(!back, "Jumping back and forth is not travelling");

    CameraTracker lost = new CameraTracker();
    lost.update(0, new double[]{40, 120}); lost.update(220, new double[]{44, 120});
    check(!lost.update(2000, new double[]{48, 120}), "A track lost for over a second starts again");
    System.out.println("PASS: camera panel motion counts travellers, not flicker, clouds or drops");
  }
}
