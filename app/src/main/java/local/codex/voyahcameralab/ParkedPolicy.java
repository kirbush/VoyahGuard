package local.codex.voyahcameralab;

import java.util.ArrayList;
import java.util.List;

/**
 * What the car should do while it stands locked. Pure logic, called with each status reading.
 *
 * Everything here is undone as soon as the car is no longer known to be locked: an unreadable
 * status counts as unlocked, so a failure can only leave the screens on and the climate as it
 * was. Only climate that was on and that this class switched off is switched back on. Camp and
 * care modes mean somebody may be inside, so a locked car in such a mode is left alone.
 */
final class ParkedPolicy {
  enum Action {DARKEN, KEEP_DARK, RESTORE_SCREENS, CLIMATE_OFF, CLIMATE_ON}

  static final class Reading {
    final boolean armed, airOn, careMode;
    Reading(boolean armed, boolean airOn, boolean careMode) { this.armed = armed; this.airOn = airOn; this.careMode = careMode; }
  }

  private boolean dark, climateOff;

  /** @param reading null when the status could not be read */
  List<Action> update(Reading reading, boolean screensOption, boolean climateOption) {
    List<Action> actions = new ArrayList<>();
    boolean armed = guarded(reading);
    if (armed && screensOption) { actions.add(dark ? Action.KEEP_DARK : Action.DARKEN); dark = true; }
    else if (dark) { actions.add(Action.RESTORE_SCREENS); dark = false; }
    boolean wanted = armed && climateOption;
    if (wanted && reading.airOn && !climateOff) { actions.add(Action.CLIMATE_OFF); climateOff = true; }
    else if (!wanted && climateOff) { actions.add(Action.CLIMATE_ON); climateOff = false; }
    return actions;
  }

  List<Action> stop() { return update(null, false, false); }

  /** Locked, and nothing says that somebody stayed inside. */
  static boolean guarded(Reading reading) { return reading != null && reading.armed && !reading.careMode; }
}
