package local.codex.voyahcameralab;

/** Cuts only before a keyframe and rebases each independent MP4 timeline. */
final class SegmentPolicy {
  private final long durationUs;
  private long firstUs=-1;
  private boolean finishing;
  SegmentPolicy(int seconds){durationUs=(seconds==180||seconds==300?seconds:60)*1000000L;}
  boolean accepts(boolean keyframe){return firstUs>=0||keyframe;}
  boolean rotate(long ptsUs,boolean keyframe){return !finishing&&firstUs>=0&&keyframe&&ptsUs-firstUs>=durationUs;}
  long timestamp(long ptsUs){if(firstUs<0)firstUs=ptsUs;return ptsUs-firstUs;}
  void next(){firstUs=-1;}
  void finish(){finishing=true;}
}
