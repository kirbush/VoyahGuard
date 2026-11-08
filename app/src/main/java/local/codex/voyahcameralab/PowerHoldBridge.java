package local.codex.voyahcameralab;

import android.os.Bundle;
import android.os.IBinder;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Own app_process entry point. No OEM binaries, manager lifecycle, raw parcels or shell. */
public final class PowerHoldBridge {
  static final String API_PATH = "/system/framework/QGAPI.jar";
  static final String VEHICLE_PATH = "/system/framework/QGVehicle.jar";
  static final String[] SERVICE_PATHS = {
      "/system/priv-app/CanBusService-release/CanBusService-release.apk",
      "/system/priv-app/CanBusService/CanBusService.apk",
      "/system/priv-app/CanBusService.apk"
  };
  private static final String PROPERTY = "com.qinggan.powerholdmode.state";
  private static final String API_SHA = "e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510";
  private static final String VEHICLE_131 = "2d66e80382f77a0a861d49b3461ba862becd98faf5fbe31450c78ace402d9e69";
  private static final String SERVICE_131 = "44bd97c43c27a2bbfbc98a2b6dacb92d5ce932b1948b6b486738e307d59c3e3a";
  private static final String VEHICLE_BASELINE = "c245e689da80b8842c07f367362109ad4b12fe74642bcfe364d151bef9262040";
  private static final String SERVICE_BASELINE = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";

  /** Exit code of a run that certainly wrote nothing, so the caller may safely try again. */
  static final int NOT_SENT = 3;
  /** The service is installed somewhere else: its file is not hashed, the firmware is unverified. */
  static final int UNKNOWN_LOCATION = -1;
  /** Unverified firmware: reads after a request before it is taken back (one, then a re-read every step). */
  private static final int CONFIRM_READS = 6;
  private static final long CONFIRM_STEP_MS = 250, UNDO_RESERVE_MS = 1000;
  /** Unverified firmware: the confirmation and the undo must both fit before the first write. */
  private static final long UNVERIFIED_BUDGET_MS = (CONFIRM_READS - 1) * CONFIRM_STEP_MS + UNDO_RESERVE_MS + 250;
  /** Printed once the undo's disable was queued, so the caller knows it went out even if a later step failed. */
  static final String UNDO_SENT = "PH1U\tqueued";
  private static volatile boolean writeAttempted;
  /** Where a failed run stopped, from a fixed list: the app log gets this, never OEM output. */
  private static volatile String stage = "arguments";

  private final Object service, switchState, timeState, warningState, socState;
  private final Method getter, gearGetter, bundleSetter, propertyGet, propertySet;
  private final long deadline;

  private PowerHoldBridge(long deadline) throws Exception {
    this.deadline = deadline;
    Class<?> manager = Class.forName("com.qinggan.os.ServiceManager");
    Object binder = manager.getMethod("getService", String.class).invoke(null, "qg.canbus");
    if (!(binder instanceof IBinder)) throw new IllegalStateException("service");
    Class<?> contract = Class.forName("com.qinggan.canbus.ICanBusService");
    service = Class.forName("com.qinggan.canbus.ICanBusService$Stub")
        .getMethod("asInterface", IBinder.class).invoke(null, binder);
    if (service == null) throw new IllegalStateException("service");
    Class<?> state = Class.forName("com.qinggan.canbus.VehicleState");
    switchState = state.getField("POWER_HOLD_MODE_SWITCH").get(null);
    timeState = state.getField("POWER_HOLD_MODE_TIME").get(null);
    warningState = state.getField("POWER_HOLD_MODE_WARNING").get(null);
    socState = state.getField("BMS_SOC_DISPLAY").get(null);
    // Public interface methods avoid IllegalAccessException on a private Stub.Proxy class.
    getter = contract.getMethod("getVehicleState", state);
    gearGetter = contract.getMethod("getGearStatus");
    bundleSetter = contract.getMethod("setVehicleAndAirConditionBundleState", Bundle.class, Bundle.class);
    Class<?> properties = Class.forName("com.qinggan.os.SystemProperties");
    propertyGet = properties.getMethod("get", String.class);
    propertySet = properties.getMethod("set", String.class, String.class);
  }

  private int read(Object state) throws Exception { return ((Number) getter.invoke(service, state)).intValue(); }
  private String property() throws Exception {
    Object value = propertyGet.invoke(null, PROPERTY);
    if (!(value instanceof String)) throw new IllegalStateException("property");
    return (String) value;
  }
  private void writeProperty(String value) throws Exception {
    beforeWrite();
    propertySet.invoke(null, PROPERTY, value);
    if (!value.equals(property())) throw new IllegalStateException("property-readback");
  }
  private PowerHoldPolicy.Snapshot snapshot() throws Exception {
    Object gear = gearGetter.invoke(service);
    return new PowerHoldPolicy.Snapshot(read(switchState), read(timeState), property(),
        gear instanceof Enum<?> ? ((Enum<?>) gear).name() : "Unknown", read(socState),
        read(warningState), readBoot());
  }
  private void queue(int rawTime, boolean enable) throws Exception {
    beforeWrite();
    if (rawTime < 0 || rawTime > 15) throw new IllegalArgumentException("duration");
    Bundle vehicle = new Bundle();
    // The installed enum.name() is the OEM bundle key; only these two fields can be written.
    vehicle.putInt(((Enum<?>) timeState).name(), rawTime);
    vehicle.putInt(((Enum<?>) switchState).name(), enable ? 1 : 0);
    writeAttempted = true;
    int result = ((Number) bundleSetter.invoke(service, null, vehicle)).intValue();
    if (result != 0) throw new IllegalStateException("queue");
    // 0 means AsyncTask queued, never ECU acknowledgement.
  }
  private void beforeWrite() {
    if (android.os.SystemClock.elapsedRealtime() >= deadline)
      throw new IllegalStateException("deadline");
  }

  /** Unverified firmware: a request that does not read back as ours within a second is not kept. */
  private PowerHoldPolicy.Snapshot confirm(PowerHoldPolicy.Lease lease, PowerHoldPolicy.Snapshot after) throws Exception {
    for (int read = 1; ; read++) {
      boolean mayWait = read < CONFIRM_READS
          && android.os.SystemClock.elapsedRealtime() + CONFIRM_STEP_MS + UNDO_RESERVE_MS < deadline;
      switch (PowerHoldPolicy.confirmUnverified(lease, after, mayWait)) {
        case CONFIRMED: return after;
        case REJECT: return null;
        default: Thread.sleep(CONFIRM_STEP_MS); after = snapshot();
      }
    }
  }
  /**
   * Takes a request back the way release does: switch off, the property as it was. Each step is
   * tried even if the one before failed: the property does not depend on the service.
   */
  private PowerHoldPolicy.Snapshot undo(PowerHoldPolicy.Lease lease, PowerHoldPolicy.Snapshot original) throws Exception {
    Exception first = null;
    try {
      queue(lease.releaseTime(), false);
      System.out.println(UNDO_SENT);
      System.out.flush();
    } catch (Exception failure) { first = failure; }
    try {
      if ("on".equals(property())) writeProperty(original.property.isEmpty() ? "off" : original.property);
    } catch (Exception failure) { if (first == null) first = failure; }
    if (first != null) throw first;
    return snapshot();
  }

  static String readBoot() throws Exception {
    try (InputStream stream = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
      byte[] buffer = new byte[128];
      int count = stream.read(buffer);
      if (count <= 0 || count == buffer.length) throw new IllegalStateException("boot");
      String value = new String(buffer, 0, count, StandardCharsets.US_ASCII).trim();
      if (!value.matches("[a-fA-F0-9-]{36}")) throw new IllegalStateException("boot");
      return value;
    }
  }
  private static String hash(String path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream stream = new FileInputStream(path)) {
      byte[] bytes = new byte[32768]; int count;
      while ((count = stream.read(bytes)) != -1) digest.update(bytes, 0, count);
    }
    StringBuilder output = new StringBuilder();
    for (byte value : digest.digest()) output.append(String.format(Locale.ROOT, "%02x", value & 255));
    return output.toString();
  }
  static int serviceLocation(String installedPath) {
    for (int i = 0; i < SERVICE_PATHS.length; i++) if (SERVICE_PATHS[i].equals(installedPath)) return i;
    return UNKNOWN_LOCATION;
  }
  /** Reading only; the app calls it too, for the tester report. */
  static PowerHoldPolicy.Firmware firmware(int location) {
    String api = hashOrMark(API_PATH), vehicle = hashOrMark(VEHICLE_PATH);
    String service = location >= 0 && location < SERVICE_PATHS.length ? hashOrMark(SERVICE_PATHS[location]) : "unknown-path";
    boolean verified = API_SHA.equals(api) && ((VEHICLE_131.equals(vehicle) && SERVICE_131.equals(service))
        || (VEHICLE_BASELINE.equals(vehicle) && SERVICE_BASELINE.equals(service)));
    return new PowerHoldPolicy.Firmware(verified, api, vehicle, service);
  }
  private static String hashOrMark(String path) {
    try { return hash(path); } catch (Exception unreadable) { return "unreadable"; }
  }
  private static void reply(String code, PowerHoldPolicy.Snapshot value) {
    stage = "reply";
    System.out.println("PH1\t" + code + "\t" + value.wire());
    System.out.flush();
  }

  public static void main(String[] args) {
    // Shared monotonic deadline also bounds an app_process child if su has not exec'd it.
    // It is transport metadata only, never a vehicle state or arbitrary operation.
    long now = android.os.SystemClock.elapsedRealtime();
    long requestedDeadline;
    try {
      if (args.length < 3 || !args[2].matches("[0-9]{1,16}")) throw new IllegalArgumentException("deadline");
      requestedDeadline = Long.parseLong(args[2]);
      if (requestedDeadline <= now || requestedDeadline - now > 7500) throw new IllegalArgumentException("deadline");
    } catch (RuntimeException failure) {
      System.out.println("PH1\tERROR\tdeadline");
      System.exit(NOT_SENT);
      return;
    }
    final long deadline = Math.min(requestedDeadline, now + 6500);
    Thread watchdog = new Thread(() -> {
      try { Thread.sleep(Math.max(1, deadline - android.os.SystemClock.elapsedRealtime())); }
      catch (InterruptedException ignored) { return; }
      Runtime.getRuntime().halt(124);
    }, "power-hold-deadline");
    watchdog.setDaemon(true);
    watchdog.start();
    int exit = 1;
    try {
      String operation = args[0];
      if (!("status".equals(operation) && args.length == 3)
          && !("acquire".equals(operation) && args.length == 5)
          && !("release".equals(operation) && args.length == 5))
        throw new IllegalArgumentException("operation");
      if (!args[1].matches("-1|[0-2]")) throw new IllegalArgumentException("firmware");
      stage = "firmware";
      // Any firmware is read; an unverified one is reported, and its requests must confirm.
      PowerHoldPolicy.Firmware firmware = firmware(Integer.parseInt(args[1]));
      System.out.println(firmware.line());
      System.out.flush();
      stage = "connect";
      PowerHoldBridge bridge = new PowerHoldBridge(deadline);
      stage = "read";
      PowerHoldPolicy.Snapshot current = bridge.snapshot();
      if ("status".equals(operation)) reply("OK", current);
      else {
        stage = "arguments";
        int minutes = Integer.parseInt(args[3]);
        int raw = PowerHoldPolicy.rawMinutes(minutes);
        PowerHoldPolicy.Snapshot original = PowerHoldPolicy.Snapshot.parse(args[4]);
        PowerHoldPolicy.Lease lease = new PowerHoldPolicy.Lease(original, minutes);
        if ("acquire".equals(operation)) {
          // Re-read immediately before the first write; comparison is best effort, not atomic CAS.
          stage = "read";
          current = bridge.snapshot();
          if (!original.sameAs(current) || !PowerHoldPolicy.canAcquire(current)) reply("STALE", current);
          else {
            stage = "time";
            // Nothing is written yet, so running short here exits NOT_SENT and the caller may try again.
            if (!firmware.verified && android.os.SystemClock.elapsedRealtime() + UNVERIFIED_BUDGET_MS >= deadline)
              throw new IllegalStateException("time");
            stage = "write";
            bridge.queue(raw, true);
            stage = "property";
            try { bridge.writeProperty("on"); }
            catch (Exception failure) { bridge.queue(raw, false); throw failure; } // Never leave a half-made request.
            stage = "confirm";
            if (firmware.verified) reply("QUEUED", bridge.snapshot());
            else {
              PowerHoldPolicy.Snapshot confirmed;
              try { confirmed = bridge.confirm(lease, bridge.snapshot()); }
              catch (Exception unreadable) { confirmed = null; } // what cannot be read back is not kept
              if (confirmed != null) reply("QUEUED", confirmed);
              else { stage = "undo"; reply("REJECTED", bridge.undo(lease, original)); }
            }
          }
        } else {
          stage = "read";
          current = bridge.snapshot();
          PowerHoldPolicy.ReleaseDecision decision = lease.releaseDecision(current);
          if (decision != PowerHoldPolicy.ReleaseDecision.RELEASE) reply(decision.name(), current);
          else {
            stage = "release";
            bridge.queue(lease.releaseTime(), false);
            // A different property after the queued request may be an external update.
            // OEM callbacks may clear it too; exact equality still avoids blind overwrites.
            String property = bridge.property();
            if ("on".equals(property)) bridge.writeProperty(original.property.isEmpty() ? "off" : original.property);
            else if (!PowerHoldPolicy.sameProperty(original.property, property)) throw new IllegalStateException("release-property-changed");
            PowerHoldPolicy.Snapshot after = bridge.snapshot();
            boolean complete = after.switchValue == 0 && after.rawTime == original.rawTime
                && PowerHoldPolicy.sameProperty(original.property, after.property) && original.boot.equals(after.boot);
            reply(complete ? "RELEASED" : "RELEASE_QUEUED", after);
          }
        }
      }
      exit = 0;
    } catch (Throwable failure) {
      // Never expose arbitrary OEM output/property values to the UI or offer raw retry commands.
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      android.util.Log.w("PowerHoldBridge", cause.toString());
      // The stage and the exception class only: the app's log is in the tester report.
      System.out.println("PH1\tERROR\t" + PowerHoldPolicy.bridgeReason(stage + ":" + cause.getClass().getSimpleName()));
      System.out.flush();
      if (!writeAttempted) exit = NOT_SENT;
    } finally {
      watchdog.interrupt();
    }
    System.exit(exit);
  }
}
