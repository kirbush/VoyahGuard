package local.codex.voyahcameralab;

/** Cuts only before a keyframe and rebases each independent MP4 timeline. */
final class SegmentPolicy {
  /** Hidden chunks of a recording; the owner only ever sees the joined video. */
  static final int CHUNK_SECONDS=15;
  private final long durationUs;
  private long firstUs=-1;
  private boolean finishing;
  SegmentPolicy(int seconds){durationUs=Math.max(1,seconds)*1000000L;}
  boolean accepts(boolean keyframe){return firstUs>=0||keyframe;}
  boolean rotate(long ptsUs,boolean keyframe){return !finishing&&firstUs>=0&&keyframe&&ptsUs-firstUs>=durationUs;}
  long timestamp(long ptsUs){if(firstUs<0)firstUs=ptsUs;return ptsUs-firstUs;}
  void next(){firstUs=-1;}
  void finish(){finishing=true;}
}
