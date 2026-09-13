package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FileCreateFailureTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject request(File from,File to)throws Exception {
        return new JSONObject().put("action","copy").put("path",from.getAbsolutePath()).put("target",to.getAbsolutePath()).put("overwrite",true);
    }
    private class FailingAccess extends HostFileAccess {
        final boolean replace,unknown;
        FileOperations.Info observed;
        File created;
        int removes;
        FailingAccess(boolean replace,boolean unknown){this.replace=replace;this.unknown=unknown;}
        @Override FileOutputStream create(File file)throws IOException {
            try(FileOutputStream output=super.create(file)){output.write(7);}
            created=file;observed=unknown?null:stat(file);
            if(replace){Files.move(file.toPath(),new File(file.getParentFile(),file.getName()+"-original").toPath());Files.write(file.toPath(),new byte[]{9,8,7});}
            throw new FileOperations.CreatedFileException(file,observed,new IOException("模拟dup失败"));
        }
        @Override void unlink(File file)throws IOException {removes++;super.unlink(file);}
    }
    @Test public void retainedSingleFileReportsIdentityAndNewTaskCanRetry()throws Exception {
        File root=temp.newFolder(),from=new File(root,"source"),to=new File(root,"target"),job=temp.newFolder();
        Files.write(from.toPath(),new byte[]{1,2,3});FailingAccess access=new FailingAccess(false,false);
        FileOperations.CreatedFileException failure=assertThrows(FileOperations.CreatedFileException.class,()->FileOperations.run(job,request(from,to),access));
        File stage=new File(root,".elfremote-copy-"+job.getName());assertEquals(stage,failure.createdPath);assertEquals(stage,failure.retainedRoot);
        assertSame(access.observed,failure.createdInfo);assertTrue(failure.getMessage().contains(stage.getPath()));assertEquals(0,access.removes);
        assertTrue(stage.isFile());assertFalse(to.exists());
        IOException repeated=assertThrows(IOException.class,()->FileOperations.run(job,request(from,to),new HostFileAccess()));
        assertTrue(repeated.getMessage().contains("此前复制现场"));assertArrayEquals(new byte[]{7},Files.readAllBytes(stage.toPath()));
        FileOperations.run(temp.newFolder(),request(from,to),new HostFileAccess());
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(to.toPath()));assertTrue(stage.exists());
    }
    @Test public void replacedCreatedPathIsRetainedWithoutUnlink()throws Exception {
        File root=temp.newFolder(),from=new File(root,"source"),to=new File(root,"target");Files.write(from.toPath(),new byte[]{1});
        FailingAccess access=new FailingAccess(true,false);
        FileOperations.CreatedFileException failure=assertThrows(FileOperations.CreatedFileException.class,()->FileOperations.run(temp.newFolder(),request(from,to),access));
        assertSame(access.observed,failure.createdInfo);assertArrayEquals(new byte[]{9,8,7},Files.readAllBytes(access.created.toPath()));
        assertEquals(0,access.removes);assertFalse(to.exists());assertArrayEquals(new byte[]{1},Files.readAllBytes(from.toPath()));
    }
    @Test public void nestedFailureDoesNotRecursivelyDeleteAncestorStage()throws Exception {
        File root=temp.newFolder(),from=new File(root,"source"),sub=new File(from,"sub"),to=new File(root,"target"),job=temp.newFolder();
        assertTrue(sub.mkdirs());Files.write(new File(sub,"child").toPath(),new byte[]{1});FailingAccess access=new FailingAccess(true,false);
        FileOperations.CreatedFileException failure=assertThrows(FileOperations.CreatedFileException.class,()->FileOperations.run(job,request(from,to),access));
        File stage=new File(root,".elfremote-copy-"+job.getName());assertEquals(stage,failure.retainedRoot);assertTrue(stage.isDirectory());
        assertArrayEquals(new byte[]{9,8,7},Files.readAllBytes(new File(stage,"sub/child").toPath()));assertEquals(0,access.removes);
        assertArrayEquals(new byte[]{1},Files.readAllBytes(new File(sub,"child").toPath()));assertFalse(to.exists());
    }
    @Test public void overwriteRestoresOriginalAndUnknownInodeIsNotInvented()throws Exception {
        File root=temp.newFolder(),from=new File(root,"source"),to=new File(root,"target"),job=temp.newFolder();
        Files.write(from.toPath(),new byte[]{1});Files.write(to.toPath(),new byte[]{4,5});FailingAccess access=new FailingAccess(false,true);
        FileOperations.CreatedFileException failure=assertThrows(FileOperations.CreatedFileException.class,()->FileOperations.run(job,request(from,to),access));
        assertNull(failure.createdInfo);assertTrue(failure.getMessage().contains("创建身份：未知"));
        assertArrayEquals(new byte[]{4,5},Files.readAllBytes(to.toPath()));assertTrue(failure.retainedRoot.isFile());
        assertFalse(new File(root,".elfremote-replaced-"+job.getName()+"-target").exists());
    }
}
