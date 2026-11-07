package local.codex.voyahcameralab;

/** App side of GuardBridge. */
final class GuardControl {
  private GuardControl() { }

  /** @return null when the state cannot be read, which callers treat as an unlocked car */
  static ParkedPolicy.Reading read() {
    String[] fields = AvmViewControl.bridge(GuardBridge.class.getName(), GuardBridge.STATUS).split("\t");
    if (fields.length != 5 || !GuardBridge.DONE.equals(fields[0])) return null;
    try {
      int hold = Integer.parseInt(fields[1].trim()), air = Integer.parseInt(fields[2].trim());
      int camp = Integer.parseInt(fields[3].trim()), care = Integer.parseInt(fields[4].trim());
      // Seen on the car: the switch is 1 from locking outside until the unlock. Without Power Hold
      // the head unit sleeps after locking, so the options have nothing to do then anyway.
      return new ParkedPolicy.Reading(hold == 1, air == 1, camp == 1 || care == 1);
    } catch (NumberFormatException garbled) { return null; }
  }

  static boolean climate(boolean on) {
    return AvmViewControl.bridge(GuardBridge.class.getName(), on ? GuardBridge.AIR_ON : GuardBridge.AIR_OFF).startsWith(GuardBridge.DONE);
  }
}
