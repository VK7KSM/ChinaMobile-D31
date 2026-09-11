package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteReleaseFilesTest {
    static final File PATH=new File("release-test");
    static RemoteReleaseFiles.Entry entry(int mode,int uid,long links,long inode,boolean canonical) {
        return new RemoteReleaseFiles.Entry(mode,uid,links,1,inode,canonical);
    }
    static final class Access implements RemoteReleaseFiles.Access {
        RemoteReleaseFiles.Entry value=entry(0040777,0,2,10,true), opened;
        int creates,opens,changes,syncs,closes;
        boolean changeAtOpen,replaceAfterSync,failChmod,failSync,ignoreChmod;
        public RemoteReleaseFiles.Entry stat(File path) { return value; }
        public void mkdir(File path) { creates++; value=entry(0040000,0,2,10,true); }
        public RemoteReleaseFiles.Handle open(File path) {
            opens++; opened=changeAtOpen ? entry(value.mode,0,value.links,99,true) : value;
            return new RemoteReleaseFiles.Handle() {
                public RemoteReleaseFiles.Entry stat() { return opened; }
                public void chmod(int mode) throws Exception {
                    changes++; if(failChmod)throw new IOException("模拟权限失败");
                    if(!ignoreChmod) { opened=entry((opened.mode & 0170000)|mode,opened.uid,opened.links,opened.inode,true); value=opened; }
                }
                public void sync() throws Exception {
                    syncs++; if(failSync)throw new IOException("模拟同步失败");
                    if(replaceAfterSync)value=entry(opened.mode,0,opened.links,99,true);
                }
                public void close() { closes++; }
            };
        }
    }
    interface Action { void run() throws Exception; }
    static void rejected(Action action) throws Exception {
        try { action.run(); fail("必须拒绝"); } catch(IOException expected) { }
    }
    @Test public void existingWideRootAndHashDirectoriesAreTightened() throws Exception {
        Access access=new Access(); RemoteReleaseFiles files=new RemoteReleaseFiles(access);
        files.directory(PATH);
        assertEquals(0700,access.value.mode & 07777); assertEquals(0,access.creates);
        assertEquals(1,access.changes); assertEquals(1,access.syncs); assertEquals(1,access.closes);
    }
    @Test public void newDirectoryDoesNotDependOnUmask() throws Exception {
        Access access=new Access(); access.value=null;
        new RemoteReleaseFiles(access).directory(PATH);
        assertEquals(1,access.creates); assertEquals(0700,access.value.mode & 07777);
    }
    @Test public void originalArchiveBytesAreUntouchedWhileModeBecomesPrivate() throws Exception {
        File apk=File.createTempFile("release-test-",".apk");
        byte[] original=new byte[]{0,1,2,3,127,-1};
        try {
            Files.write(apk.toPath(),original);
            Access access=new Access(); access.value=entry(0100666,0,1,10,true);
            new RemoteReleaseFiles(access).file(apk);
            assertEquals(0600,access.value.mode & 07777); assertArrayEquals(original,Files.readAllBytes(apk.toPath()));
        } finally { Files.deleteIfExists(apk.toPath()); }
    }
    @Test public void existingFailedPartIsRejectedWithoutTruncationOrPermissionChange() throws Exception {
        File part=File.createTempFile("release-failed-",".part");
        byte[] original=new byte[]{80,75,3,4,-1,0,25};
        try {
            Files.write(part.toPath(),original);
            Access access=new Access(); access.value=entry(0100600,0,1,10,true);
            rejected(() -> new RemoteReleaseFiles(access).requireNewPart(part));
            assertArrayEquals(original,Files.readAllBytes(part.toPath()));
            assertEquals(0,access.opens); assertEquals(0,access.changes);
            access.value=entry(0120777,0,1,10,true);
            rejected(() -> new RemoteReleaseFiles(access).requireNewPart(part));
            assertArrayEquals(original,Files.readAllBytes(part.toPath()));
            access.value=null; new RemoteReleaseFiles(access).requireNewPart(part);
        } finally { Files.deleteIfExists(part.toPath()); }
    }
    @Test public void linksWrongOwnersWrongTypesAndAliasesAreRejectedBeforeOpen() throws Exception {
        for(RemoteReleaseFiles.Entry bad:new RemoteReleaseFiles.Entry[]{entry(0120777,0,1,10,true),
                entry(0100666,1000,1,10,true),entry(0100666,0,2,10,true),entry(0040777,0,2,10,true),
                entry(0100666,0,1,10,false),entry(0010600,0,1,10,true)}) {
            Access access=new Access(); access.value=bad;
            rejected(() -> new RemoteReleaseFiles(access).file(PATH)); assertEquals(0,access.opens); assertEquals(0,access.changes);
        }
        for(RemoteReleaseFiles.Entry bad:new RemoteReleaseFiles.Entry[]{entry(0120777,0,1,10,true),
                entry(0040777,1000,2,10,true),entry(0040777,0,2,10,false)}) {
            Access access=new Access(); access.value=bad;
            rejected(() -> new RemoteReleaseFiles(access).directory(PATH)); assertEquals(0,access.opens); assertEquals(0,access.creates);
        }
    }
    @Test public void privateParentIsRequiredAndNeverSilentlyRepaired() throws Exception {
        Access access=new Access(); RemoteReleaseFiles files=new RemoteReleaseFiles(access);
        rejected(() -> files.requireRoot(PATH)); assertEquals(0,access.changes);
        access.value=entry(0040700,0,2,10,true); files.requireRoot(PATH); assertEquals(0,access.opens);
    }
    @Test public void replacedOpenDescriptorIsNotChmoddedAndIsClosed() throws Exception {
        Access access=new Access(); access.changeAtOpen=true;
        rejected(() -> new RemoteReleaseFiles(access).directory(PATH));
        assertEquals(0,access.changes); assertEquals(1,access.closes);
    }
    @Test public void chmodAndSyncFailuresCloseDescriptorAndNeverReportSuccess() throws Exception {
        Access chmod=new Access(); chmod.failChmod=true;
        rejected(() -> new RemoteReleaseFiles(chmod).directory(PATH)); assertEquals(1,chmod.closes); assertEquals(0,chmod.syncs);
        Access sync=new Access(); sync.failSync=true;
        rejected(() -> new RemoteReleaseFiles(sync).directory(PATH)); assertEquals(1,sync.closes);
    }
    @Test public void unchangedModeAndPathReplacementAfterSyncAreRejected() throws Exception {
        Access mode=new Access(); mode.ignoreChmod=true;
        rejected(() -> new RemoteReleaseFiles(mode).directory(PATH)); assertEquals(1,mode.closes);
        Access path=new Access(); path.replaceAfterSync=true;
        rejected(() -> new RemoteReleaseFiles(path).directory(PATH)); assertEquals(1,path.closes);
        assertEquals(99,path.value.inode);
    }
}
