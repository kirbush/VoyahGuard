package local.codex.voyahcameralab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Darkens the screens through the OEM power and backlight services, both open to any app.
 *
 * The OEM screen-off state is black and wakes on a touch, but it refuses the driver's screen
 * while the 360 screen is open, and that is exactly when the car is being watched. There the
 * window-manager brightness override takes over: it goes down to the lowest level, not to black,
 * changes no saved setting and leaves the screen usable if this app dies. -1 ends the override.
 */
final class ScreenControl implements ServiceConnection {
  private static final String TAG = "SentryScreen";
  private static final String POLICY = "com.qinggan.system.ISystemPolicy";
  private static final String POLICY_ACTION = "com.qinggan.systempolicy.SystemPolicyService", POLICY_PACKAGE = "com.qinggan.systempolicy";
  private static final int SET_BACKLIGHT = 3, FLAG_WINDOW_MANAGER = 4, SCREEN_MASK_DRIVER = 1;
  private static final int LOWEST = 0, END_OVERRIDE = -1;
  private static final String ENTER_OFF = "9", EXIT_OFF = "11", DRIVER = "1", PASSENGER = "2";

  private final Context context;
  private volatile IBinder policy;
  private boolean bound;

  ScreenControl(Context context) { this.context = context.getApplicationContext(); }

  void connect() {
    if (bound) return;
    try { bound = context.bindService(new Intent(POLICY_ACTION).setPackage(POLICY_PACKAGE), this, Context.BIND_AUTO_CREATE); }
    catch (RuntimeException failure) { AppLog.w(TAG, "Backlight service", failure); }
  }

  void disconnect() {
    if (!bound) return;
    bound = false;
    policy = null;
    try { context.unbindService(this); } catch (RuntimeException ignored) { }
  }

  @Override public void onServiceConnected(ComponentName name, IBinder service) { policy = service; }
  @Override public void onServiceDisconnected(ComponentName name) { policy = null; }

  void darken() {
    power(ENTER_OFF, PASSENGER);
    power(ENTER_OFF, DRIVER);
    keepDark();
  }

  /** Opening the 360 screen raises the brightness again, so this is repeated. */
  void keepDark() { backlight(LOWEST); }

  /** @return false when the driver's screen may still be dim: the caller tries again */
  boolean restore() {
    power(EXIT_OFF, PASSENGER);
    power(EXIT_OFF, DRIVER);
    return backlight(END_OVERRIDE);
  }

  private static void power(String transaction, String screen) {
    AvmViewControl.run("/system/bin/service", "call", "qg.power_manager", transaction, "i32", screen);
  }

  private boolean backlight(int level) {
    IBinder target = policy;
    if (target == null) return false;
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(POLICY);
      data.writeInt(level);
      data.writeInt(FLAG_WINDOW_MANAGER);
      data.writeInt(SCREEN_MASK_DRIVER);
      data.writeInt(0); // No high-temperature handling: the OEM keeps its own limit.
      target.transact(SET_BACKLIGHT, data, reply, 0);
      reply.readException();
      return true; // The call reports false for this override even when it is applied.
    } catch (Exception failure) {
      AppLog.w(TAG, "Backlight", failure);
      return false;
    } finally {
      data.recycle();
      reply.recycle();
    }
  }
}
