package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ApkMediaLibraryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    File archive(byte[] data)throws Exception{
        File file=temp.newFile();try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){
            out.putNextEntry(new ZipEntry("lib/armeabi-v7a/libjingle_peerconnection_so.so"));out.write(data);out.closeEntry();}
        return file;
    }
    File actualArmLibrary()throws Exception {
        String configured=System.getProperty("d31.test.webrtcArmLibrary");
        assertNotNull("缺少d31.test.webrtcArmLibrary；组合测试须依赖mergeFullReleaseNativeLibs并传入本批v7a原生库路径",configured);
        File file=new File(configured);
        assertTrue("d31.test.webrtcArmLibrary未指向本批真实原生库文件",file.isFile());
        return file;
    }
    @Test public void extractsAndValidatesActualCachedArmLibraryWithoutLoading()throws Exception{
        File real=actualArmLibrary();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(real)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}
        byte[] content=bytes.toByteArray();assertEquals(0x7f,content[0]);assertEquals('E',content[1]);assertEquals(1,content[4]);
        File apk=archive(content),cache=temp.newFolder();String hash=MediaFiles.hash(apk);
        File extracted=ApkMediaLibrary.extract(apk,hash,cache,"armeabi-v7a");assertEquals(MediaFiles.hash(real),MediaFiles.hash(extracted));
        assertEquals(extracted,ApkMediaLibrary.extract(apk,hash,cache,"armeabi-v7a"));
    }
    @Test public void refusesChangedApkMissingAbiAndCorruptCache()throws Exception{
        final File apk=archive(new byte[]{1,2,3}),cache=temp.newFolder();final String hash=MediaFiles.hash(apk);
        MediaCaptureTest.rejects("APK_CHANGED",new MediaCaptureTest.Operation(){public void run()throws Exception{ApkMediaLibrary.extract(apk,new String(new char[64]).replace('\0','0'),cache,"armeabi-v7a");}});
        MediaCaptureTest.rejects("ENTRY",new MediaCaptureTest.Operation(){public void run()throws Exception{ApkMediaLibrary.extract(apk,hash,cache,"arm64-v8a");}});
        File output=ApkMediaLibrary.extract(apk,hash,cache,"armeabi-v7a");try(FileOutputStream out=new FileOutputStream(output)){out.write(9);}
        MediaCaptureTest.rejects("CACHE_CHANGED",new MediaCaptureTest.Operation(){public void run()throws Exception{ApkMediaLibrary.extract(apk,hash,cache,"armeabi-v7a");}});
    }
    @Test public void readonlyReadinessDoesNotInventIdentityOrCapability()throws Exception{
        org.json.JSONObject report=MediaReadiness.snapshot(null,"net.elfradio.d31bootstrap",null);
        assertEquals("NOT_READY",report.getString("state"));assertFalse(report.getBoolean("managed_media"));
        assertFalse(report.getBoolean("managed_alarm_tasks"));assertEquals("NOT_VERIFIED",report.getString("audio_record_identity"));
        assertEquals("READ_FAILED",report.getString("record_permission"));assertEquals("NOT_CHECKED",report.getString("jni_loaded"));
        assertEquals("NOT_CONFIRMED_IDLE",report.getString("audio_occupancy"));assertFalse(report.has("uid"));
    }
    @Test public void readonlyArchiveInspectionDoesNotExtract()throws Exception{
        File apk=archive(new byte[]{1,2,3});String[] before=temp.getRoot().list();
        org.json.JSONObject result=ApkMediaLibrary.inspect(apk,MediaFiles.hash(apk),"armeabi-v7a");
        assertEquals("ARCHIVE_ENTRY_OBSERVED",result.getString("state"));assertEquals("NOT_CHECKED",result.getString("jni_loaded"));
        assertEquals(3,result.getInt("native_bytes"));assertArrayEquals(before,temp.getRoot().list());
    }
    @Test public void rootSystemShellAndMismatchedPackageCannotUseAppRecordingIdentity(){
        String expected="net.elfradio.d31bootstrap";String[] packages={expected};
        for(int uid:new int[]{0,1000,2000,99000,-1})assertFalse(MediaReadiness.applicationIdentityMatches(uid,expected,expected,packages));
        assertTrue(MediaReadiness.applicationIdentityMatches(10042,expected,expected,packages));
        assertTrue(MediaReadiness.applicationIdentityMatches(110042,expected,expected,packages));
        assertFalse(MediaReadiness.applicationIdentityMatches(10042,"android",expected,packages));
        assertFalse(MediaReadiness.applicationIdentityMatches(10042,expected,expected,new String[]{"other.package"}));
        assertFalse(MediaReadiness.applicationIdentityMatches(10042,expected,expected,null));
    }
}
