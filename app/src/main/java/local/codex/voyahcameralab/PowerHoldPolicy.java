package local.codex.voyahcameralab;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Pure software policy. Cache readings are not proof of ECU acceptance. */
final class PowerHoldPolicy {
  enum State { OFF, REQUESTED, ARMED, ACTIVE, UNAVAILABLE }
  enum ReleaseDecision { RELEASE, ENDED, CHANGED, UNKNOWN, REVOKED }

  /** One operation lock for every Activity/controller in this app process. */
  static final class OwnershipGuard {
    private static final java.util.concurrent.locks.ReentrantLock PROCESS_LOCK = new java.util.concurrent.locks.ReentrantLock();
    private final String session;
    OwnershipGuard(String session) {
      if (session == null || session.isEmpty()) throw new IllegalArgumentException("session");
      this.session = session;
    }
    <T> T serialized(java.util.function.Supplier<T> operation) {
      PROCESS_LOCK.lock();
      try { return operation.get(); } finally { PROCESS_LOCK.unlock(); }
    }
    <T> T serialized(long deadline, java.util.function.Supplier<T> operation, java.util.function.Supplier<T> busy) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return busy.get();
      try {
        if (!PROCESS_LOCK.tryLock(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) return busy.get();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return busy.get();
      }
      try { return operation.get(); } finally { PROCESS_LOCK.unlock(); }
    }
    boolean mayStart(boolean pending) { return !pending; }
    boolean ownsJournal(boolean pending, String owner) { return pending && session.equals(owner); }
  }

  static int rawMinutes(int minutes) {
    if (minutes < 30 || minutes > 180 || minutes % 30 != 0)
      throw new IllegalArgumentException("Supported minutes: 30/60/90/120/150/180");
    return minutes / 30;
  }

  static final class Snapshot {
    final int switchValue, rawTime, soc, warning;
    final String property, gear, boot;
    Snapshot(int switchValue, int rawTime, String property, String gear, int soc,
        int warning, String boot) {
      this.switchValue = switchValue;
      this.rawTime = rawTime;
      this.property = property;
      this.gear = gear;
      this.soc = soc;
      this.warning = warning;
      this.boot = boot;
    }
    /** TIME is -1 on H97X in every state, including an active hold: the cache never reports it. */
    boolean usable() {
      return (switchValue == 0 || switchValue == 1) && rawTime >= -1 && rawTime <= 15
          && property != null && property.getBytes(StandardCharsets.UTF_8).length <= 91
          && gear != null && boot != null && !boot.isEmpty();
    }
    boolean timeReported() { return rawTime >= 0; }
    boolean safeParking() {
      return "Parking".equals(gear) && soc >= 15 && soc <= 100
          && warning != 1 && warning != 2;
    }
    boolean sameAs(Snapshot other) {
      return other != null && switchValue == other.switchValue && rawTime == other.rawTime
          && soc == other.soc && warning == other.warning && equal(property, other.property)
          && equal(gear, other.gear) && equal(boot, other.boot);
    }
    /** For the app log: the property is reduced to on/off/other, never its raw value. */
    String log() {
      String shown = "on".equals(property) ? "on" : normal(property).equals("off") ? "off" : "other";
      return "switch=" + switchValue + " time=" + rawTime + " property=" + shown + " gear=" + gear
          + " soc=" + soc + " warning=" + warning;
    }
    String wire() {
      if (property == null || gear == null || !gear.matches("[A-Za-z_]{0,64}")
          || boot == null || !boot.matches("[A-Za-z0-9-]{1,64}"))
        throw new IllegalArgumentException("Unserializable snapshot");
      return switchValue + "\t" + rawTime + "\t" + soc + "\t" + warning + "\t" + gear
          + "\t" + Base64.getUrlEncoder().withoutPadding().encodeToString(property.getBytes(StandardCharsets.UTF_8))
          + "\t" + boot;
    }
    static Snapshot parse(String wire) {
      if (wire == null || wire.length() > 512) throw new IllegalArgumentException("Invalid snapshot");
      String[] fields = wire.split("\t", -1);
      if (fields.length != 7 || !fields[4].matches("[A-Za-z_]{0,64}")
          || !fields[6].matches("[A-Za-z0-9-]{1,64}"))
        throw new IllegalArgumentException("Invalid snapshot");
      String property = new String(Base64.getUrlDecoder().decode(fields[5]), StandardCharsets.UTF_8);
      if (property.getBytes(StandardCharsets.UTF_8).length > 91)
        throw new IllegalArgumentException("Invalid property length");
      return new Snapshot(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), property,
          fields[4], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), fields[6]);
    }
  }

  /** The OEM listener writes "off" over the empty value left by boot: both mean not prepared. */
  static boolean sameProperty(String left, String right) { return normal(left).equals(normal(right)); }
  private static String normal(String value) { return value == null || value.isEmpty() ? "off" : value; }

  static boolean canAcquire(Snapshot snapshot) {
    return snapshot != null && snapshot.usable() && snapshot.safeParking()
        && snapshot.switchValue == 0 && !"on".equals(snapshot.property);
  }

  /** A lease ends with its boot (see Lease.inspect), so such a journal is only retired, never replayed. */
  static boolean journalExpired(String originalWire, Snapshot current) {
    if (current == null || !current.usable()) return false;
    try { return !equal(Snapshot.parse(originalWire).boot, current.boot); }
    catch (IllegalArgumentException unreadable) { return false; }
  }

  static State state(Snapshot snapshot) {
    if (snapshot == null || !snapshot.usable()) return State.UNAVAILABLE;
    if (snapshot.switchValue == 1) return State.ACTIVE;
    return "on".equals(snapshot.property) ? State.ARMED : State.OFF;
  }

  static State stateAfterQueued(Snapshot snapshot) {
    State value = state(snapshot);
    return value == State.OFF ? State.REQUESTED : value;
  }

  /**
   * What the bridge found installed. Verified means the SDK and the service match one of the two
   * firmwares whose files were checked by hand; any other firmware is still read and may be tried.
   */
  static final class Firmware {
    private static final String MARK = "[0-9a-f]{64}|unreadable|unknown-path";
    final boolean verified;
    final String api, vehicle, service;
    Firmware(boolean verified, String api, String vehicle, String service) {
      this.verified = verified; this.api = api; this.vehicle = vehicle; this.service = service;
    }
    /** The bridge's "PH1F" line; anything else is not a firmware report. */
    static Firmware parse(String line) {
      String[] f = line == null ? new String[0] : line.split("\t", -1);
      if (f.length != 5 || !"PH1F".equals(f[0]) || !f[1].matches("verified|unverified")
          || !f[2].matches(MARK) || !f[3].matches(MARK) || !f[4].matches(MARK))
        throw new IllegalArgumentException("Invalid firmware line");
      if ("verified".equals(f[1]) && !(f[2] + f[3] + f[4]).matches("[0-9a-f]{192}"))
        throw new IllegalArgumentException("Verified firmware needs three hashes");
      return new Firmware("verified".equals(f[1]), f[2], f[3], f[4]);
    }
    String line() { return "PH1F\t" + (verified ? "verified" : "unverified") + "\t" + api + "\t" + vehicle + "\t" + service; }
    String describe() {
      return (verified ? "verified" : "unverified") + " QGAPI=" + api + " QGVehicle=" + vehicle + " CanBusService=" + service;
    }
    boolean sameAs(Firmware other) { return other != null && line().equals(other.line()); }
  }

  /** A bridge failure: one fixed stage and an exception class, never an OEM value. */
  static String bridgeReason(String raw) {
    return raw != null && raw.matches("[a-z-]{1,16}(:[A-Za-z0-9_$]{1,64})?") ? raw : "unavailable";
  }

  enum Confirmation { CONFIRMED, WAIT, REJECT }

  /**
   * Unverified firmware: the request must read back as ours, or it is taken back at once. On
   * H97X the duration always reads -1, so what is checked is the switch, the property, the boot,
   * P, the charge and the warning; a reported duration must be ours.
   */
  static Confirmation confirmUnverified(Lease lease, Snapshot after, boolean mayWait) {
    ReleaseDecision decision = lease.releaseDecision(after);
    // Lease accepts any duration when the original did not report one; here a reported one must be ours.
    boolean ours = decision == ReleaseDecision.RELEASE && (!after.timeReported() || after.rawTime == lease.rawTime);
    if (ours) return Confirmation.CONFIRMED;
    boolean unsure = decision == ReleaseDecision.UNKNOWN || decision == ReleaseDecision.RELEASE;
    return unsure && mayWait ? Confirmation.WAIT : Confirmation.REJECT;
  }

  static final class Lease {
    final Snapshot original;
    final int minutes, rawTime;
    private boolean writeAuthorityRevoked, requestedTimeObserved;
    Lease(Snapshot original, int minutes) {
      if (!canAcquire(original)) throw new IllegalArgumentException("Unsafe original state");
      this.original = original;
      this.minutes = minutes;
      this.rawTime = rawMinutes(minutes);
    }
    /** TIME sent with the disable: an unreported original must not be written back as -1. */
    int releaseTime() { return original.timeReported() ? original.rawTime : rawTime; }
    ReleaseDecision releaseDecision(Snapshot current) {
      if (writeAuthorityRevoked) return ReleaseDecision.REVOKED;
      ReleaseDecision decision = inspect(current);
      if (decision == ReleaseDecision.ENDED || decision == ReleaseDecision.CHANGED)
        writeAuthorityRevoked = true;
      else if (decision == ReleaseDecision.RELEASE) requestedTimeObserved = true;
      return decision;
    }
    boolean writeAuthorityRevoked() { return writeAuthorityRevoked; }
    void revokeWriteAuthority() { writeAuthorityRevoked = true; }
    private ReleaseDecision inspect(Snapshot current) {
      if (current == null || !current.usable()) return ReleaseDecision.UNKNOWN;
      if (!equal(original.boot, current.boot)) return ReleaseDecision.CHANGED;
      // Never restore the old duration after OEM cancellation, even though it looks harmless:
      // doing so could race a later user request. No automatic enable exists in this policy.
      if (current.switchValue == 0 && !"on".equals(current.property)) return ReleaseDecision.ENDED;
      if (current.soc < 0 || current.soc > 100 || current.gear.isEmpty()
          || "Unknown".equals(current.gear)) return ReleaseDecision.UNKNOWN;
      if (!"on".equals(current.property) || !current.safeParking()) return ReleaseDecision.CHANGED;
      if (!original.timeReported()) return ReleaseDecision.RELEASE; // Only the switch and property exist here.
      if (!current.timeReported()) return ReleaseDecision.UNKNOWN;
      if (current.rawTime != rawTime) {
        // The setter only queues work: its first readback can still contain the saved
        // duration. It proves neither ownership nor an external change. Once our
        // requested duration was seen, a return to the old duration is a real change.
        if (!requestedTimeObserved && current.rawTime == original.rawTime) return ReleaseDecision.UNKNOWN;
        return ReleaseDecision.CHANGED;
      }
      return ReleaseDecision.RELEASE;
    }
  }

  private static boolean equal(Object left, Object right) {
    return left == null ? right == null : left.equals(right);
  }
  private PowerHoldPolicy() {}
}
