package local.codex.voyahcameralab;

import android.os.Bundle;
import android.os.IBinder;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Own app_process entry point for the parked mode. Reads a few fixed states, switches the climate
 * through the same ungated bundle setter as Power Hold, and flashes the ambient light red and the
 * headlights through the state setter the OEM settings app uses (it needs WRITE_CANBUS, which the
 * app holds). Nothing here accepts a key or a value from a caller: every operation and every word
 * of the alarm is fixed.
 */
public final class GuardBridge {
  static final String STATUS = "status", AIR_OFF = "air-off", AIR_ON = "air-on", ALARM = "alarm", LIGHT_RESTORE = "light-restore";
  /** The words of an alarm: whether the ambient light flashes, and which headlights. */
  static final String AMBIENT = "ambient", NO_AMBIENT = "no-ambient", LAMPS_NONE = "none", LAMPS_POSITION = "position", LAMPS_LOW = "low";
  static final String DONE = "G1";
  /** The flashes stop starting after FLASH_BUDGET_MS, the watchdog ends the process after ALARM_DEADLINE_MS. */
  private static final long DEADLINE_MS = 4000, ALARM_DEADLINE_MS = 14000, FLASH_BUDGET_MS = 8000;
  /** The caller's limit includes starting the VM. */
  static final long ALARM_TIMEOUT_MS = 18000;
  private static final String SWITCH = "VEHICLE_AMBIENT_LIGHT_SWITCH", COLOR = "VEHICLE_AMBIENT_LIGHT_COLOR", LEVEL = "VEHICLE_AMBIENT_LIGHT_BRIGHTNESS_LEVEL";
  /** Ambient light: red is the first colour of the OEM palette (#FA4550), level 10 the brightest; switch 1 off, 2 on. */
  private static final int RED = 62, BRIGHTEST = 10, OFF = 1, ON = 2;
  /**
   * The headlight selector of the OEM light page: every mode is its own key, written with 1, and
   * the feedback reads the mode back. Indices are the feedback values. Seen on the car: both
   * position lamps and low beam follow at a flash a second, and the mode comes back.
   */
  private static final String LAMP_MODE = "LAMP_SWITCH_POSITION_FB";
  private static final String[] LAMP_KEYS = {"AUTO_LAMP_SWITCH", "LOW_BEAM", "POSITION_LAMP_SWITCH", "OUT_LAMP_OFF"};
  private static final int LAMP_LOW = 1, LAMP_POSITION = 2, LAMP_OFF = 3, FLASHES = 6, HALF_FLASH_MS = 600;
  /**
   * The owner's lights before an alarm, kept until they are written back, also across a killed
   * run: ambient switch, colour, level and headlight mode; -1 for what the alarm does not touch.
   */
  static final String SAVED_PATH = "/data/user/0/local.codex.voyahcameralab/files/lights-before";
  private static final File SAVED = new File(SAVED_PATH);
  private static final String AIR_POWER = "AC_POWER_SWITCH";

  private final Object service;
  private final Class<?> vehicle, air;
  private final Method vehicleGetter, airGetter, bundleSetter, doorGetter, stateSetter;

  private GuardBridge() throws Exception {
    Object binder = Class.forName("com.qinggan.os.ServiceManager").getMethod("getService", String.class).invoke(null, "qg.canbus");
    if (!(binder instanceof IBinder)) throw new IllegalStateException("service");
    Class<?> contract = Class.forName("com.qinggan.canbus.ICanBusService");
    service = Class.forName("com.qinggan.canbus.ICanBusService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    if (service == null) throw new IllegalStateException("service");
    vehicle = Class.forName("com.qinggan.canbus.VehicleState");
    air = Class.forName("com.qinggan.canbus.AirConditionState");
    vehicleGetter = contract.getMethod("getVehicleState", vehicle);
    airGetter = contract.getMethod("getAirConditionState", air);
    bundleSetter = contract.getMethod("setVehicleAndAirConditionBundleState", Bundle.class, Bundle.class);
    doorGetter = contract.getMethod("getDoorStatus");
    stateSetter = contract.getMethod("setVehicleState", vehicle, int.class);
  }

  private void writeVehicle(String name, int value) throws Exception { stateSetter.invoke(service, vehicle.getField(name).get(null), value); }

  private boolean stillLocked() {
    try {
      String[] door = doors().split("\t");
      return ParkedPolicy.locked(readVehicle("POWER_HOLD_MODE_SWITCH"), Integer.parseInt(door[0]), Integer.parseInt(door[1]));
    } catch (Exception unreadable) { return false; }
  }

  /**
   * Flashes for about the length of the phrase and stops at once when the car is unlocked. No new
   * flash starts after the budget, so the restore always finishes well inside the watchdog. Always
   * ends with the owner's lights, written back from the saved values.
   * @param lamp LAMP_LOW, LAMP_POSITION, or -1 for no headlights
   */
  private String alarm(boolean ambient, int lamp) throws Exception {
    int[] before = saved();
    if (before == null) {
      before = new int[]{-1, -1, -1, -1};
      if (ambient) {
        int on = readVehicle(SWITCH), colour = readVehicle(COLOR), level = readVehicle(LEVEL);
        if (validAmbient(on, colour, level)) { before[0] = on; before[1] = colour; before[2] = level; }
      }
      if (lamp > 0) {
        int mode = readVehicle(LAMP_MODE);
        if (mode >= 0 && mode < LAMP_KEYS.length) before[3] = mode;
      }
      if (before[0] < 0 && before[3] < 0) return DONE + "\tunsupported";
      save(before);
    }
    boolean lights = ambient && before[0] > 0, lamps = lamp > 0 && before[3] >= 0, restored;
    long stopAt = System.nanoTime() + FLASH_BUDGET_MS * 1_000_000;
    try {
      if (lights) { writeVehicle(COLOR, RED); writeVehicle(LEVEL, BRIGHTEST); }
      for (int i = 0; i < FLASHES && System.nanoTime() < stopAt && stillLocked(); i++) {
        if (lights) writeVehicle(SWITCH, ON);
        if (lamps) writeVehicle(LAMP_KEYS[lamp], 1);
        Thread.sleep(HALF_FLASH_MS);
        if (lights) writeVehicle(SWITCH, OFF);
        if (lamps) writeVehicle(LAMP_KEYS[LAMP_OFF], 1);
        Thread.sleep(HALF_FLASH_MS);
      }
    } finally { restored = restore(before); }
    return restored ? DONE : DONE + "\tpartial";
  }

  private String restoreLights() {
    int[] before = saved();
    return before == null || restore(before) ? DONE : DONE + "\tpartial";
  }

  /**
   * Puts back only what the alarm left, each group on its own so one failing does not keep the
   * other; the saved file goes when both are back. After a killed run the owner may have changed
   * the lights by hand, and those changes stay.
   */
  private boolean restore(int[] before) {
    boolean done = true;
    try {
      if (before[0] > 0 && readVehicle(COLOR) == RED) { writeVehicle(COLOR, before[1]); writeVehicle(LEVEL, before[2]); writeVehicle(SWITCH, before[0]); }
    } catch (Exception failed) { done = false; }
    try {
      // Leaving Power Hold the car switches the lights to auto by itself; that is not undone.
      if (before[3] >= 0) {
        int now = readVehicle(LAMP_MODE);
        if (now == LAMP_OFF || now == LAMP_LOW || now == LAMP_POSITION) writeVehicle(LAMP_KEYS[before[3]], 1);
      }
    } catch (Exception failed) { done = false; }
    if (done) SAVED.delete();
    return done;
  }

  private static boolean validAmbient(int on, int colour, int level) { return (on == OFF || on == ON) && colour >= 1 && colour <= 64 && level >= 1 && level <= BRIGHTEST; }

  private static void save(int[] before) throws Exception {
    try (FileOutputStream out = new FileOutputStream(SAVED)) {
      out.write((before[0] + " " + before[1] + " " + before[2] + " " + before[3]).getBytes(StandardCharsets.US_ASCII));
      out.getFD().sync();
    }
  }

  /** The saved lights, or null when there are none or the file is not exactly what this class writes. */
  private static int[] saved() {
    try {
      if (!SAVED.isFile()) return null;
      String[] parts = new String(Files.readAllBytes(SAVED.toPath()), StandardCharsets.US_ASCII).trim().split(" ");
      if (parts.length != 4) return null;
      int[] v = new int[4];
      for (int i = 0; i < v.length; i++) v[i] = Integer.parseInt(parts[i]);
      boolean ambient = v[0] == -1 && v[1] == -1 && v[2] == -1 || validAmbient(v[0], v[1], v[2]);
      return ambient && v[3] >= -1 && v[3] < LAMP_KEYS.length ? v : null;
    } catch (Exception broken) { return null; }
  }

  private static int lampFor(String word) {
    if (LAMPS_LOW.equals(word)) return LAMP_LOW;
    if (LAMPS_POSITION.equals(word)) return LAMP_POSITION;
    if (LAMPS_NONE.equals(word)) return -1;
    throw new IllegalArgumentException("lamps");
  }

  /** Driver door lock (1 unlocked, 0 locked) and whether any door or the tailgate is open; -1 when unknown. */
  private String doors() {
    try {
      Object status = doorGetter.invoke(service);
      int lock = door(status, "fLDoorLockStatus"), open = 0;
      for (String name : new String[]{"fLDoor", "fRDoor", "rLDoor", "rRDoor", "loadSpace"}) if (door(status, name) == 1) open = 1;
      return lock + "\t" + open;
    } catch (Exception unreadable) { return "-1\t-1"; }
  }

  private static int door(Object status, String name) throws Exception {
    java.lang.reflect.Field field = status.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return ((Number) field.get(status)).intValue();
  }

  private int readVehicle(String name) throws Exception { return ((Number) vehicleGetter.invoke(service, vehicle.getField(name).get(null))).intValue(); }
  private int readAir(String name) throws Exception { return ((Number) airGetter.invoke(service, air.getField(name).get(null))).intValue(); }

  /**
   * One line: Power Hold switch, climate power, camp mode, care mode, driver door lock, doors open,
   * as the OEM reports them. The body unit sleeps on a locked car, so its anti-theft signals stay
   * stale. The Power Hold switch turns on after locking from outside but stays on after the unlock
   * until a door opens; the door lock itself changes at once, the body unit wakes up to send it.
   */
  private String status() throws Exception {
    return DONE + "\t" + readVehicle("POWER_HOLD_MODE_SWITCH") + "\t" + readAir(AIR_POWER) + "\t" + readVehicle("CAMP_MODE_SWITCH") + "\t" + readAir("AC_BABY_CARE_MODE") + "\t" + doors();
  }

  private String switchAir(int value) throws Exception {
    Bundle bundle = new Bundle();
    bundle.putInt(AIR_POWER, value);
    int queued = ((Number) bundleSetter.invoke(service, bundle, null)).intValue();
    if (queued != 0) throw new IllegalStateException("queue");
    return DONE;
  }

  public static void main(String[] args) {
    boolean alarm = args.length == 3 && ALARM.equals(args[0]);
    long deadline = alarm ? ALARM_DEADLINE_MS : DEADLINE_MS;
    Thread watchdog = new Thread(() -> {
      try { Thread.sleep(deadline); } catch (InterruptedException stopped) { return; }
      Runtime.getRuntime().halt(124);
    }, "guard-deadline");
    watchdog.setDaemon(true);
    watchdog.start();
    int exit = 1;
    try {
      if (args.length != 1 && !alarm) throw new IllegalArgumentException("operation");
      if (alarm && !AMBIENT.equals(args[1]) && !NO_AMBIENT.equals(args[1])) throw new IllegalArgumentException("ambient");
      GuardBridge bridge = new GuardBridge();
      String reply;
      if (alarm) reply = bridge.alarm(AMBIENT.equals(args[1]), lampFor(args[2]));
      else if (STATUS.equals(args[0])) reply = bridge.status();
      else if (AIR_OFF.equals(args[0])) reply = bridge.switchAir(0);
      else if (AIR_ON.equals(args[0])) reply = bridge.switchAir(1);
      else if (LIGHT_RESTORE.equals(args[0])) reply = bridge.restoreLights();
      else throw new IllegalArgumentException("operation");
      System.out.println(reply);
      exit = 0;
    } catch (Throwable failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      android.util.Log.w("GuardBridge", cause.toString());
    }
    System.out.flush();
    System.exit(exit);
  }
}
