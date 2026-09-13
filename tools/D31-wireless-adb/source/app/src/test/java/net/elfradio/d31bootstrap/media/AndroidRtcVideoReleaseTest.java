package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.webrtc.*;
import static org.junit.Assert.*;

/** 只替代JNI构造及原生操作，直接执行正式组件close；不打开相机或音频。 */
public class AndroidRtcVideoReleaseTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static <T>T allocate(Class<T> type)throws Exception {
        Class<?> allocator=Class.forName("sun.misc.Unsafe");
        Field field=allocator.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(allocator.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=AndroidRtcVideoCapture.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception {
        Field field=AndroidRtcVideoCapture.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    static class Sender extends RtpSender {
        String key;boolean disposed;
        Sender(){super(0);}
        public String id(){if(disposed)throw new IllegalStateException("RtpSender has been disposed.");return key;}
        public boolean setTrack(MediaStreamTrack track,boolean owns){throw new AssertionError("不得重复清空轨道");}
        public void dispose(){disposed=true;}
    }
    static class Peer extends PeerConnection {
        List<RtpSender> fresh;RtpSender removed;boolean reject;RuntimeException failure;int lookups;
        Peer(){super((NativePeerConnectionFactory)null);}
        public List<RtpSender> getSenders(){lookups++;return fresh;}
        public boolean removeTrack(RtpSender sender){if(failure!=null)throw failure;removed=sender;return !reject;}
    }
    static class Track extends VideoTrack {
        boolean disposed;RuntimeException failure;
        Track(){super(0);}
        public void dispose(){if(failure!=null)throw failure;disposed=true;}
    }
    static class Source extends VideoSource {
        boolean disposed;RuntimeException failure;
        Source(){super(0);}
        public void dispose(){if(failure!=null)throw failure;disposed=true;}
    }
    private Sender sender(String id)throws Exception {Sender result=allocate(Sender.class);result.key=id;return result;}
    private AndroidRtcVideoCapture driver(Peer peer)throws Exception {
        AndroidRtcVideoCapture result=allocate(AndroidRtcVideoCapture.class);
        set(result,"peer",peer);set(result,"senderAttached",true);set(result,"senderId","video-fixture");return result;
    }
    private static IOException closeFails(AndroidRtcVideoCapture driver,String code)throws Exception {
        try{driver.close();fail("应保留释放失败");return null;}catch(IOException error){assertEquals(code,error.getMessage());return error;}
    }
    @Test public void fixedJarDisposedSenderRejectsSetTrackBeforeNativeCall()throws Exception {
        // 真实固定依赖的方法，零native句柄模拟dispose后的包装状态，不执行JNI。
        RtpSender disposed=allocate(RtpSender.class);
        try{disposed.setTrack(null,false);fail();}catch(IllegalStateException expected){assertEquals("RtpSender has been disposed.",expected.getMessage());}
    }
    @Test public void refreshedSenderIdSurvivesWrapperReplacementAndPreservesAudio()throws Exception {
        Sender old=sender("video-fixture");old.dispose();
        Sender audio=sender("audio-fixture"),current=sender("video-fixture");
        Peer peer=allocate(Peer.class);peer.fresh=Arrays.asList(audio,current);
        AndroidRtcVideoCapture driver=driver(peer);Track track=allocate(Track.class);set(driver,"track",track);
        driver.close();driver.close();
        assertEquals(1,peer.lookups);assertSame(current,peer.removed);assertTrue(track.disposed);
        assertFalse(current.disposed);assertFalse(audio.disposed);assertTrue((Boolean)get(driver,"released"));
    }
    @Test public void removeRejectionRetainsTrackAndCameraLease()throws Exception {
        Peer peer=allocate(Peer.class);peer.fresh=Arrays.asList(sender("video-fixture"));peer.reject=true;
        AndroidRtcVideoCapture driver=driver(peer);Track track=allocate(Track.class);set(driver,"track",track);
        File root=temp.newFolder();MediaFiles.Lease held=MediaFiles.lease(root);set(driver,"lease",held);
        try{
            closeFails(driver,"MEDIA_VIDEO_SENDER_REMOVE_REJECTED");assertFalse(track.disposed);assertSame(track,get(driver,"track"));
            try(MediaFiles.Lease ignored=MediaFiles.lease(root)){fail();}catch(IOException expected){assertEquals("MEDIA_BUSY",expected.getMessage());}
        }finally{held.close();}
    }
    @Test public void removeExceptionKeepsSpecificStageAndOriginalCause()throws Exception {
        Peer peer=allocate(Peer.class);peer.fresh=Arrays.asList(sender("video-fixture"));peer.failure=new IllegalStateException("fixture-private-detail");
        AndroidRtcVideoCapture driver=driver(peer);
        IOException error=closeFails(driver,"MEDIA_VIDEO_SENDER_REMOVE_EXCEPTION");
        assertSame(peer.failure,error.getCause());assertFalse(error.getMessage().contains("fixture-private-detail"));
        assertTrue((Boolean)get(driver,"senderAttached"));
    }
    @Test public void missingSenderIsNotAssumedReleased()throws Exception {
        Peer peer=allocate(Peer.class);peer.fresh=Arrays.asList(sender("audio-fixture"));
        AndroidRtcVideoCapture driver=driver(peer);closeFails(driver,"MEDIA_VIDEO_SENDER_NOT_FOUND");assertNull(peer.removed);
    }
    @Test public void trackDisposeExceptionStopsBeforeSourceDispose()throws Exception {
        Peer peer=allocate(Peer.class);peer.fresh=Arrays.asList(sender("video-fixture"));
        AndroidRtcVideoCapture driver=driver(peer);Track track=allocate(Track.class);Source source=allocate(Source.class);
        track.failure=new IllegalStateException("fixture");set(driver,"track",track);set(driver,"source",source);
        closeFails(driver,"MEDIA_VIDEO_TRACK_DISPOSE_EXCEPTION");assertFalse(source.disposed);assertSame(track,get(driver,"track"));
    }
    @Test public void cameraDisposeExceptionStopsBeforeSenderAndKeepsCamera()throws Exception {
        Peer peer=allocate(Peer.class);AndroidRtcVideoCapture driver=driver(peer);
        CameraVideoCapturer camera=(CameraVideoCapturer)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CameraVideoCapturer.class},(proxy,method,args)->{
            if(method.getName().equals("dispose"))throw new IllegalStateException("fixture");return null;
        });
        set(driver,"camera",camera);closeFails(driver,"MEDIA_VIDEO_CAMERA_DISPOSE_EXCEPTION");
        assertEquals(0,peer.lookups);assertSame(camera,get(driver,"camera"));
    }
    @Test public void successfulCloseReleasesLeaseOnlyAfterNativeSteps()throws Exception {
        Peer peer=allocate(Peer.class);peer.fresh=Arrays.asList(sender("video-fixture"));AndroidRtcVideoCapture driver=driver(peer);
        Source source=allocate(Source.class);set(driver,"source",source);File root=temp.newFolder();set(driver,"lease",MediaFiles.lease(root));
        driver.close();assertTrue(source.disposed);assertNull(get(driver,"lease"));
        try(MediaFiles.Lease next=MediaFiles.lease(root)){assertNotNull(next);}
    }
}
