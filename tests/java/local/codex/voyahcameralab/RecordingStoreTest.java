package local.codex.voyahcameralab;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exercises real filesystem ownership/retention used by RecordingStore, without Android stubs. */
public final class RecordingStoreTest {
  static final long M=1024L*1024;
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static File clip(File dir,int n) throws IOException {
    File file=new File(dir,"sentry-176000000000"+n+"-0123456789abcdef0123456789abcdef-000001.mp4");
    if(!file.createNewFile())throw new IOException("fixture exists");return file;
  }
  static final String SESSION="fedcba9876543210fedcba9876543210";
  static final long NOW=1760000000000L;
  /** Sparse, so a fixture can occupy real length without using the disk. */
  static File sized(File file,long bytes) throws IOException {
    try(RandomAccessFile body=new RandomAccessFile(file,"rw")){body.setLength(bytes);}
    return file;
  }
  static File part(File dir,String session,int n,long age,long bytes) throws IOException {
    File file=new File(dir,"sentry-176000000000"+n+"-"+session+"-000001.mp4.part");
    if(!file.createNewFile()||!sized(file,bytes).setLastModified(NOW-age))throw new IOException("fixture part");
    return file;
  }
  static void ensure(File dir,File current,StoragePolicy.SpaceProbe space,boolean cycle) throws IOException {
    StoragePolicy.ensureSpace(dir,current,space,cycle,90,SESSION,NOW);
  }
  static void expectStop(File dir,StoragePolicy.SpaceProbe space,boolean cycle,File current) throws Exception {
    try{ensure(dir,current,space,cycle);throw new AssertionError("Expected storage stop");}
    catch(IOException expected){}
  }
  public static void main(String[] args) throws Exception {
    Path workspace=Files.createTempDirectory("sentry-store-test-");
    try {
      File dir=Files.createDirectory(workspace.resolve("clips")).toFile();
      File old=sized(clip(dir,1),60*M),middle=clip(dir,2),newest=clip(dir,3);
      File foreign=new File(dir,"family.mp4");foreign.createNewFile();
      File pending=new File(dir,old.getName()+".part");pending.createNewFile();
      check(StoragePolicy.completedFiles(dir).equals(Arrays.asList(newest,middle,old)),"List exposes only completed owned clips newest first");
      StoragePolicy.SpaceProbe pressure=()->new long[]{2000*M,old.exists()?150*M:210*M};
      expectStop(dir,pressure,false,null);
      check(old.exists()&&middle.exists()&&newest.exists(),"Stop mode never deletes");
      ensure(dir,null,pressure,true);
      check(!old.exists()&&middle.exists()&&newest.exists()&&foreign.exists()&&pending.exists(),"Cycle deletes only oldest eligible completed clip");
      expectStop(dir,()->new long[]{2000*M,100*M},true,middle);
      check(middle.exists()&&newest.exists(),"Current and newest clips survive even if target is impossible");
      check(!StoragePolicy.isCompleted(dir,new File(dir,"../family.mp4")),"Traversal is rejected");
      File outside=clip(workspace.toFile(),4);
      check(!StoragePolicy.isCompleted(dir,new File(dir,"../"+outside.getName())),"Owned-looking path outside the directory is rejected");
      File invalidDir=Files.createDirectory(workspace.resolve("invalid-stats")).toFile();
      File invalidOld=clip(invalidDir,1);clip(invalidDir,2);
      expectStop(invalidDir,()->new long[]{0,0},true,null);
      check(invalidOld.exists(),"Unavailable volume statistics must not cause deletion");
      File hopeless=Files.createDirectory(workspace.resolve("hopeless")).toFile();
      File kept1=sized(clip(hopeless,1),20*M),kept2=sized(clip(hopeless,2),20*M),kept3=clip(hopeless,3);
      expectStop(hopeless,()->new long[]{2000*M,100*M},true,null);
      check(kept1.exists()&&kept2.exists()&&kept3.exists(),"Pressure from other apps never erases the archive for a target it cannot reach");
      File bounded=Files.createDirectory(workspace.resolve("bounded")).toFile();
      File first=sized(clip(bounded,1),60*M),second=sized(clip(bounded,2),60*M),last=clip(bounded,3);
      int[] probes={0};
      expectStop(bounded,()->{probes[0]++;return new long[]{2000*M,100*M};},true,null);
      check(!first.exists()&&!second.exists()&&last.exists()&&probes[0]==3,"A deletion that frees less than promised stops bounded and preserves newest");
      File parts=Files.createDirectory(workspace.resolve("parts")).toFile();
      File recent=clip(parts,1),latest=clip(parts,2);
      File stale=part(parts,"0123456789abcdef0123456789abcdef",3,StoragePolicy.ABANDONED_PART_MILLIS,60*M);
      File own=part(parts,SESSION,4,StoragePolicy.ABANDONED_PART_MILLIS*2,0);
      File live=part(parts,"0123456789abcdef0123456789abcdef",5,1000,0);
      File stranger=new File(parts,"holiday.mp4.part");stranger.createNewFile();stranger.setLastModified(NOW-StoragePolicy.ABANDONED_PART_MILLIS*2);
      check(StoragePolicy.abandonedParts(parts,SESSION,NOW).equals(Arrays.asList(stale)),"Only old fragments of other sessions count as abandoned");
      expectStop(parts,()->new long[]{2000*M,stale.exists()?150*M:210*M},false,null);
      check(stale.exists(),"Stop mode deletes nothing, abandoned fragments included");
      ensure(parts,null,()->new long[]{2000*M,stale.exists()?150*M:210*M},true);
      check(!stale.exists()&&recent.exists()&&latest.exists(),"An unplayable fragment goes before any playable clip");
      check(own.exists()&&live.exists()&&stranger.exists(),"The running session, a fresh fragment and foreign names survive");
      try {
        Path link=dir.toPath().resolve(outside.getName());Files.createSymbolicLink(link,outside.toPath());
        // Windows JVMs do not resolve links in getCanonicalFile; Android and the Linux CI host do.
        if(System.getProperty("os.name").startsWith("Windows"))System.out.println("SKIP: symlink canonicalization differs on Windows hosts");
        else check(!StoragePolicy.isCompleted(dir,link.toFile()),"Symlink escapes cannot be shared or deleted");
      } catch(UnsupportedOperationException|java.nio.file.FileSystemException unavailable) {
        System.out.println("SKIP: symlink creation unavailable on this host");
      }
      System.out.println("PASS: owned files, bounded cycle deletion, stop mode and protected clips");
    } finally {
      try(java.util.stream.Stream<Path> paths=Files.walk(workspace)){
        paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(IOException failure){throw new UncheckedIOException(failure);}});
      }
    }
  }
}
