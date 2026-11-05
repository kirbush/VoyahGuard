package local.codex.voyahcameralab;

import java.io.File;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Volume pressure and naming rules, independent of Android/media APIs. */
final class StoragePolicy {
  static final long RESERVE_BYTES=128L*1024*1024;
  static final String EVENT_SUFFIX="_event";
  private static final String VIDEO_TIME="yyyy-MM-dd_HH-mm-ss";
  private static final Pattern CHUNK=Pattern.compile("[0-9]{13}-[0-9]{6}\\.mp4");
  private static final Pattern SESSION=Pattern.compile("[0-9a-f]{32}");
  private static final Pattern LEGACY=Pattern.compile("sentry-[0-9]{13,17}-[0-9a-f]{32}-[0-9]{6}\\.mp4");
  interface SpaceProbe {long[] read() throws IOException;}
  /** Something storage pressure may take, in the order given. */
  interface Reclaimable {long bytes();boolean reclaim() throws IOException;}

  static int percent(int value){return Math.max(50,Math.min(95,value));}

  static long nextStamp(long now,long previous){
    if(now<0||previous<0||now>99999999999999999L||previous>=99999999999999999L)
      throw new IllegalArgumentException("Invalid clip clock");
    return Math.max(now,previous+1);
  }

  static long requiredBytes(long total,int maximumPercent){
    int freePercent=100-percent(maximumPercent);
    // ceil(total * freePercent / 100), without overflowing on large volumes.
    return Math.max(RESERVE_BYTES,total/100*freePercent+(total%100*freePercent+99)/100);
  }

  static boolean hasSpace(long total,long available,int maximumPercent){
    if(total<=0||available<0||available>total)return false;
    return available>=requiredBytes(total,maximumPercent);
  }

  static boolean isSession(String name){return SESSION.matcher(name).matches();}
  static boolean isChunk(String name){return CHUNK.matcher(name).matches();}
  static String chunkName(long wallMs,int sequence){return String.format(Locale.ROOT,"%013d-%06d.mp4",wallMs,sequence);}
  static long chunkWall(String name){return Long.parseLong(name.substring(0,13));}
  /** Files of the releases that kept one-minute clips in the app folder. */
  static boolean isLegacy(String name){return LEGACY.matcher(name).matches();}
  static String legacySession(String name){return name.split("-")[2];}
  static long legacyWall(String name){return Long.parseLong(name.split("-")[1]);}

  /** When the footage of a chunk or an old clip began. */
  static long wallOf(String name,long fallback){return isChunk(name)?chunkWall(name):isLegacy(name)?legacyWall(name):fallback;}

  /** Sorts by time in any file manager, and an event is visible from the name alone. */
  static String videoName(long wallMs,boolean event){
    return new SimpleDateFormat(VIDEO_TIME,Locale.ROOT).format(new Date(wallMs))+(event?EVENT_SUFFIX:"")+".mp4";
  }
  static long videoWall(String name,long fallback){
    Date time=new SimpleDateFormat(VIDEO_TIME,Locale.ROOT).parse(name,new ParsePosition(0));
    return time==null?fallback:time.getTime();
  }
  static boolean isEvent(String name){return name.endsWith(EVENT_SUFFIX+".mp4");}

  /** Oldest footage goes first, footage without an event before any event. */
  static List<Reclaimable> reclaimOrder(List<? extends Reclaimable> oldRecords,List<? extends Reclaimable> runningRecord,List<? extends Reclaimable> oldEvents,List<? extends Reclaimable> runningEvent){
    List<Reclaimable> order=new ArrayList<>(oldRecords);
    order.addAll(runningRecord);order.addAll(oldEvents);order.addAll(runningEvent);
    return order;
  }

  static void ensureSpace(SpaceProbe probe,boolean cycle,int maximumPercent,List<? extends Reclaimable> candidates) throws IOException {
    long[] space=checkedSpace(probe);
    if(hasSpace(space[0],space[1],maximumPercent))return;
    if(!cycle)throw new IOException("Достигнут предел заполнения памяти; видео сохранены без удаления");
    long freeable=0;
    for(Reclaimable candidate:candidates)freeable+=candidate.bytes();
    // Space taken by other apps must not cost the archive a deletion that cannot reach the target.
    if(space[1]+freeable<requiredBytes(space[0],maximumPercent))throw new IOException("Недостаточно места: видео приложения не освобождают нужный объём; они сохранены без удаления");
    for(Reclaimable candidate:candidates){
      if(!candidate.reclaim())continue;
      space=checkedSpace(probe);
      if(hasSpace(space[0],space[1],maximumPercent))return;
    }
    throw new IOException("Недостаточно места: видео приложения не освобождают нужный объём");
  }

  /** Chunks of one session in recording order. */
  static List<File> chunks(File directory) throws IOException {
    File[] files=directory.listFiles();
    if(files==null)throw new IOException("Recording directory unavailable");
    List<File> chunks=new ArrayList<>();
    for(File file:files)if(file.isFile()&&isChunk(file.getName()))chunks.add(file);
    File[] sorted=chunks.toArray(new File[0]);
    Arrays.sort(sorted);
    return new ArrayList<>(Arrays.asList(sorted));
  }

  private static long[] checkedSpace(SpaceProbe probe) throws IOException {
    long[] result=probe.read();
    if(result==null||result.length<2||result[0]<=0||result[1]<0||result[1]>result[0])
      throw new IOException("Не удалось получить состояние накопителя");
    return result;
  }
}
