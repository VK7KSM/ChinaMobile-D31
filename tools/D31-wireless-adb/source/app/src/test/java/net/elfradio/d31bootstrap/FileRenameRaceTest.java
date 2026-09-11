package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 文件提交竞态、部分发布恢复及兼容分支回归。 */
public class FileRenameRaceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    private static class CommitRaceAccess extends HostFileAccess {
        final File destination;
        boolean injected,lastTargetWasMissing;
        CommitRaceAccess(File destination) {
            this.destination=destination;
        }
        @Override FileOperations.Info stat(File file)throws IOException {
            FileOperations.Info info=super.stat(file);
            if(file.equals(destination))lastTargetWasMissing=info==null;
            return info;
        }
        @Override boolean rename(File source,File target)throws IOException {
                if(target.equals(destination)&&!injected) {
                    assertTrue("必须在最后一次目标不存在检查之后注入",lastTargetWasMissing);
                    assertFalse(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS));
                    Files.write(target.toPath(),new byte[]{9},StandardOpenOption.CREATE_NEW);
                    injected=true;
                }
                return super.rename(source,target);
        }
    }
    private JSONObject task(String action,File source,File target,boolean overwrite)throws Exception {
        return new JSONObject().put("action",action).put("path",source.getPath())
                .put("target",target.getPath()).put("overwrite",overwrite);
    }
    private File data(File parent,String name,int value)throws IOException {
        File file=new File(parent,name);Files.write(file.toPath(),new byte[]{(byte)value});return file;
    }
    private void bytes(File file,int value)throws IOException {
        assertArrayEquals(new byte[]{(byte)value},Files.readAllBytes(file.toPath()));
    }
    private File backup(File target,File job) {
        return new File(target.getParentFile(),".elfremote-replaced-"+job.getName()+"-"+target.getName());
    }

    @Test public void transferRejectsConcurrentFileWithAndWithoutOverwrite()throws Exception {
        for(String action:new String[]{"copy","move"})for(boolean overwrite:new boolean[]{false,true}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target");
            if(overwrite)data(root,"target",2);
            CommitRaceAccess fs=new CommitRaceAccess(target);
            assertThrows(IOException.class,()->FileOperations.run(job,task(action,source,target,overwrite),fs));
            assertTrue(fs.injected);bytes(target,9);
            if(overwrite)bytes(backup(target,job),2);else assertFalse(backup(target,job).exists());
            bytes(source,1);
        }
    }
    @Test public void snapshotRejectsConcurrentFile()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target");
        CommitRaceAccess fs=new CommitRaceAccess(target);
        assertThrows(IOException.class,()->FileSnapshot.run(job,new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0),fs));
        assertTrue(fs.injected);bytes(target,9);bytes(source,1);
        assertEquals(2,root.list().length);
    }
    @Test public void directoryCopyAndMoveKeepLegacyRenameWithoutHardLinks()throws Exception {
        for(String action:new String[]{"copy","move"}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=new File(root,"source"),target=new File(root,"target");
            assertTrue(source.mkdir());data(source,"child",1);
            HostFileAccess fs=new HostFileAccess(){@Override void hardLink(File from,File to){
                throw new AssertionError("目录复制或移动不应要求目录硬链接");
            }};
            JSONObject result=FileOperations.run(job,task(action,source,target,false),fs);
            assertEquals("completed",result.getString("state"));
            bytes(new File(target,"child"),1);assertFalse(backup(target,job).exists());
            assertEquals("copy".equals(action),source.exists());
        }
    }
    @Test public void concurrentBackupNameIsNotReplaced()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2),backup=backup(target,job);
        HostFileAccess fs=new HostFileAccess(){@Override boolean rename(File from,File to)throws IOException{
            if(to.equals(backup))Files.write(backup.toPath(),new byte[]{9},StandardOpenOption.CREATE_NEW);
            return super.rename(from,to);
        }};
        assertThrows(IOException.class,()->FileOperations.run(job,task("move",source,target,true),fs));
        bytes(source,1);bytes(target,2);bytes(backup,9);
    }
    @Test public void concurrentRestoreTargetIsNotReplaced()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2),backup=backup(target,job);
        HostFileAccess fs=new HostFileAccess(){@Override boolean rename(File from,File to)throws IOException{
            if(from.equals(source))return false;
            if(from.equals(backup))Files.write(target.toPath(),new byte[]{9},StandardOpenOption.CREATE_NEW);
            return super.rename(from,to);
        }};
        IOException error=assertThrows(IOException.class,()->FileOperations.run(job,task("move",source,target,true),fs));
        bytes(source,1);bytes(target,9);bytes(backup,2);assertTrue(error.getMessage().contains(backup.getPath()));
    }
    @Test public void publishedCopyOrMoveWithUnlinkFailureKeepsNewTargetAndBackup()throws Exception {
        for(String action:new String[]{"copy","move"}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
            File oldName="copy".equals(action)?new File(root,".elfremote-copy-"+job.getName()):source;
            HostFileAccess fs=new HostFileAccess(){@Override void unlink(File file)throws IOException{
                if(file.equals(oldName))throw new IOException("注入旧名称清理失败");super.unlink(file);
            }};
            IOException error=assertThrows(IOException.class,()->FileOperations.run(job,task(action,source,target,true),fs));
            assertTrue(error.getMessage().contains("目标已发布"));assertTrue(error.getMessage().contains(oldName.getPath()));
            bytes(target,1);bytes(source,1);bytes(oldName,1);bytes(backup(target,job),2);
        }
    }
    @Test public void snapshotPublishedBeforeUnlinkFailureRemainsRecoverable()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target"),stage=new File(root,"target.tmp");
        HostFileAccess fs=new HostFileAccess(){@Override void unlink(File file)throws IOException{throw new IOException("注入旧名称清理失败");}};
        assertThrows(FileOperations.PublishedException.class,()->FileSnapshot.run(job,new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0),fs));
        bytes(source,1);bytes(target,1);bytes(stage,1);assertNotNull(FileSnapshot.recover(job,new HostFileAccess()));
    }
    @Test public void backupUnlinkFailureLeavesOriginalAndReportsAdditionalName()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2);
        HostFileAccess fs=new HostFileAccess(){@Override void unlink(File file)throws IOException{throw new IOException("注入旧名称清理失败");}};
        IOException error=assertThrows(IOException.class,()->FileOperations.run(job,task("copy",source,target,true),fs));
        bytes(source,1);bytes(target,2);bytes(backup(target,job),2);assertTrue(error.getMessage().contains(backup(target,job).getPath()));
    }
    @Test public void restoreUnlinkFailureDoesNotUndoRestoredTarget()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=data(root,"target",2),backup=backup(target,job);
        HostFileAccess fs=new HostFileAccess(){
            @Override boolean rename(File from,File to)throws IOException{if(from.equals(source))return false;return super.rename(from,to);}
            @Override void unlink(File file)throws IOException{if(file.equals(backup))throw new IOException("注入恢复名称清理失败");super.unlink(file);}
        };
        IOException error=assertThrows(IOException.class,()->FileOperations.run(job,task("move",source,target,true),fs));
        bytes(source,1);bytes(target,2);bytes(backup,2);assertTrue(error.getMessage().contains(backup.getPath()));
    }
    @Test public void unsupportedHardLinksPreserveCopyMoveAndOverwriteCompatibility()throws Exception {
        for(String action:new String[]{"copy","move"})for(boolean overwrite:new boolean[]{false,true}) {
            File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target");
            if(overwrite)data(root,"target",2);
            HostFileAccess fs=withoutHardLinks();
            JSONObject result=FileOperations.run(job,task(action,source,target,overwrite),fs);
            assertEquals("completed",result.getString("state"));bytes(target,1);assertEquals("copy".equals(action),source.exists());
            if(overwrite)bytes(backup(target,job),2);
        }
    }
    private HostFileAccess withoutHardLinks() {
        return new HostFileAccess(){@Override void hardLink(File from,File to)throws IOException{
            throw new FileOperations.HardLinkUnavailableException(new IOException("注入卷不支持硬链接"));
        }};
    }
    @Test public void unsupportedHardLinksKeepSnapshotWorking()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target");
        JSONObject result=FileSnapshot.run(job,new JSONObject().put("source",source.getPath()).put("target",target.getPath()).put("uid",0),withoutHardLinks());
        assertEquals("completed",result.getString("state"));bytes(source,1);bytes(target,1);assertFalse(new File(root,"target.tmp").exists());
    }
    @Test public void arbitraryLinkFailureIsNotMisclassifiedAsUnsupported()throws Exception {
        File root=temp.newFolder(),job=temp.newFolder(),source=data(root,"source",1),target=new File(root,"target");
        HostFileAccess fs=new HostFileAccess(){@Override void hardLink(File from,File to)throws IOException{throw new IOException("注入其它链接失败");}};
        assertThrows(IOException.class,()->FileOperations.run(job,task("move",source,target,false),fs));bytes(source,1);assertFalse(target.exists());
    }
}
