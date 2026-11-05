package local.codex.voyahcameralab;

public final class WarningPolicyTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    WarningPolicy policy = new WarningPolicy();
    for (long t = 0; t < 7000; t += 1000) check(!policy.onMotion(t), "A passer-by hears nothing");
    check(!policy.onMotion(20000), "A gap starts the count again");
    check(!policy.onMotion(23000), "The new presence has to last too");
    check(policy.onMotion(28000), "Somebody who stays is warned");
    check(!policy.onMotion(29000) && !policy.onMotion(31000), "One warning at a time");
    long t = 28000;
    for (; t < 80000; t += 3000) check(!policy.onMotion(t + 3000) || t + 3000 >= 88000, "A cooldown separates warnings");
    int plays = 1;
    for (t = 88000; t < 3000000; t += 3000) if (policy.onMotion(t)) plays++;
    check(plays == WarningPolicy.MAX_PER_WINDOW, "A shadow that never leaves is limited: " + plays);
    boolean again = false;
    for (t = 3700000; t < 3760000; t += 3000) again |= policy.onMotion(t);
    check(again, "The limit is per hour, not for ever");
    System.out.println("PASS: warning needs a stay, a cooldown and an hourly limit");
  }
}
