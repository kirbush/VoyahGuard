package local.codex.voyahcameralab;

/** App side of GuardBridge. */
final class GuardControl {
  private GuardControl() { }

  /** @return null when the state cannot be read, which callers treat as an unlocked car */
  static ParkedPolicy.Reading read() {
    String[] fields = AvmViewControl.bridge(GuardBridge.class.getName(), GuardBridge.STATUS).split("\t");
    if (fields.length != 5 || !GuardBridge.DONE.equals(fields[0])) return null;
    try {
      int armed = Integer.parseInt(fields[1].trim()), air = Integer.parseInt(fields[2].trim());
      int camp = Integer.parseInt(fields[3].trim()), care = Integer.parseInt(fields[4].trim());
      // The OEM power code treats 1 and 2 as armed.
      return new ParkedPolicy.Reading(armed == 1 || armed == 2, air == 1, camp == 1 || care == 1);
    } catch (NumberFormatException garbled) { return null; }
  }

  static boolean climate(boolean on) {
    return AvmViewControl.bridge(GuardBridge.class.getName(), on ? GuardBridge.AIR_ON : GuardBridge.AIR_OFF).startsWith(GuardBridge.DONE);
  }
}
