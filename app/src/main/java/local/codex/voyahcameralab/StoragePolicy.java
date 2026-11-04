package local.codex.voyahcameralab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Volume pressure and file ownership rules, independent of Android/media APIs. */
final class StoragePolicy {
  static final long RESERVE_BYTES=128L*1024*1024;
  private static final Pattern OWN_NAME=Pattern.compile("sentry-[0-9]{13,17}-[0-9a-f]{32}-[0-9]{6}\\.mp4");
  interface SpaceProbe {long[] read() throws IOException;}

  static int percent(int value){return Math.max(50,Math.min(95,value));}

  static long nextStamp(long now,long previous){
    if(now<0||previous<0||now>99999999999999999L||previous>=99999999999999999L)
      throw new IllegalArgumentException("Invalid clip clock");
    return Math.max(now,previous+1);
  }

  static boolean hasSpace(long total,long available,int maximumPercent){
    if(total<=0||available<0||available>total)return false;
    int freePercent=100-percent(maximumPercent);
    // ceil(total * freePercent / 100), without overflowing on large volumes.
    long required=total/100*freePercent+(total%100*freePercent+99)/100;
    return available>=Math.max(RESERVE_BYTES,required);
  }

  static boolean isCompleted(File directory,File file){
    if(file==null||!OWN_NAME.matcher(file.getName()).matches()||!file.isFile())return false;
    try {
      File root=directory.getCanonicalFile();
      File absolute=file.getAbsoluteFile();
      // Reject every symlink, even one pointing to another file inside this directory.
      return root.equals(absolute.getParentFile().getCanonicalFile())
          &&new File(root,file.getName()).equals(file.getCanonicalFile());
    }catch(IOException failure){return false;}
  }

  static List<File> completedFiles(File directory) throws IOException {
    File[] files=directory.listFiles();
    if(files==null)throw new IOException("Recording directory unavailable");
    List<File> clips=new ArrayList<>();
    for(File file:files)if(isCompleted(directory,file))clips.add(file);
    clips.sort(Comparator.comparing(File::getName).reversed());
    return clips;
  }

  static void ensureSpace(File directory,File protectedFile,SpaceProbe probe,boolean cycle,int maximumPercent) throws IOException {
    long[] space=checkedSpace(probe);
    if(hasSpace(space[0],space[1],maximumPercent))return;
    if(!cycle)throw new IOException("Достигнут предел заполнения памяти; ролики сохранены без удаления");
    List<File> clips=completedFiles(directory);
    File newest=clips.isEmpty()?null:clips.get(0);
    File protectedPath=protectedFile==null?null:protectedFile.getCanonicalFile();
    // A finite snapshot prevents repeated deletion of newly created/current segments.
    for(int i=clips.size()-1;i>=0;i--){
      File candidate=clips.get(i);
      if(candidate.equals(newest)||candidate.getCanonicalFile().equals(protectedPath))continue;
      if(!isCompleted(directory,candidate)||!candidate.delete())continue;
      space=checkedSpace(probe);
      if(hasSpace(space[0],space[1],maximumPercent))return;
    }
    throw new IOException("Недостаточно места: старые ролики приложения не освобождают нужный объём");
  }

  private static long[] checkedSpace(SpaceProbe probe) throws IOException {
    long[] result=probe.read();
    if(result==null||result.length<2||result[0]<=0||result[1]<0||result[1]>result[0])
      throw new IOException("Не удалось получить состояние накопителя");
    return result;
  }
}
