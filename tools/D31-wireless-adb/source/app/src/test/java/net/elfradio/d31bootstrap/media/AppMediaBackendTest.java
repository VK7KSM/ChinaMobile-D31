package net.elfradio.d31bootstrap.media;

import java.net.URI;
import java.lang.reflect.Field;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class AppMediaBackendTest {
    // Windows File不解析目录联接；用宿主真实路径解析复现Android File的父级链接语义。
    private File androidFile(File file){
        return new File(file.getAbsolutePath()){
            @Override public File getCanonicalFile()throws java.io.IOException {
                if(Files.exists(toPath()))return toPath().toRealPath().toFile();
                return new File(androidFile(getParentFile()).getCanonicalFile(),getName());
            }
        };
    }
    private File directoryFixture()throws Exception {
        return Files.createTempDirectory(new File(System.getProperty("java.io.tmpdir")).toPath(),"app-media-path-").toFile();
    }
    private File folder(File parent,String name)throws Exception {
        File child=new File(parent,name);assertTrue(child.mkdirs());return child;
    }
    private File link(File parent,String name,File target)throws Exception {
        File alias=new File(parent,name);
        if(File.separatorChar=='\\'){
            ProcessBuilder command=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",
                    "New-Item -ItemType Junction -Path $env:D31_MEDIA_TEST_LINK -Target $env:D31_MEDIA_TEST_TARGET -ErrorAction Stop | Out-Null");
            command.environment().put("D31_MEDIA_TEST_LINK",alias.getAbsolutePath());
            command.environment().put("D31_MEDIA_TEST_TARGET",target.getAbsolutePath());
            command.redirectErrorStream(true).redirectOutput(new File(parent,name+"-junction.log"));
            Process process=command.start();
            if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();fail("宿主目录别名创建超时");}
            assertEquals("宿主目录别名创建失败，见临时目录日志",0,process.exitValue());
        }else Files.createSymbolicLink(alias.toPath(),target.toPath());
        assertTrue(Files.isSameFile(target.toPath(),alias.toPath()));return androidFile(alias);
    }
    @Test public void androidParentAliasResolvesBothPrivateDirectories()throws Exception {
        File fixture=directoryFixture(),data=folder(fixture,"data"),root=folder(data,"app");
        folder(root,"code_cache");folder(root,"files");File user0=link(fixture,"user0",data);
        File aliasRoot=new File(user0,"app");
        for(String name:new String[]{"code_cache","files"}){
            File resolved=AppMediaBackend.privateDirectory(androidFile(aliasRoot),androidFile(new File(aliasRoot,name)));
            assertEquals(new File(root,name).getCanonicalFile(),resolved);
            File child=new File(resolved,name.equals("files")?"media":"media-native");
            assertFalse(child.exists());assertEquals(child,MediaFiles.directory(child));
        }
    }
    @Test public void privateDirectoryRejectsEscapedChildrenAndPrefixSibling()throws Exception {
        File fixture=directoryFixture(),root=folder(fixture,"app"),outside=folder(fixture,"app-other");
        for(String name:new String[]{"code_cache","files"}){
            File escaped=link(root,name,outside);
            MediaCaptureTest.rejects("OUTSIDE_DATA",()->AppMediaBackend.privateDirectory(root,escaped));
        }
        MediaCaptureTest.rejects("OUTSIDE_DATA",()->AppMediaBackend.privateDirectory(root,outside));
        MediaCaptureTest.rejects("OUTSIDE_DATA",()->AppMediaBackend.privateDirectory(root,root));
    }
    @Test public void canonicalParentDoesNotAllowLinkedMediaChildren()throws Exception {
        File fixture=directoryFixture(),root=folder(fixture,"app"),outside=folder(fixture,"outside");
        for(String name:new String[]{"code_cache","files"}){
            File directory=AppMediaBackend.privateDirectory(root,folder(root,name));
            File escaped=link(directory,name.equals("files")?"media":"media-native",outside);
            MediaCaptureTest.rejects("LINK_PATH_REJECTED",()->MediaFiles.directory(escaped));
        }
    }
    @Test public void guardSnapshotDoesNotSampleOrPretendIdle()throws Exception{
        final int[] reads={0};
        AndroidAudioOccupancy guard=new AndroidAudioOccupancy(
                ()->{reads[0]++;throw new SecurityException("private-source-detail");},()->100L);
        try{
            JSONObject result=new JSONObject();AppMediaBackend.appendGuardSnapshot(result,guard);
            JSONObject snapshot=result.getJSONObject("audio_occupancy_snapshot");
            assertEquals("UNKNOWN",snapshot.getString("state"));assertEquals("NOT_STARTED",snapshot.getString("reason"));
            assertFalse(snapshot.getBoolean("idle"));assertTrue(snapshot.getBoolean("read_only"));
            assertEquals(0,reads[0]);assertFalse(result.toString().contains("private-source-detail"));
            guard.close();AppMediaBackend.appendGuardSnapshot(result,guard);
            assertEquals("CLOSED",result.getJSONObject("audio_occupancy_snapshot").getString("reason"));assertEquals(0,reads[0]);
        }finally{guard.close();}
    }
    @Test public void missingGuardSnapshotRemainsUnknown()throws Exception{
        JSONObject result=new JSONObject();AppMediaBackend.appendGuardSnapshot(result,null);
        assertEquals("UNKNOWN",result.getJSONObject("audio_occupancy_snapshot").getString("state"));
        assertEquals("GUARD_UNAVAILABLE",result.getJSONObject("audio_occupancy_snapshot").getString("reason"));
        AppMediaBackend.appendGuardSnapshot(result,()->{});
        assertEquals("SNAPSHOT_NOT_SUPPORTED",result.getJSONObject("audio_occupancy_snapshot").getString("reason"));
    }
    @Test public void applicationConfigureOnlyRegistersFactory()throws Exception{
        final int[] creations={0};
        try{
            AppMediaService.configure(new AppMediaService.GuardFactory(){public AudioGuard create(android.content.Context context){creations[0]++;return null;}},new URI("https://control.example"));
            assertEquals(0,creations[0]);assertNotNull(AppMediaService.configuration().factory);
        }finally{AppMediaService.configure(null,new URI("https://control.example"));}
    }
    @Test public void autoCloseableGuardReleasedExactlyOnce()throws Exception{
        class Guard implements AudioGuard,AutoCloseable {int closes;public void requireIdle(){}public void close(){closes++;}}
        Guard guard=new Guard();AppMediaBackend backend=new AppMediaBackend(null);
        Field field=AppMediaBackend.class.getDeclaredField("guard");field.setAccessible(true);field.set(backend,guard);
        backend.close();backend.close();assertEquals(1,guard.closes);assertNull(field.get(backend));
    }
}
