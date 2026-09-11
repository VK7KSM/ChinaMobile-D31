package net.elfradio.d31bootstrap;
import org.json.JSONObject;import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.file.*;
public class FileSnapshotTest{
 private void clean(Path dir)throws Exception{try(java.util.stream.Stream<Path> p=Files.walk(dir)){for(Path f:p.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(f);}}
 @Test public void immutableCopyRecoveryAndCancellationPreserveSource()throws Exception{
  Path dir=Files.createTempDirectory("snapshot");try{
   File source=dir.resolve("original.bin").toFile(),target=dir.resolve("copy.bin").toFile(),job=dir.resolve("job").toFile();job.mkdir();Files.write(source.toPath(),new byte[]{1,2,3});
   JSONObject p=new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0),r=FileSnapshot.run(job,p,new HostFileAccess());assertEquals("snapshot",r.getString("action"));assertEquals(RescueFiles.sha256(source),r.getString("sha256"));
   Files.write(source.toPath(),new byte[]{9});assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(target.toPath()));assertNotNull(FileSnapshot.recover(job,new HostFileAccess()));
   Files.write(target.toPath(),new byte[]{4});assertNull(FileSnapshot.recover(job,new HostFileAccess()));target.delete();new File(job,"cancel").createNewFile();
   try{FileSnapshot.run(job,p,new HostFileAccess());fail();}catch(IOException expected){}assertArrayEquals(new byte[]{9},Files.readAllBytes(source.toPath()));assertFalse(target.exists());assertFalse(new File(target.getPath()+".tmp").exists());
  }finally{clean(dir);}
 }
 @Test public void oversizedSparseFileRejectedBeforeCopy()throws Exception{
  Path dir=Files.createTempDirectory("snapshot-size");try{File source=dir.resolve("large").toFile();try(RandomAccessFile f=new RandomAccessFile(source,"rw")){f.setLength(FileCommit.MAX_BYTES+1);}
   try{FileSnapshot.run(dir.toFile(),new JSONObject().put("source",source.getPath()).put("target",dir.resolve("copy").toString()),new HostFileAccess());fail();}catch(IOException expected){}assertFalse(Files.exists(dir.resolve("copy")));
  }finally{clean(dir);}
 }
 @Test public void sourceTargetAndTempLinksAreRejectedWithoutTouchingReferents()throws Exception{
  Path dir=Files.createTempDirectory("snapshot-links");try{
   File source=dir.resolve("source").toFile(),target=dir.resolve("target").toFile(),temp=dir.resolve("target.tmp").toFile(),missing=dir.resolve("missing").toFile();Files.write(source.toPath(),new byte[]{7});
   File link=dir.resolve("source-link").toFile();HostFileAccess.link(link,source);
   JSONObject p=new JSONObject().put("source",link.getPath()).put("target",target.getPath()).put("uid",0);
   assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,new HostFileAccess()));p.put("source",source.getPath());
   HostFileAccess.link(target,missing);assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,new HostFileAccess()));assertTrue(Files.isSymbolicLink(target.toPath()));Files.delete(target.toPath());
   HostFileAccess.link(temp,missing);assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,new HostFileAccess()));assertTrue(Files.isSymbolicLink(temp.toPath()));
   assertFalse(missing.exists());assertArrayEquals(new byte[]{7},Files.readAllBytes(source.toPath()));
  }finally{clean(dir);}
 }
 @Test public void emptySnapshotCancellationAndCommitFailureNeverPublishOrDeleteOldTemp()throws Exception{
  Path dir=Files.createTempDirectory("snapshot-cancel");try{
   File source=dir.resolve("source").toFile(),target=dir.resolve("target").toFile(),temp=dir.resolve("target.tmp").toFile(),cancel=dir.resolve("cancel").toFile();Files.write(source.toPath(),new byte[0]);
   JSONObject p=new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0);
   HostFileAccess cancelling=new HostFileAccess(){@Override void protect(File file,int uid){try{Files.write(cancel.toPath(),new byte[0]);}catch(IOException e){throw new AssertionError(e);}}};
   assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,cancelling));assertFalse(target.exists());assertFalse(temp.exists());assertEquals(0,source.length());Files.delete(cancel.toPath());
   HostFileAccess failing=new HostFileAccess(){@Override boolean rename(File from,File to){return false;}};
   assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,failing));assertNull(FileSnapshot.recover(dir.toFile(),new HostFileAccess()));assertFalse(temp.exists());
   Files.write(temp.toPath(),new byte[]{9});assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,new HostFileAccess()));assertArrayEquals(new byte[]{9},Files.readAllBytes(temp.toPath()));
  }finally{clean(dir);}
 }
 @Test public void concurrentSnapshotTargetAndSourceReplacementAreNotAccepted()throws Exception{
  Path dir=Files.createTempDirectory("snapshot-race");try{
   File source=dir.resolve("source").toFile(),target=dir.resolve("target").toFile();Files.write(source.toPath(),new byte[]{1});
   JSONObject p=new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0);
   HostFileAccess occupied=new HostFileAccess(){@Override void protect(File file,int uid){try{Files.write(target.toPath(),new byte[]{9});}catch(IOException e){throw new AssertionError(e);}}};
   assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,occupied));assertArrayEquals(new byte[]{9},Files.readAllBytes(target.toPath()));Files.delete(target.toPath());
   HostFileAccess changed=new HostFileAccess(){@Override InputStream read(File file)throws IOException{
    InputStream in=super.read(file);return new FilterInputStream(in){@Override public void close()throws IOException{super.close();Path replacement=dir.resolve("replacement");Files.write(replacement,new byte[]{2});Files.setLastModifiedTime(replacement,Files.getLastModifiedTime(source.toPath()));Files.move(replacement,source.toPath(),StandardCopyOption.REPLACE_EXISTING);
     // Windows保留替换路径的创建时间，显式改变桌面身份标记；Android使用真实设备号和inode。
     Files.setAttribute(source.toPath(),"basic:creationTime",java.nio.file.attribute.FileTime.fromMillis(1000000000000L));
    }};
   }};
   assertThrows(IOException.class,()->FileSnapshot.run(dir.toFile(),p,changed));assertFalse(target.exists());assertArrayEquals(new byte[]{2},Files.readAllBytes(source.toPath()));
  }finally{clean(dir);}
 }
 @Test public void recoveryRejectsAValidContentSymlink()throws Exception{
  Path dir=Files.createTempDirectory("snapshot-recover-link");try{
   File source=dir.resolve("source").toFile(),target=dir.resolve("target").toFile();Files.write(source.toPath(),new byte[]{1});
   FileSnapshot.run(dir.toFile(),new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0),new HostFileAccess());
   Files.delete(target.toPath());HostFileAccess.link(target,source);assertNull(FileSnapshot.recover(dir.toFile(),new HostFileAccess()));assertArrayEquals(new byte[]{1},Files.readAllBytes(source.toPath()));
  }finally{clean(dir);}
 }
}
