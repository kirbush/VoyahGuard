package local.codex.voyahcameralab;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ParkedPolicyTest {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static ParkedPolicy.Reading reading(boolean armed, boolean air, boolean care) { return new ParkedPolicy.Reading(armed, air, care); }
  static final List<ParkedPolicy.Action> NOTHING = Collections.emptyList();

  public static void main(String[] args) {
    ParkedPolicy policy = new ParkedPolicy();
    check(policy.update(reading(false, true, false), true, true).equals(NOTHING), "An unlocked car is left alone");
    check(policy.update(reading(true, true, false), false, false).equals(NOTHING), "Options are off by default");

    check(policy.update(reading(true, true, false), true, true).equals(Arrays.asList(ParkedPolicy.Action.DARKEN, ParkedPolicy.Action.CLIMATE_OFF)), "Locking darkens and stops the climate");
    check(policy.update(reading(true, false, false), true, true).equals(Arrays.asList(ParkedPolicy.Action.KEEP_DARK)), "Each reading keeps the screens dark, climate is sent once");
    check(policy.update(reading(false, false, false), true, true).equals(Arrays.asList(ParkedPolicy.Action.RESTORE_SCREENS, ParkedPolicy.Action.CLIMATE_ON)), "Unlocking undoes both");
    check(policy.update(reading(false, false, false), true, true).equals(NOTHING), "And only once");

    policy.update(reading(true, true, false), true, true);
    check(policy.update(null, true, true).equals(Arrays.asList(ParkedPolicy.Action.RESTORE_SCREENS, ParkedPolicy.Action.CLIMATE_ON)), "An unreadable status counts as unlocked");

    ParkedPolicy off = new ParkedPolicy();
    check(off.update(reading(true, false, false), false, true).equals(NOTHING), "Climate that was off stays out of it");
    check(off.update(reading(false, false, false), false, true).equals(NOTHING), "So it is not switched on later");

    ParkedPolicy care = new ParkedPolicy();
    check(care.update(reading(true, true, true), false, true).equals(NOTHING), "Camp and care modes keep the climate");
    care.update(reading(true, true, false), false, true);
    check(care.update(reading(true, false, true), false, true).equals(Arrays.asList(ParkedPolicy.Action.CLIMATE_ON)), "Care mode turned on later gets it back");

    ParkedPolicy stopped = new ParkedPolicy();
    stopped.update(reading(true, true, false), true, true);
    check(stopped.stop().equals(Arrays.asList(ParkedPolicy.Action.RESTORE_SCREENS, ParkedPolicy.Action.CLIMATE_ON)), "Stop undoes everything");
    System.out.println("PASS: parked screens and climate are undone on unlock, stop and unknown status");
  }
}
