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
  static void expectStop(File dir,StoragePolicy.SpaceProbe space,boolean cycle,File current) throws Exception {
    try{StoragePolicy.ensureSpace(dir,current,space,cycle,90);throw new AssertionError("Expected storage stop");}
    catch(IOException expected){}
  }
  public static void main(String[] args) throws Exception {
    Path workspace=Files.createTempDirectory("sentry-store-test-");
    try {
      File dir=Files.createDirectory(workspace.resolve("clips")).toFile();
      File old=clip(dir,1),middle=clip(dir,2),newest=clip(dir,3);
      File foreign=new File(dir,"family.mp4");foreign.createNewFile();
      File pending=new File(dir,old.getName()+".part");pending.createNewFile();
      check(StoragePolicy.completedFiles(dir).equals(Arrays.asList(newest,middle,old)),"List exposes only completed owned clips newest first");
      StoragePolicy.SpaceProbe pressure=()->new long[]{2000*M,old.exists()?150*M:210*M};
      expectStop(dir,pressure,false,null);
      check(old.exists()&&middle.exists()&&newest.exists(),"Stop mode never deletes");
      StoragePolicy.ensureSpace(dir,null,pressure,true,90);
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
      File bounded=Files.createDirectory(workspace.resolve("bounded")).toFile();
      File first=clip(bounded,1),second=clip(bounded,2),last=clip(bounded,3);
      int[] probes={0};
      expectStop(bounded,()->{probes[0]++;return new long[]{2000*M,100*M};},true,null);
      check(!first.exists()&&!second.exists()&&last.exists()&&probes[0]==3,"No space gained from old files produces a bounded stop and preserves newest");
      try {
        Path link=dir.toPath().resolve(outside.getName());Files.createSymbolicLink(link,outside.toPath());
        check(!StoragePolicy.isCompleted(dir,link.toFile()),"Symlink escapes cannot be shared or deleted");
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
