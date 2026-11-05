package local.codex.voyahcameralab;

import android.os.Bundle;
import android.os.IBinder;

/**
 * Own app_process entry point for the OEM request that opens the 360 screen, used where
 * setVehicleState is refused: its WRITE_CANBUS permission exists only if VoyahTweaks declares it.
 * The bundle setter is not gated, and CanBusService routes this key to the same setVehicleState.
 * Accepts only the three values of this one request.
 */
public final class AvmBridge {
  static final String DONE = "AVM1 OK";
  private static final String REQUEST = "IVI_VOICE_REQ_AVM_SW";

  public static void main(String[] args) {
    int exit = 1;
    try {
      if (args.length != 1 || !args[0].matches("[012]")) throw new IllegalArgumentException("value");
      Object binder = Class.forName("com.qinggan.os.ServiceManager").getMethod("getService", String.class).invoke(null, "qg.canbus");
      if (!(binder instanceof IBinder)) throw new IllegalStateException("service");
      Object service = Class.forName("com.qinggan.canbus.ICanBusService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
      Bundle vehicle = new Bundle();
      vehicle.putInt(REQUEST, Integer.parseInt(args[0]));
      Object queued = Class.forName("com.qinggan.canbus.ICanBusService")
          .getMethod("setVehicleAndAirConditionBundleState", Bundle.class, Bundle.class).invoke(service, null, vehicle);
      if (((Number) queued).intValue() == 0) { System.out.println(DONE); exit = 0; }
    } catch (Throwable failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      android.util.Log.w("AvmBridge", cause.toString());
    }
    System.out.flush();
    System.exit(exit);
  }
}
