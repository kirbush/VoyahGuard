package local.codex.voyahcameralab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * The only automotive surface of the adaptive mode: thirteen fixed touches, the OEM open and
 * close request for the 360 screen, and read-only gear and 360 state. Runs with the app's own
 * UID; nothing here accepts a packet, a state name or a command line from a caller.
 */
final class AvmViewControl {
  private static final String PRODUCT = "f6811";
  private static final long PRESS_MS = 120, SECOND_TAP_MS = 300, COMMAND_TIMEOUT_MS = 5000;
  private static final int TOUCH_MODE = 4, TOUCH_3D = 5;
  private static final long OPEN_PULSE_MS = 3000;
  private static final int AVM_OPEN = 1, AVM_CLOSE = 2, AVM_IDLE = 0;
  private static boolean loaded;
  private static volatile String apk;
  private static String loadError;

  static {
    try { System.loadLibrary("avmview"); loaded = true; }
    catch (Throwable error) { loadError = String.valueOf(error.getMessage()); }
  }

  private static native int nativeTouch(int target, boolean pressed);

  /** The bridge runs from this APK in its own app_process. */
  static void setApk(String path) { apk = path; }

  /** Empty when supported, otherwise a reason for the owner. */
  static String unavailableReason() {
    if (!loaded) return "управление видом недоступно: " + loadError;
    if (!PRODUCT.equals(run("/system/bin/getprop", "ro.build.product_3rdparty"))) return "прошивка не поддерживается";
    return "";
  }

  static boolean send(int view) throws InterruptedException { return tap(view); }
  static boolean toggleMode() throws InterruptedException { return tap(TOUCH_MODE); }

  /** The OEM 3D buttons react to a double tap only; a single one changed nothing on the car. */
  static boolean sendButton(int button) throws InterruptedException {
    if (!tap(TOUCH_3D + button)) return false;
    Thread.sleep(SECOND_TAP_MS);
    return tap(TOUCH_3D + button);
  }

  private static boolean tap(int target) throws InterruptedException {
    if (nativeTouch(target, true) != 0) return false;
    Thread.sleep(PRESS_MS);
    return nativeTouch(target, false) == 0;
  }

  static boolean showing() {
    return run("/system/bin/dumpsys", "dls_vehicle_signal_service").contains("STATE_SHOW");
  }

  /** Gear from canbus transaction 6: P, another gear, or unknown when the reply cannot be read. */
  static final int GEAR_UNKNOWN = -1, GEAR_OTHER = 0, GEAR_PARK = 1;

  static boolean parked() { return gear() == GEAR_PARK; }

  /** The reply is (status, present, gear): P is 0, D was seen as 3. Anything else is unknown. */
  static int gear() {
    String reply = run("/system/bin/service", "call", "qg.canbus", "6");
    int start = reply.indexOf('(');
    if (start < 0) return GEAR_UNKNOWN;
    String[] words = reply.substring(start + 1).trim().split("\\s+");
    if (words.length < 3 || !"00000000".equals(words[0]) || !"00000001".equals(words[1]) || !words[2].matches("[0-9a-f]{8}")) return GEAR_UNKNOWN;
    return "00000000".equals(words[2]) ? GEAR_PARK : GEAR_OTHER;
  }

  static boolean open() throws InterruptedException { return pulse(AVM_OPEN); }
  static boolean close() throws InterruptedException { return pulse(AVM_CLOSE); }

  /** Same request and hold time as the OEM button; always ends on the idle value. */
  private static boolean pulse(int value) throws InterruptedException {
    boolean sent = request(value);
    try { Thread.sleep(OPEN_PULSE_MS); }
    finally { if (!request(AVM_IDLE)) sent = false; }
    return sent;
  }

  /** The direct call needs WRITE_CANBUS, which only VoyahTweaks declares; otherwise the bridge carries it. */
  private static boolean request(int value) {
    String reply = run("/system/bin/service", "call", "qg.canbus", "58", "i32", "1", "i32", "838", "i32", "1055", "i32", String.valueOf(value));
    if (reply.contains("Parcel(00000000")) return true;
    return bridge(AvmBridge.class.getName(), String.valueOf(value)).contains(AvmBridge.DONE);
  }

  /** One of this APK's fixed entry points in its own app_process, or "" without an APK path. */
  static String bridge(String entry, String... arguments) { return bridge(COMMAND_TIMEOUT_MS, entry, arguments); }

  /** For the few operations that run longer by design, such as the alarm light. */
  static String bridge(long timeoutMs, String entry, String... arguments) {
    if (apk == null || apk.indexOf(':') >= 0) return "";
    String[] command = new String[4 + arguments.length];
    command[0] = "/system/bin/app_process";
    command[1] = "-Djava.class.path=" + apk + ":" + PowerHoldBridge.API_PATH + ":" + PowerHoldBridge.VEHICLE_PATH;
    command[2] = "/system/bin";
    command[3] = entry;
    System.arraycopy(arguments, 0, command, 4, arguments.length);
    return run(timeoutMs, command);
  }

  /** Bounded even if the child never writes or exits: the pipe is read on its own daemon thread. */
  static String run(String... command) { return run(COMMAND_TIMEOUT_MS, command); }

  static String run(long timeoutMs, String... command) {
    Process process = null;
    try {
      process = new ProcessBuilder(Arrays.asList(command)).redirectErrorStream(true).start();
      InputStream stream = process.getInputStream();
      StringBuffer output = new StringBuffer();
      Thread reader = new Thread(() -> {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream))) {
          for (String line; (line = lines.readLine()) != null && output.length() < 4096; ) output.append(line).append('\n');
        } catch (IOException closed) { /* the partial output is judged by the caller */ }
      }, "avm-command");
      reader.setDaemon(true);
      reader.start();
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return "";
      reader.join(200);
      return output.toString().trim();
    } catch (Exception error) {
      return "";
    } finally {
      if (process != null) process.destroyForcibly();
    }
  }
}
