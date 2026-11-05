package local.codex.voyahcameralab;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** A narrow read-only grant target; never exposes pending files or directory paths. */
public final class RecordingProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}

  private File resolve(Uri uri) throws FileNotFoundException {
    List<String> parts=uri.getPathSegments();
    if(!"content".equals(uri.getScheme())||!RecordingStore.AUTHORITY.equals(uri.getAuthority())
        ||parts.size()!=1||uri.getQuery()!=null||uri.getFragment()!=null)
      throw new FileNotFoundException("Invalid recording URI");
    String name=parts.get(0);
    if(name.contains("/")||name.contains("\\"))throw new FileNotFoundException("Invalid recording name");
    try {
      File directory=RecordingStore.directory(getContext()),file=new File(directory,name);
      if(!StoragePolicy.isCompleted(directory,file))throw new FileNotFoundException("Completed recording unavailable");
      return file;
    }catch(IOException failure){throw new FileNotFoundException(failure.getMessage());}
  }

  @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
    if(!"r".equals(mode))throw new SecurityException("Recordings are read-only");
    return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override public String getType(Uri uri){
    try{resolve(uri);return "video/mp4";}catch(FileNotFoundException failure){return null;}
  }

  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){
    File file;
    try{file=resolve(uri);}catch(FileNotFoundException failure){return null;}
    String[] columns=StoragePolicy.knownColumns(projection,OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE);
    MatrixCursor result=new MatrixCursor(columns);Object[] row=new Object[columns.length];
    for(int i=0;i<columns.length;i++){
      if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]=file.getName();
      else row[i]=file.length();
    }
    result.addRow(row);return result;
  }

  @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("Recordings are read-only");}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new SecurityException("Recordings are read-only");}
  @Override public int delete(Uri uri,String selection,String[] arguments){throw new SecurityException("Recordings are read-only");}
}
