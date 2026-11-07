package local.codex.voyahcameralab;

import android.os.Bundle;
import android.os.IBinder;
import java.lang.reflect.Method;

/**
 * Own app_process entry point for the parked mode. Reads a few fixed states and switches the
 * climate through the same ungated bundle setter as Power Hold. Nothing here accepts a key or a
 * value from a caller: there are three operations and one written key.
 */
public final class GuardBridge {
  static final String STATUS = "status", AIR_OFF = "air-off", AIR_ON = "air-on";
  static final String DONE = "G1";
  private static final long DEADLINE_MS = 4000;
  private static final String AIR_POWER = "AC_POWER_SWITCH";

  private final Object service;
  private final Class<?> vehicle, air;
  private final Method vehicleGetter, airGetter, bundleSetter;

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
  }

  private int readVehicle(String name) throws Exception { return ((Number) vehicleGetter.invoke(service, vehicle.getField(name).get(null))).intValue(); }
  private int readAir(String name) throws Exception { return ((Number) airGetter.invoke(service, air.getField(name).get(null))).intValue(); }

  /**
   * One line: Power Hold switch, climate power, camp mode, care mode, as the OEM reports them.
   * The body unit sleeps on a locked car, so its anti-theft signals stay stale in the head unit.
   * The Power Hold switch turns on only after locking from outside and off on unlock.
   */
  private String status() throws Exception {
    return DONE + "\t" + readVehicle("POWER_HOLD_MODE_SWITCH") + "\t" + readAir(AIR_POWER) + "\t" + readVehicle("CAMP_MODE_SWITCH") + "\t" + readAir("AC_BABY_CARE_MODE");
  }

  private String switchAir(int value) throws Exception {
    Bundle bundle = new Bundle();
    bundle.putInt(AIR_POWER, value);
    int queued = ((Number) bundleSetter.invoke(service, bundle, null)).intValue();
    if (queued != 0) throw new IllegalStateException("queue");
    return DONE;
  }

  public static void main(String[] args) {
    Thread watchdog = new Thread(() -> {
      try { Thread.sleep(DEADLINE_MS); } catch (InterruptedException stopped) { return; }
      Runtime.getRuntime().halt(124);
    }, "guard-deadline");
    watchdog.setDaemon(true);
    watchdog.start();
    int exit = 1;
    try {
      if (args.length != 1) throw new IllegalArgumentException("operation");
      GuardBridge bridge = new GuardBridge();
      String reply;
      if (STATUS.equals(args[0])) reply = bridge.status();
      else if (AIR_OFF.equals(args[0])) reply = bridge.switchAir(0);
      else if (AIR_ON.equals(args[0])) reply = bridge.switchAir(1);
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
