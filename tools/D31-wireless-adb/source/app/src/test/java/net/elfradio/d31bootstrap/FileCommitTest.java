package net.elfradio.d31bootstrap;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import static org.junit.Assert.*;

public class FileCommitTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject params(File source,File target)throws Exception{return new JSONObject().put("source",source.getPath()).put("path",target.getPath()).put("size",source.length()).put("sha256",RescueFiles.sha256(source));}
    @Test public void preservesOriginalAndVerifiesNewFile()throws Exception{
        File source=temp.newFile(),target=temp.newFile(),job=temp.newFolder();RescueFiles.write(source,"new content");RescueFiles.write(target,"original");
        JSONObject p=params(source,target);
        assertThrows(IOException.class,()->FileCommit.run(job,p));assertEquals("original",RescueFiles.read(target,100));
        JSONObject r=FileCommit.run(job,p.put("overwrite",true));assertEquals("committed",r.getString("action"));
        assertEquals("new content",RescueFiles.read(target,100));assertEquals("original",RescueFiles.read(new File(target.getPath()+".elfremote-"+job.getName()+".bak"),100));
        assertEquals("committed",FileCommit.recover(job).getString("action"));
    }
    @Test public void badHashAndCancellationNeverReplaceOriginal()throws Exception{
        File source=temp.newFile(),target=temp.newFile(),job=temp.newFolder();RescueFiles.write(source,"new");RescueFiles.write(target,"old");
        JSONObject p=params(source,target).put("overwrite",true).put("sha256",new String(new char[64]).replace('\0','0'));
        assertThrows(IOException.class,()->FileCommit.run(job,p));assertEquals("old",RescueFiles.read(target,100));
        RescueFiles.write(new File(job,"cancel"),"cancel");p.put("sha256",RescueFiles.sha256(source));
        assertThrows(IOException.class,()->FileCommit.run(job,p));assertEquals("old",RescueFiles.read(target,100));
    }
    @Test public void crashBetweenRenamesRestoresOriginal()throws Exception{
        File job=temp.newFolder(),target=new File(temp.getRoot(),"target"),backup=temp.newFile(),partial=temp.newFile();RescueFiles.write(backup,"old");
        JSONObject j=new JSONObject().put("target",target.getPath()).put("backup",backup.getPath()).put("temp",partial.getPath()).put("stage","prepared").put("size",3).put("sha256","unused");
        RescueFiles.write(new File(job,"file-commit.json"),j.toString());assertNull(FileCommit.recover(job));assertEquals("old",RescueFiles.read(target,100));assertFalse(partial.exists());
    }
    @Test public void hashesSegmentsBeyondTwoGigabytesWithoutIntegerOverflow()throws Exception{
        File f=temp.newFile();try(RandomAccessFile out=new RandomAccessFile(f,"rw")){long start=2147483650L;out.seek(start);out.write(new byte[]{1,2,3});assertEquals(FileCommit.hex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3})),RemoteFileDownload.hash(out,start,3));}
    }
}
