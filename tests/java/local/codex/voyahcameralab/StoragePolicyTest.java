package local.codex.voyahcameralab;

public final class StoragePolicyTest {
  private static final long M=1024L*1024;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static final class Item implements StoragePolicy.Reclaimable {
    final long size;boolean gone;final long[] free;
    Item(long size,long[] free){this.size=size;this.free=free;}
    public long bytes(){return size;}
    public boolean reclaim(){gone=true;free[0]+=size;return true;}
  }
  static void expectStop(StoragePolicy.SpaceProbe probe,boolean cycle,java.util.List<Item> items){
    try{StoragePolicy.ensureSpace(probe,cycle,90,items);throw new AssertionError("Expected storage stop");}
    catch(java.io.IOException expected){}
  }
  static void space() throws Exception {
    long[] free={150*M};StoragePolicy.SpaceProbe probe=()->new long[]{2000*M,free[0]};
    Item a=new Item(60*M,free),b=new Item(60*M,free);
    expectStop(probe,false,java.util.Arrays.asList(a,b));
    check(!a.gone&&!b.gone,"Stop mode never deletes");
    StoragePolicy.ensureSpace(probe,true,90,java.util.Arrays.asList(a,b));
    check(a.gone&&!b.gone,"Cycle stops as soon as the target is reached");
    long[] tight={100*M};Item c=new Item(20*M,tight),d=new Item(20*M,tight);
    expectStop(()->new long[]{2000*M,tight[0]},true,java.util.Arrays.asList(c,d));
    check(!c.gone&&!d.gone,"Pressure from other apps never erases the archive for a target it cannot reach");
    expectStop(()->new long[]{0,0},true,java.util.Arrays.asList(c));
    check(!c.gone,"Unavailable volume statistics must not cause deletion");
    int[] probes={0};Item e=new Item(60*M,new long[1]),f=new Item(60*M,new long[1]);
    expectStop(()->{probes[0]++;return new long[]{2000*M,100*M};},true,java.util.Arrays.asList(e,f));
    check(e.gone&&f.gone&&probes[0]==3,"A deletion that frees less than promised stops bounded");
    java.util.List<StoragePolicy.Reclaimable> order=StoragePolicy.reclaimOrder(java.util.Arrays.asList(a),java.util.Arrays.asList(b),java.util.Arrays.asList(c),java.util.Arrays.asList(d));
    check(order.get(0)==a&&order.get(1)==b&&order.get(2)==c&&order.get(3)==d,"Footage without events goes before any event");
  }
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
    check(StoragePolicy.isChunk(StoragePolicy.chunkName(1762000000000L,7)),"Chunk names round-trip");
    check(StoragePolicy.chunkWall(StoragePolicy.chunkName(1762000000000L,7))==1762000000000L,"A chunk keeps its wall time for recovery");
    check(StoragePolicy.chunkName(1762000000000L,12).compareTo(StoragePolicy.chunkName(1762000000001L,3))<0,"Chunks sort by time");
    check(!StoragePolicy.isChunk("holiday.mp4")&&!StoragePolicy.isChunk("1762000000000-000001.mp4.part"),"Foreign and unfinished files are not chunks");
    check(StoragePolicy.isSession("0123456789abcdef0123456789abcdef")&&!StoragePolicy.isSession("../etc"),"Only session folders are recovered");
    String legacy="sentry-1762000000000-0123456789abcdef0123456789abcdef-000004.mp4";
    check(StoragePolicy.isLegacy(legacy)&&StoragePolicy.legacyWall(legacy)==1762000000000L&&StoragePolicy.legacySession(legacy).length()==32,"Clips of 0.6 are recognised");
    check(!StoragePolicy.isLegacy("family.mp4"),"A foreign file is never migrated");
    String event=StoragePolicy.videoName(1762000000000L,true),plain=StoragePolicy.videoName(1762000000000L,false);
    check(StoragePolicy.isEvent(event)&&!StoragePolicy.isEvent(plain),"An event is visible from the name");
    check(StoragePolicy.videoWall(event,0)==1762000000000L&&StoragePolicy.videoWall(plain,0)==1762000000000L,"The name gives the time of the footage back");
    check(StoragePolicy.videoWall("holiday.mp4",7)==7,"A foreign name keeps the stored time");
    check(StoragePolicy.wallOf(StoragePolicy.chunkName(1762000000000L,3),7)==1762000000000L&&StoragePolicy.wallOf(legacy,7)==1762000000000L&&StoragePolicy.wallOf("x",7)==7,"Sources carry their start time");
    check(plain.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}\\.mp4"),"Names sort by time");
    space();
    System.out.println("PASS: storage thresholds, physical reserve, invalid statistics and overflow");
  }
}
