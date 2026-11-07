package local.codex.voyahcameralab;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Finished videos live in the shared Movies/Sentry folder through MediaStore, where the gallery
 * and any file manager find them without a permission. Hidden chunks of running recordings stay
 * in the app's own folder until they are joined.
 */
final class RecordingStore {
  static final String FOLDER=Environment.DIRECTORY_MOVIES+"/Sentry";
  private static final String[] COLUMNS={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_TAKEN,MediaStore.MediaColumns.DATE_ADDED};
  private static final String IN_FOLDER=MediaStore.MediaColumns.RELATIVE_PATH+"=?";
  private final Context context;
  private final ContentResolver resolver;
  private final SharedPreferences preferences;
  private final File workspace;

  static final class Snapshot {
    final long totalBytes,availableBytes,recordingBytes;
    final int clips,usedPercent;
    Snapshot(long total,long available,long bytes,int clips){
      totalBytes=total;availableBytes=available;recordingBytes=bytes;this.clips=clips;
      usedPercent=total<=0?100:(int)Math.min(100,Math.max(0,Math.ceil(100.0*(total-available)/total)));
    }
  }

  static final class Video implements StoragePolicy.Reclaimable {
    final Uri uri;final String name;final long size,wallMs;private final ContentResolver resolver;
    Video(ContentResolver resolver,Uri uri,String name,long size,long wallMs){this.resolver=resolver;this.uri=uri;this.name=name;this.size=size;this.wallMs=wallMs;}
    boolean event(){return StoragePolicy.isEvent(name);}
    boolean camera(){return StoragePolicy.isCamera(name);}
    @Override public long bytes(){return size;}
    @Override public boolean reclaim(){try{return resolver.delete(uri,null,null)>0;}catch(RuntimeException failure){return false;}}
  }

  RecordingStore(Context context) throws IOException {
    this.context=context.getApplicationContext();resolver=context.getContentResolver();
    preferences=context.getSharedPreferences("camera",Context.MODE_PRIVATE);workspace=workspace(context);
  }

  static File workspace(Context context) throws IOException {
    File directory=context.getExternalFilesDir("chunks");
    if(directory==null)throw new IOException("Папка видео приложения недоступна");
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать папку записей приложения");
    return directory;
  }

  static String newSession(){return UUID.randomUUID().toString().replace("-","");}

  File sessionDirectory(String session) throws IOException {
    File directory=new File(workspace,session);
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать папку записи");
    return directory;
  }

  File[] sessions(){File[] directories=workspace.listFiles(File::isDirectory);return directories==null?new File[0]:directories;}

  /** Newest first. */
  List<Video> list() throws IOException {
    List<Video> videos=new ArrayList<>();
    try(Cursor rows=resolver.query(collection(),COLUMNS,IN_FOLDER,new String[]{FOLDER+"/"},MediaStore.MediaColumns.DATE_ADDED+" DESC")){
      if(rows==null)throw new IOException("Список видео недоступен");
      while(rows.moveToNext()){
        long taken=rows.isNull(3)?0:rows.getLong(3);
        String name=rows.getString(1);
        // The name holds the time of the footage; the scanner and a late join may move the columns.
        videos.add(new Video(resolver,ContentUris.withAppendedId(collection(),rows.getLong(0)),name,rows.getLong(2),StoragePolicy.videoWall(name,taken>0?taken:rows.getLong(4)*1000)));
      }
    }catch(RuntimeException failure){throw new IOException("Список видео недоступен",failure);}
    videos.sort((a,b)->Long.compare(b.wallMs,a.wallMs));
    return videos;
  }

  Snapshot snapshot() throws IOException {
    long[] space=space();long bytes=0;List<Video> videos=list();
    for(Video video:videos)bytes+=video.size;
    return new Snapshot(space[0],space[1],bytes,videos.size());
  }

  /** A join copies before it deletes, so it needs real room, not room under the owner's limit. */
  long roomForCopy() throws IOException {return space()[1]-StoragePolicy.RESERVE_BYTES;}

  /**
   * Under pressure quiet footage goes first, then motion further away, then events, oldest first. The
   * newest finished video stays. The start of the running video counts as its oldest footage.
   */
  void ensureSpace(VideoSession running) throws IOException {
    long[] space=space();
    if(StoragePolicy.hasSpace(space[0],space[1],maximumPercent()))return;
    List<Video> records=new ArrayList<>(),cameras=new ArrayList<>(),events=new ArrayList<>();
    List<Video> videos=list();
    for(int i=videos.size()-1;i>0;i--)(videos.get(i).event()?events:videos.get(i).camera()?cameras:records).add(videos.get(i));
    List<StoragePolicy.Reclaimable> start=running==null?new ArrayList<>():running.heldStart();
    boolean event=running!=null&&running.inEvent(),near=event&&running.inNearEvent();
    List<StoragePolicy.Reclaimable> none=new ArrayList<>();
    StoragePolicy.ensureSpace(this::space,cycle(),maximumPercent(),StoragePolicy.reclaimOrder(records,event?none:start,cameras,event&&!near?start:none,events,near?start:none));
  }

  Uri createPending(String name,long wallMs) throws IOException {
    ContentValues values=new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
    values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH,FOLDER);
    values.put(MediaStore.MediaColumns.DATE_TAKEN,wallMs);
    values.put(MediaStore.MediaColumns.IS_PENDING,1);
    Uri uri;
    try{uri=resolver.insert(collection(),values);}catch(RuntimeException failure){throw new IOException("Не удалось создать видео в папке Movies/Sentry",failure);}
    if(uri==null)throw new IOException("Не удалось создать видео в папке Movies/Sentry");
    return uri;
  }

  ParcelFileDescriptor openPending(Uri uri) throws IOException {
    ParcelFileDescriptor file=resolver.openFileDescriptor(uri,"rw");
    if(file==null)throw new IOException("Видео недоступно для записи");
    return file;
  }

  void publishPending(Uri uri) throws IOException {
    ContentValues values=new ContentValues();
    values.put(MediaStore.MediaColumns.IS_PENDING,0);
    if(resolver.update(uri,values,null,null)<1)throw new IOException("Не удалось завершить видео");
  }

  void discardPending(Uri uri){try{resolver.delete(uri,null,null);}catch(RuntimeException ignored){}}

  /** Half-written entries of a run that was killed. Their chunks are joined again. */
  void dropAbandonedPending(){
    Bundle arguments=new Bundle();
    arguments.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,IN_FOLDER+" AND "+MediaStore.MediaColumns.IS_PENDING+"=1");
    arguments.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{FOLDER+"/"});
    arguments.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
    try(Cursor rows=resolver.query(collection(),new String[]{MediaStore.MediaColumns._ID},arguments,null)){
      while(rows!=null&&rows.moveToNext())discardPending(ContentUris.withAppendedId(collection(),rows.getLong(0)));
    }catch(RuntimeException ignored){}
  }

  /** The folder of 0.6 and earlier, emptied once into whole videos. */
  File legacyFolder(){
    File movies=context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
    return movies==null?null:new File(movies,"Sentry");
  }

  private static Uri collection(){return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);}
  private boolean cycle(){return !"stop".equals(preferences.getString("storage_policy","cycle"));}
  private int maximumPercent(){return StoragePolicy.percent(preferences.getInt("max_storage_percent",90));}
  private long[] space() throws IOException {
    try {
      StatFs stat=new StatFs(workspace.getPath());
      long total=stat.getTotalBytes(),available=stat.getAvailableBytes();
      if(total<=0||available<0||available>total)throw new IOException("Invalid recording volume statistics");
      return new long[]{total,available};
    }catch(IllegalArgumentException failure){throw new IOException("Recording volume unavailable",failure);}
  }
}
