package local.codex.voyahcameralab;

/** A launch requests recording once; callbacks/resume must never create another request. */
final class LaunchPolicy {
  private boolean pending,permissionAsked;
  void newLaunch(boolean automatic){pending=automatic;permissionAsked=false;}
  void restore(boolean waiting){pending=waiting;}
  boolean isPending(){return pending;}
  boolean takeAutoRecord(){boolean value=pending;pending=false;return value;}
  void stop(){pending=false;}
  /** Every system dialog ends in onResume; only a new launch may ask again. */
  boolean takePermissionRequest(){boolean value=!permissionAsked;permissionAsked=true;return value;}
}
