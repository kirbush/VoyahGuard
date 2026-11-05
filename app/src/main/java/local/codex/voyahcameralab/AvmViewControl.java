package local.codex.voyahcameralab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The only automotive surface of the adaptive mode: five fixed touches, the OEM open and
 * close request for the 360 screen, and read-only gear and 360 state. Runs with the app's own
 * UID; nothing here accepts a packet, a state name or a command line from a caller.
 */
final class AvmViewControl {
  private static final String PRODUCT = "f6811";
  private static final long PRESS_MS = 120, COMMAND_TIMEOUT_MS = 3000;
  private static final long OPEN_PULSE_MS = 3000;
  private static final int AVM_OPEN = 1, AVM_CLOSE = 2, AVM_IDLE = 0;
  private static boolean loaded;
  private static String loadError;

  static {
    try { System.loadLibrary("avmview"); loaded = true; }
    catch (Throwable error) { loadError = String.valueOf(error.getMessage()); }
  }

  private static native int nativeTouch(int target, boolean pressed);

  /** Empty when supported, otherwise a reason for the owner. */
  static String unavailableReason() {
    if (!loaded) return "управление видом недоступно: " + loadError;
    if (!PRODUCT.equals(run("/system/bin/getprop", "ro.build.product_3rdparty"))) return "прошивка не поддерживается";
    return "";
  }

  /** @param target one of the AvmLayout views or TOGGLE_2D */
  static boolean send(int target) throws InterruptedException {
    if (nativeTouch(target, true) != 0) return false;
    Thread.sleep(PRESS_MS);
    return nativeTouch(target, false) == 0;
  }

  static boolean showing() {
    return run("/system/bin/dumpsys", "dls_vehicle_signal_service").contains("STATE_SHOW");
  }

  /** getGearStatus: no exception, non-null state, first field 0 is Parking. Anything else is not P. */
  static boolean parked() {
    String reply = run("/system/bin/service", "call", "qg.canbus", "6");
    int start = reply.indexOf('(');
    if (start < 0) return false;
    String[] words = reply.substring(start + 1).trim().split("\\s+");
    return words.length >= 3 && "00000000".equals(words[0]) && "00000001".equals(words[1]) && "00000000".equals(words[2]);
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

  private static boolean request(int value) {
    String reply = run("/system/bin/service", "call", "qg.canbus", "58", "i32", "1", "i32", "838", "i32", "1055", "i32", String.valueOf(value));
    return reply.contains("Parcel(00000000");
  }

  private static String run(String... command) {
    List<String> arguments = Arrays.asList(command);
    Process process = null;
    try {
      process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        for (String line; (line = reader.readLine()) != null && output.length() < 4096; ) output.append(line).append('\n');
      }
      if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "";
      return output.toString().trim();
    } catch (Exception error) {
      return "";
    } finally {
      if (process != null) process.destroy();
    }
  }
}
