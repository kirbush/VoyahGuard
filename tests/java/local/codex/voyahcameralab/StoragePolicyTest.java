package local.codex.voyahcameralab;

public final class StoragePolicyTest {
  private static final long M=1024L*1024;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args) throws Exception {
    check(StoragePolicy.nextStamp(1761000000000L,1762000000000L)==1762000000001L,"Clock rollback must not make the next recorded clip sort before previous clips");
    check(StoragePolicy.nextStamp(1763000000000L,1762000000000L)==1763000000000L,"Forward wall time remains recognizable in clip names");
    check(!StoragePolicy.hasSpace(1000*M,99*M,90),"Volume threshold includes other apps, not just recordings");
    check(StoragePolicy.hasSpace(2000*M,200*M,90),"Exact threshold is allowed when physical reserve is available");
    check(!StoragePolicy.hasSpace(500*M,127*M,95),"Physical reserve overrides percentage");
    check(!StoragePolicy.hasSpace(0,1000*M,90),"Unknown volume statistics fail closed");
    check(!StoragePolicy.hasSpace(1000*M,300*M,20),"Low corrupt percentage clamps to 50");
    check(!StoragePolicy.hasSpace(10000*M,400*M,100),"High corrupt percentage clamps to 95");
    check(StoragePolicy.hasSpace(Long.MAX_VALUE,Long.MAX_VALUE/2+1,50),"Large volumes do not overflow");
    System.out.println("PASS: storage thresholds, physical reserve, invalid statistics and overflow");
  }
}
