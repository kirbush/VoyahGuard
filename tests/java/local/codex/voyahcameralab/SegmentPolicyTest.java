package local.codex.voyahcameralab;

public final class SegmentPolicyTest {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    SegmentPolicy policy=new SegmentPolicy(60);
    check(!policy.accepts(false),"New clip cannot start with a dependent frame");
    check(policy.accepts(true),"New clip accepts a keyframe");
    check(policy.timestamp(9000000)==0,"Clip timestamp starts at zero");
    check(!policy.rotate(68999999,true),"Do not rotate before 60 seconds from the first sample");
    check(!policy.rotate(69000000,false),"Duration alone cannot cut on a dependent frame");
    check(policy.rotate(70000000,true),"First keyframe after duration starts the next clip");
    policy.next();check(!policy.accepts(false),"Every new clip waits for a keyframe");
    check(policy.timestamp(70000000)==0&&policy.timestamp(70033333)==33333,"Each clip has its own normalized clock");
    SegmentPolicy three=new SegmentPolicy(180);three.timestamp(1);
    check(!three.rotate(60000001,true)&&three.rotate(180000001,true),"The requested length is honored");
    SegmentPolicy chunk=new SegmentPolicy(SegmentPolicy.CHUNK_SECONDS);chunk.timestamp(0);
    check(!chunk.rotate(14999999,true)&&chunk.rotate(15000000,true),"Hidden chunks use their own length");
    SegmentPolicy stopping=new SegmentPolicy(60);stopping.timestamp(5000000);stopping.finish();
    check(!stopping.rotate(65000000,true),"Storage stop/EOS keeps buffered keyframes in the current clip rather than opening another file");
    check(stopping.timestamp(65000000)==60000000,"Final buffered frames preserve the current clip timeline");
    System.out.println("PASS: segment keyframes, duration and independent clip timestamps");
  }
}
