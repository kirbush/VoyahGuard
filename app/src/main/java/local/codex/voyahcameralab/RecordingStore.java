package local.codex.voyahcameralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Owns only Sentry files in this application's external-files directory. */
final class RecordingStore {
  static final String AUTHORITY="local.codex.voyahcameralab.recordings";
  private final File directory;
  private final SharedPreferences preferences;
  private File latestCompleted;
  private String activeSession;

  static final class Snapshot {
    final long totalBytes,availableBytes,recordingBytes;
    final int clips,usedPercent;
    Snapshot(long total,long available,long bytes,int clips){
      totalBytes=total;availableBytes=available;recordingBytes=bytes;this.clips=clips;
      usedPercent=total<=0?100:(int)Math.min(100,Math.max(0,Math.ceil(100.0*(total-available)/total)));
    }
  }

  static final class Pending {
    final File file,completed;
    Pending(File file,File completed){this.file=file;this.completed=completed;}
  }

  RecordingStore(Context context) throws IOException {
    directory=directory(context);
    preferences=context.getSharedPreferences("camera",Context.MODE_PRIVATE);
  }

  /** A recorder names its session so that its own fragments never count as abandoned. */
  RecordingStore(Context context,String session) throws IOException {
    this(context);activeSession=session;
  }

  static File directory(Context context) throws IOException {
    File movies=context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
    if(movies==null)throw new IOException("Папка видео приложения недоступна");
    File directory=new File(movies,"Sentry");
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать папку записей приложения");
    // Do not follow a substituted Sentry directory outside the app's movies folder.
    if(!new File(movies.getCanonicalFile(),"Sentry").equals(directory.getCanonicalFile()))
      throw new IOException("Unsafe recording directory");
    return directory.getCanonicalFile();
  }

  synchronized List<File> list() throws IOException {return StoragePolicy.completedFiles(directory);}

  static Uri uri(Context context,File file) throws IOException {
    if(!StoragePolicy.isCompleted(directory(context),file))throw new IOException("Not a completed Sentry clip");
    return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName()).build();
  }

  synchronized Snapshot snapshot() throws IOException {
    long[] space=space();long bytes=0;List<File> clips=list();
    for(File clip:clips)bytes+=clip.length();
    return new Snapshot(space[0],space[1],bytes,clips.size());
  }

  synchronized void ensureSpace(File protectedFile) throws IOException {
    StoragePolicy.ensureSpace(directory,protectedFile==null?latestCompleted:protectedFile,this::space,cycle(),maximumPercent(),activeSession,System.currentTimeMillis());
  }

  /** Enforce pressure during long clips too; only older completed clips are eligible. */
  synchronized void checkDuringCapture() throws IOException {
    ensureSpace(latestCompleted);
  }

  synchronized Pending createPending(String session,int sequence) throws IOException {
    ensureSpace(null);
    if(!session.matches("[0-9a-f]{32}")||sequence<1||sequence>999999)throw new IOException("Invalid clip sequence");
    long previous=preferences.getLong("last_clip_stamp",0);
    List<File> existing=list();
    if(!existing.isEmpty())previous=Math.max(previous,Long.parseLong(existing.get(0).getName().split("-")[1]));
    long stamp=StoragePolicy.nextStamp(System.currentTimeMillis(),previous);
    if(!preferences.edit().putLong("last_clip_stamp",stamp).commit())throw new IOException("Не удалось сохранить порядок видеозаписей");
    String name=String.format(Locale.US,"sentry-%013d-%s-%06d.mp4",stamp,session,sequence);
    File completed=new File(directory,name),pending=new File(directory,name+".part");
    if(completed.exists()||!pending.createNewFile())throw new IOException("Не удалось создать новый видеофрагмент");
    return new Pending(pending,completed);
  }

  synchronized File complete(Pending pending) throws IOException {
    if(!directory.equals(pending.file.getCanonicalFile().getParentFile())||pending.completed.exists()
        ||!pending.file.renameTo(pending.completed))throw new IOException("Не удалось завершить видеофрагмент");
    latestCompleted=pending.completed;
    return pending.completed;
  }

  synchronized void discard(Pending pending){
    if(pending==null)return;
    try{if(directory.equals(pending.file.getCanonicalFile().getParentFile())&&pending.file.getName().endsWith(".mp4.part"))pending.file.delete();}
    catch(IOException ignored){}
  }

  static String newSession(){return UUID.randomUUID().toString().replace("-","");}
  private boolean cycle(){return !"stop".equals(preferences.getString("storage_policy","cycle"));}
  private int maximumPercent(){return StoragePolicy.percent(preferences.getInt("max_storage_percent",90));}
  private long[] space() throws IOException {
    try {
      StatFs stat=new StatFs(directory.getPath());
      long total=stat.getTotalBytes(),available=stat.getAvailableBytes();
      if(total<=0||available<0||available>total)throw new IOException("Invalid recording volume statistics");
      return new long[]{total,available};
    }catch(IllegalArgumentException failure){throw new IOException("Recording volume unavailable",failure);}
  }
}
