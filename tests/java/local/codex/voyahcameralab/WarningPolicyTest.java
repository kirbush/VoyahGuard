package local.codex.voyahcameralab;

public final class WarningPolicyTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static int plays(WarningPolicy policy, long from, long to) {
    int count = 0;
    for (long t = from; t < to; t += 1000) if (policy.onMotion(t)) count++;
    return count;
  }
  public static void main(String[] args) {
    WarningPolicy policy = new WarningPolicy(15, 10, 60);
    for (long t = 0; t < 14000; t += 1000) check(!policy.onMotion(t), "Somebody passing by within the delay hears nothing");
    check(!policy.onMotion(30000), "A quiet gap starts the count again");
    for (long t = 31000; t < 45000; t += 1000) check(!policy.onMotion(t), "The new stay has to reach the delay too");
    check(policy.onMotion(45000), "Somebody who stays is warned");
    check(plays(policy, 46000, 76000) == 3, "While they stay the phrase repeats at the interval");
    check(!policy.onMotion(85000), "Gone for longer than the gap: no warning on return");
    for (long t = 86000; t < 100000; t += 1000) check(!policy.onMotion(t), "A new stay waits for the delay again");
    check(policy.onMotion(100000), "And then warns again");

    WarningPolicy once = new WarningPolicy(10, 0, 60);
    check(plays(once, 0, 120000) == 1, "Without repeats one stay gives one warning");
    WarningPolicy slow = new WarningPolicy(30, 60, 60);
    check(plays(slow, 0, 29000) == 0 && plays(slow, 29000, 31000) == 1, "The delay is the owner's choice");
    check(plays(slow, 31000, 151000) == 2, "So is the interval");

    WarningPolicy shadow = new WarningPolicy(10, 10, 10);
    check(plays(shadow, 0, 3000000) == 10, "A shadow that never leaves is limited per hour");
    check(plays(shadow, 3700000, 3720000) > 0, "The limit is per hour, not for ever");

    WarningPolicy odd = new WarningPolicy(7, 13, 999);
    check(plays(odd, 0, 14000) == 0 && plays(odd, 14000, 16000) == 1, "Values that are not on offer fall back to the defaults");
    check(WarningPolicy.pick(WarningPolicy.PHRASES, 5, WarningPolicy.DEFAULT_PHRASES) == 2, "Phrase count falls back too");
    WarningPolicy kept = new WarningPolicy(15, 10, 60);
    check(kept.sameSettings(15, 10, 60) && kept.sameSettings(7, 13, 999) == new WarningPolicy(7, 13, 999).sameSettings(15, 10, 60), "Same numbers keep the policy, also after fallback");
    check(!kept.sameSettings(30, 10, 60) && !kept.sameSettings(15, 0, 60) && !kept.sameSettings(15, 10, 10), "New numbers rebuild it");
    // The 0.7.x bug: rebuilt every second, a stay never reached the delay.
    WarningPolicy live = new WarningPolicy(15, 10, 60);
    int warned = 0;
    for (long t = 0; t < 60000; t += 250) {
      if (!live.sameSettings(15, 10, 60)) live = new WarningPolicy(15, 10, 60);
      if (live.onMotion(t)) warned++;
    }
    check(warned >= 4, "A minute next to the car with the settings re-read every tick is warned: " + warned);
    double inf = Double.POSITIVE_INFINITY;
    int[] defaults = WarningPolicy.DEFAULT_REACH_CM;
    check(!WarningPolicy.alarming(new double[]{0.8, inf, inf, inf}, defaults) && WarningPolicy.alarming(new double[]{0.3, inf, inf, inf}, defaults), "In front only right at the body by default");
    check(WarningPolicy.alarming(new double[]{inf, 3, inf, inf}, defaults) && WarningPolicy.alarming(new double[]{inf, inf, 2, inf}, defaults), "At a side or behind at any distance by default");
    check(!WarningPolicy.alarming(new double[]{inf, inf, inf, inf}, defaults), "Nobody is nobody");
    int[] rightOff = {50, WarningPolicy.ANYWHERE, WarningPolicy.ANYWHERE, 0};
    check(!WarningPolicy.alarming(new double[]{inf, inf, inf, 0}, rightOff), "A side switched off never counts, even at the body");
    int[] tight = {25, 100, 100, 100};
    check(!WarningPolicy.alarming(new double[]{inf, 1.5, inf, inf}, tight) && WarningPolicy.alarming(new double[]{inf, 0.9, inf, inf}, tight), "Each side has its own distance");
    check(WarningPolicy.pick(WarningPolicy.REACH_CM, 40, 50) == 50, "An odd stored distance falls back to the default");
    System.out.println("PASS: warning delay, repeat interval and hourly limit follow the settings");
  }
}
