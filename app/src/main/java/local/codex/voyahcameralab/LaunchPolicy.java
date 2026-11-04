package local.codex.voyahcameralab;

/** A launch requests recording once; callbacks/resume must never create another request. */
final class LaunchPolicy {
  private boolean pending;
  void newLaunch(boolean automatic){pending=automatic;}
  void restore(boolean waiting){pending=waiting;}
  boolean isPending(){return pending;}
  boolean takeAutoRecord(){boolean value=pending;pending=false;return value;}
  void stop(){pending=false;}
}
