package net.elfradio.d31bootstrap;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import static org.junit.Assert.*;

public class FileOperationsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject run(String action,File source,File target)throws Exception {
        JSONObject p=new JSONObject().put("action",action).put("path",source.getAbsolutePath());
        if(target!=null)p.put("target",target.getAbsolutePath());
        return new JSONObject(FileOperations.run(temp.newFolder(),p,new HostFileAccess()).getString("output"));
    }
    @Test public void nestedCopyMoveAndRecoverableRemovalKeepOriginalBytes()throws Exception {
        File parent=temp.newFolder(),source=new File(parent,"源目录");source.mkdir();
        File child=new File(source,"引号'与空格.txt");Files.write(child.toPath(),new byte[]{1,2,3});
        File copy=new File(parent,"副本");run("copy",source,copy);
        assertArrayEquals(Files.readAllBytes(child.toPath()),Files.readAllBytes(new File(copy,child.getName()).toPath()));
        assertThrows(Exception.class,()->run("copy",source,copy));
        File renamed=new File(parent,"新名称");run("move",copy,renamed);assertFalse(copy.exists());
        JSONObject trashed=run("trash",renamed,null);File kept=new File(trashed.getString("path"));
        assertTrue(kept.isDirectory());assertFalse(renamed.exists());
        run("move",kept,renamed);assertTrue(new File(renamed,child.getName()).isFile());assertTrue(child.isFile());
    }
    @Test public void listPagesIncludeEmptyFilesAndLiteralNames()throws Exception {
        File dir=temp.newFolder();for(int i=0;i<51;i++)new File(dir,String.format("文件%02d",i)).createNewFile();
        JSONObject one=run("list",dir,null);assertEquals(51,one.getInt("total"));assertEquals(12,one.getInt("next"));
        JSONObject p=new JSONObject().put("action","list").put("path",dir.getAbsolutePath()).put("offset",40);
        JSONObject two=new JSONObject(FileOperations.run(temp.newFolder(),p,new HostFileAccess()).getString("output"));
        assertEquals(11,two.getJSONArray("entries").length());assertEquals(-1,two.getInt("next"));
        assertEquals(0,two.getJSONArray("entries").getJSONObject(0).getLong("bytes"));
    }
    @Test public void rejectsCopyIntoItselfAndCancellationDoesNotTouchSource()throws Exception {
        File dir=temp.newFolder();Files.write(new File(dir,"source").toPath(),new byte[]{4,5,6});
        assertThrows(Exception.class,()->run("copy",dir,new File(dir,"inside")));
        File job=temp.newFolder();new File(job,"cancel").createNewFile();File target=new File(temp.getRoot(),"copy-target");
        assertThrows(Exception.class,()->FileOperations.run(job,new JSONObject().put("action","copy").put("path",dir.getAbsolutePath()).put("target",target.getAbsolutePath()),new HostFileAccess()));
        assertTrue(new File(dir,"source").isFile());assertFalse(target.exists());
    }
    @Test public void pathsAreNotCommandsAndParentTraversalIsRejected()throws Exception {
        assertThrows(Exception.class,()->FileOperations.normalize(new JSONObject().put("action","list").put("path","/a/../b")));
        File root=temp.newFolder(),created=new File(root,"$name ' quote");run("mkdir",created,null);assertTrue(created.isDirectory());
    }
    private JSONObject params(String action,File source,File target)throws Exception {
        JSONObject p=new JSONObject().put("action",action).put("path",source.getAbsolutePath());
        if(target!=null)p.put("target",target.getAbsolutePath()).put("overwrite",true);
        return p;
    }
    private File data(File parent,String name,int value)throws Exception {
        File file=new File(parent,name);Files.write(file.toPath(),new byte[]{(byte)value});return file;
    }
    private void bytes(File file,int value)throws Exception { assertArrayEquals(new byte[]{(byte)value},Files.readAllBytes(file.toPath())); }
    @Test public void deleteRecursesAndDoesNotCreateTrashOrRemoveHistoricalSiblings()throws Exception {
        File root=temp.newFolder(),dir=new File(root,"delete");assertTrue(new File(dir,"nested/empty").mkdirs());
        data(new File(dir,"nested"),"data",7);data(dir,".hidden",8);File history=data(root,".elfremote-trash-history",9);
        JSONObject result=run("delete",dir,null);assertEquals(dir.getPath(),result.getString("path"));
        assertFalse(dir.exists());bytes(history,9);assertEquals(1,root.list().length);assertFalse(result.has("restore_to"));
    }
    @Test public void deletesLiveAndBrokenLinksWithoutTouchingTheirReferents()throws Exception {
        File root=temp.newFolder(),outside=temp.newFolder(),dir=new File(root,"tree");assertTrue(dir.mkdir());
        File kept=data(outside,"kept",7),missing=new File(outside,"missing");
        HostFileAccess.link(new File(dir,"directory-link"),outside);HostFileAccess.link(new File(dir,"file-link"),kept);
        HostFileAccess.link(new File(dir,"broken-link"),missing);HostFileAccess.link(new File(dir,"loop"),dir);
        run("delete",dir,null);bytes(kept,7);assertFalse(missing.exists());assertFalse(Files.exists(dir.toPath(),LinkOption.NOFOLLOW_LINKS));
        File broken=new File(root,"broken");HostFileAccess.link(broken,missing);run("delete",broken,null);
        assertFalse(Files.exists(broken.toPath(),LinkOption.NOFOLLOW_LINKS));
    }
    @Test public void overwritePreservesOriginalTargetForCopyAndMove()throws Exception {
        for(String action:new String[]{"copy","move"}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
            JSONObject result=FileOperations.run(job,params(action,source,target),new HostFileAccess());
            assertEquals("completed",result.getString("state"));assertEquals(0,result.getInt("exit_code"));assertFalse(result.getBoolean("truncated"));
            JSONObject output=new JSONObject(result.getString("output"));bytes(target,1);bytes(new File(output.getString("backup_path")),2);
            assertEquals("copy".equals(action),source.exists());
        }
    }
    @Test public void overwriteDirectoryReplacesInsteadOfMergingAndKeepsEmptyDirectories()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=new File(root,"source"),target=new File(root,"target");
        assertTrue(new File(source,"empty").mkdirs());assertTrue(target.mkdir());data(source,"new",1);data(target,"old",2);
        JSONObject out=new JSONObject(FileOperations.run(job,params("copy",source,target),new HostFileAccess()).getString("output"));
        assertTrue(new File(target,"empty").isDirectory());assertFalse(new File(target,"old").exists());bytes(new File(target,"new"),1);
        bytes(new File(out.getString("backup_path"),"old"),2);
    }
    @Test public void rejectsBothContainmentDirectionsSamePathAndHardLinkWithoutBackingUp()throws Exception {
        File root=temp.newFolder(),dir=new File(root,"dir");assertTrue(dir.mkdir());File child=data(dir,"child",4);
        for(String action:new String[]{"copy","move"}) {
            for(File[] pair:new File[][]{{dir,dir},{dir,new File(dir,"inside")},{child,dir}}) {
                assertThrows(IOException.class,()->FileOperations.run(temp.newFolder(),params(action,pair[0],pair[1]),new HostFileAccess()));
                bytes(child,4);assertEquals(1,root.list().length);
            }
        }
        File alias=new File(dir,"hard-link");Files.createLink(alias.toPath(),child.toPath());
        assertThrows(IOException.class,()->FileOperations.run(temp.newFolder(),params("copy",child,alias),new HostFileAccess()));bytes(child,4);bytes(alias,4);
    }
    @Test public void parentAliasCannotBypassContainmentAndCopyRejectsNestedLinks()throws Exception {
        File root=temp.newFolder(),source=new File(root,"source");assertTrue(source.mkdir());File child=data(source,"child",4),alias=new File(root,"alias");
        HostFileAccess.link(alias,source);
        assertThrows(IOException.class,()->FileOperations.run(temp.newFolder(),params("move",source,new File(alias,"inside")),new HostFileAccess()));
        File link=new File(source,"broken");HostFileAccess.link(link,new File(root,"missing"));File target=data(root,"target",8);
        assertThrows(IOException.class,()->FileOperations.run(temp.newFolder(),params("copy",source,target),new HostFileAccess()));
        bytes(child,4);bytes(target,8);assertTrue(Files.isSymbolicLink(link.toPath()));
    }
    @Test public void brokenOverwriteTargetIsBackedUpAsLinkNotFollowed()throws Exception {
        File root=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target"),missing=new File(root,"missing");
        HostFileAccess.link(target,missing);
        assertThrows(IOException.class,()->run("copy",source,target));
        JSONObject out=new JSONObject(FileOperations.run(temp.newFolder(),params("copy",source,target),new HostFileAccess()).getString("output"));
        File backup=new File(out.getString("backup_path"));assertTrue(Files.isSymbolicLink(backup.toPath()));
        assertEquals(missing.toPath(),Files.readSymbolicLink(backup.toPath()));bytes(target,1);assertFalse(missing.exists());
    }
    @Test public void copyAndMoveCommitFailuresRestoreOriginalTargetAndLeaveSource()throws Exception {
        for(String action:new String[]{"copy","move"}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
            HostFileAccess fs=new HostFileAccess(){@Override boolean rename(File from,File to)throws IOException{
                if(to.equals(target)&&!from.getName().startsWith(".elfremote-replaced-"))return false;
                return super.rename(from,to);
            }};
            assertThrows(IOException.class,()->FileOperations.run(job,params(action,source,target),fs));
            bytes(source,1);bytes(target,2);assertEquals(2,root.list().length);
        }
    }
    @Test public void failureDoesNotClobberAConcurrentTargetAndReportsRetainedBackup()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
        HostFileAccess fs=new HostFileAccess(){@Override boolean rename(File from,File to)throws IOException{
            if(from.equals(source)){try{Files.write(target.toPath(),new byte[]{3});}catch(IOException e){throw new AssertionError(e);}return false;}
            return super.rename(from,to);
        }};
        IOException error=assertThrows(IOException.class,()->FileOperations.run(job,params("move",source,target),fs));
        File backup=new File(root,".elfremote-replaced-"+job.getName()+"-target");
        assertTrue(error.getMessage().contains(backup.getPath()));bytes(backup,2);bytes(source,1);bytes(target,3);
    }
    @Test public void readFailureAfterPartialCopyRestoresTargetAndCleansOnlyOwnedStage()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
        HostFileAccess fs=new HostFileAccess(){@Override InputStream read(File file)throws IOException {
            InputStream in=super.read(file);return new FilterInputStream(in){int reads;
                @Override public int read(byte[] b,int off,int len)throws IOException {if(reads++>0)throw new IOException("注入读取失败");return super.read(b,off,len);}
            };
        }};
        IOException error=assertThrows(IOException.class,()->FileOperations.run(job,params("copy",source,target),fs));
        assertTrue(error.getMessage().contains("读取失败"));bytes(target,2);bytes(source,1);assertEquals(2,root.list().length);
        File stage=data(root,".elfremote-copy-"+job.getName(),9);
        assertThrows(IOException.class,()->FileOperations.run(job,params("copy",source,target),new HostFileAccess()));bytes(stage,9);bytes(target,2);
    }
    @Test public void existingBackupPreventsOverwriteAndHistoricalBackupsAreNeverCleaned()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
        File backup=data(root,".elfremote-replaced-"+job.getName()+"-target",3);
        assertThrows(IOException.class,()->FileOperations.run(job,params("move",source,target),new HostFileAccess()));
        bytes(source,1);bytes(target,2);bytes(backup,3);
    }
    @Test public void cancelledBeforeAnyMutationPreservesAllInputsIncludingEmptyFiles()throws Exception {
        for(String action:new String[]{"copy","move","delete","trash","mkdir"}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=new File(root,"source"),target=data(root,"target",2);
            if(!"mkdir".equals(action))assertTrue(source.createNewFile());assertTrue(new File(job,"cancel").createNewFile());
            assertThrows(IOException.class,()->FileOperations.run(job,params(action,source,target),new HostFileAccess()));
            assertEquals(!"mkdir".equals(action),source.exists());bytes(target,2);assertEquals("mkdir".equals(action)?1:2,root.list().length);
        }
    }
    @Test public void cancelAfterBackupAndDuringCopyRollsBackEvenWithCancellationStillSet()throws Exception {
        for(boolean duringRead:new boolean[]{false,true}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2),cancel=new File(job,"cancel");
            HostFileAccess fs=new HostFileAccess(){
                void cancel(){try{Files.write(cancel.toPath(),new byte[0]);}catch(IOException error){throw new AssertionError(error);}}
                @Override boolean rename(File from,File to)throws IOException{boolean moved=super.rename(from,to);if(moved&&from.equals(target)&&!duringRead)cancel();return moved;}
                @Override InputStream read(File file)throws IOException {InputStream in=super.read(file);if(duringRead)cancel();return in;}
            };
            assertThrows(IOException.class,()->FileOperations.run(job,params("copy",source,target),fs));
            assertTrue(cancel.exists());bytes(source,1);bytes(target,2);assertEquals(2,root.list().length);
        }
    }
    @Test public void interruptedThreadAndElapsedDeadlineStopBeforeMutation()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1);
        try {Thread.currentThread().interrupt();assertThrows(IOException.class,()->FileOperations.run(job,params("delete",source,null),new HostFileAccess()));}
        finally {Thread.interrupted();}
        bytes(source,1);
        HostFileAccess expired=new HostFileAccess(){int reads;@Override long now(){return reads++==0?0:120000000001L;}};
        assertThrows(IOException.class,()->FileOperations.run(job,params("delete",source,null),expired));bytes(source,1);
    }
    @Test public void listKeepsModifiedMillisecondsAndDoesNotInventUnavailablePermissions()throws Exception {
        File root=temp.newFolder(),source=data(root,"source",1);assertTrue(source.setLastModified(1600000000123L));
        HostFileAccess unavailable=new HostFileAccess(){@Override FileOperations.Info stat(File file)throws IOException {throw new IOException("属性读取失败");}};
        JSONObject item=new JSONObject(FileOperations.run(temp.newFolder(),params("list",root,null),unavailable).getString("output")).getJSONArray("entries").getJSONObject(0);
        assertEquals(source.lastModified(),item.getLong("modified_ms"));assertFalse(item.has("mode"));assertFalse(item.has("uid"));assertFalse(item.has("gid"));assertTrue(item.isNull("link"));
    }
    @Test public void pathAndOverwriteValidationRejectAmbiguousDestructiveRequests()throws Exception {
        for(String path:new String[]{"relative","/a/../b","/a/./b","/a\0b"})
            assertThrows(IOException.class,()->FileOperations.normalize(new JSONObject().put("action","delete").put("path",path)));
        File source=temp.newFile();JSONObject p=params("copy",source,new File(temp.getRoot(),"dest"));p.put("overwrite","true");
        assertThrows(IOException.class,()->FileOperations.normalize(p));
        assertThrows(IOException.class,()->FileOperations.run(temp.newFolder(),params("delete",temp.getRoot().toPath().getRoot().toFile(),null),new HostFileAccess()));
    }
}
