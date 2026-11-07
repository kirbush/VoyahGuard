package local.codex.voyahcameralab;

/** App side of GuardBridge. */
final class GuardControl {
  private GuardControl() { }

  /** @return null when the state cannot be read, which callers treat as an unlocked car */
  static ParkedPolicy.Reading read() {
    String[] fields = AvmViewControl.bridge(GuardBridge.class.getName(), GuardBridge.STATUS).split("\t");
    if (fields.length != 7 || !GuardBridge.DONE.equals(fields[0])) return null;
    try {
      int hold = Integer.parseInt(fields[1].trim()), air = Integer.parseInt(fields[2].trim());
      int camp = Integer.parseInt(fields[3].trim()), care = Integer.parseInt(fields[4].trim());
      int lock = Integer.parseInt(fields[5].trim()), open = Integer.parseInt(fields[6].trim());
      return new ParkedPolicy.Reading(ParkedPolicy.locked(hold, lock, open), air == 1, camp == 1 || care == 1);
    } catch (NumberFormatException garbled) { return null; }
  }

  static final int LAMPS_NONE = 0, LAMPS_POSITION = 1, LAMPS_LOW = 2;
  private static final String[] LAMP_WORDS = {GuardBridge.LAMPS_NONE, GuardBridge.LAMPS_POSITION, GuardBridge.LAMPS_LOW};

  /** Blocks for the length of the flashes, about eight seconds. @param lamps LAMPS_NONE, LAMPS_POSITION or LAMPS_LOW */
  static boolean alarmLights(boolean ambient, int lamps) {
    String word = LAMP_WORDS[Math.max(0, Math.min(LAMP_WORDS.length - 1, lamps))];
    String reply = AvmViewControl.bridge(GuardBridge.ALARM_TIMEOUT_MS, GuardBridge.class.getName(), GuardBridge.ALARM, ambient ? GuardBridge.AMBIENT : GuardBridge.NO_AMBIENT, word);
    return reply.equals(GuardBridge.DONE) || reply.equals(GuardBridge.DONE + "\tunsupported");
  }

  /** True only when every saved light is back; a partial restore is retried by the caller. */
  static boolean restoreLights() { return AvmViewControl.bridge(GuardBridge.class.getName(), GuardBridge.LIGHT_RESTORE).equals(GuardBridge.DONE); }

  static boolean climate(boolean on) {
    return AvmViewControl.bridge(GuardBridge.class.getName(), on ? GuardBridge.AIR_ON : GuardBridge.AIR_OFF).startsWith(GuardBridge.DONE);
  }
}
