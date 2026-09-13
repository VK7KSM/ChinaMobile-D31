package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.webrtc.AudioTrack;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.audio.JavaAudioDeviceModule;
import static org.junit.Assert.*;

/** 使用真实WebRTC Java类与零native句柄；不加载JNI，不创建Android音频对象。 */
public class PttCancellationBoundaryTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static Object empty(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type);
    }
    private static void set(Object instance,Class<?> type,String name,Object value)throws Exception{
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(instance,value);
    }
    private static Object get(Object instance,Class<?> type,String name)throws Exception{
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    private static void peerFlag(AndroidRtcDownlink peer,String name,boolean value)throws Exception{set(peer,AndroidRtcDownlink.class,name,value);}
    private final class Fixture {
        final AtomicInteger changed=new AtomicInteger(),failed=new AtomicInteger();
        final AndroidRtcDownlink peer;
        final JavaAudioDeviceModule module;
        final Object output;
        final PeerConnection.Observer callbacks;
        Fixture()throws Exception{
            File cache=temporary.newFolder();
            peer=new AndroidRtcDownlink(null,new File(cache,"unused.apk"),new String(new char[64]).replace('\0','a'),cache,
                    new MediaCapture.Clock(){public long wall(){return 1000000;}public long elapsed(){return 100;}},
                    new AndroidRtcDownlink.Events(){public void changed(){changed.incrementAndGet();}public void failed(String code){failed.incrementAndGet();}});
            module=(JavaAudioDeviceModule)empty(JavaAudioDeviceModule.class);
            output=empty(Class.forName("org.webrtc.audio.WebRtcAudioTrack"));
            set(module,JavaAudioDeviceModule.class,"audioOutput",output);
            set(module,JavaAudioDeviceModule.class,"nativeLock",new Object());
            set(peer,AndroidRtcDownlink.class,"module",module);
            PeerConnection.Observer observer=null;
            for(int i=1;i<10;i++){
                Class<?> candidate;
                try{candidate=Class.forName(AndroidRtcDownlink.class.getName()+"$"+i);}catch(ClassNotFoundException absent){continue;}
                if(PeerConnection.Observer.class.isAssignableFrom(candidate)){
                    Constructor<?> constructor=candidate.getDeclaredConstructor(AndroidRtcDownlink.class);constructor.setAccessible(true);
                    observer=(PeerConnection.Observer)constructor.newInstance(peer);break;
                }
            }
            if(observer==null)throw new AssertionError("没有找到生产PeerConnection回调");callbacks=observer;
        }
        boolean muted()throws Exception{return (Boolean)get(output,output.getClass(),"speakerMute");}
        void permitPlayout()throws Exception{
            for(String flag:new String[]{"playoutRequested","playbackStarted","acknowledged","ice"})peerFlag(peer,flag,true);
        }
        void detachModule()throws Exception{set(peer,AndroidRtcDownlink.class,"module",null);}
    }
    private static RtpReceiver receiver(AudioTrack track)throws Exception{
        RtpReceiver receiver=(RtpReceiver)empty(RtpReceiver.class);set(receiver,RtpReceiver.class,"cachedTrack",track);return receiver;
    }
    private static RtpTransceiver transceiver(RtpReceiver receiver)throws Exception{
        RtpTransceiver tx=(RtpTransceiver)empty(RtpTransceiver.class);set(tx,RtpTransceiver.class,"cachedReceiver",receiver);return tx;
    }
    public static final class BlockingTrack extends AudioTrack {
        CountDownLatch entered,release;
        private BlockingTrack(){super(1);}
        public boolean setEnabled(boolean enabled){
            entered.countDown();
            try{if(!release.await(4,TimeUnit.SECONDS))throw new AssertionError("测试回调等待超时");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            return true;
        }
    }

    @Test public void cancelSetsActualSoftwareMuteBeforeClose()throws Exception{
        Fixture f=new Fixture();f.permitPlayout();f.peer.enablePlayout(()->{});assertFalse(f.muted());
        f.peer.cancel();assertTrue("cancel返回时真实WebRTC软件静音位仍为false",f.muted());
        assertFalse(f.peer.ready());assertEquals(0,f.failed.get());
    }
    @Test public void evidenceWriterWaitMustNotDelaySoftwareMute()throws Exception{
        Fixture f=new Fixture();f.permitPlayout();f.peer.enablePlayout(()->{});
        CountDownLatch writerEntered=new CountDownLatch(1),writerRelease=new CountDownLatch(1),delegateClosed=new CountDownLatch(1);
        PttSessionController.Operations delegate=new PttSessionController.Operations(){
            public void open(){}public void send(org.json.JSONObject value){}
            public org.json.JSONObject subscribe(org.json.JSONObject value){return value;}
            public void answerAcknowledged(){}public void prepareMuted(){}public int outputProof(){return 1;}
            public boolean playbackFrames(){return true;}public void unmute(){}public void cancel(){f.peer.cancel();}
            public void close(){delegateClosed.countDown();}
        };
        AppPttEvidence evidence=new AppPttEvidence(delegate,null,null,null,null,
                new MediaCapture.Clock(){public long wall(){return 1;}public long elapsed(){return 1;}},null);
        ExecutorService writer=(ExecutorService)get(evidence,AppPttEvidence.class,"writer");
        ExecutorService closer=Executors.newSingleThreadExecutor();Future<?> closing=null;
        try{
            writer.submit(()->{writerEntered.countDown();try{writerRelease.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(writerEntered.await(1,TimeUnit.SECONDS));evidence.cancel();
            closing=closer.submit(()->{try{evidence.close();}catch(Exception e){throw new RuntimeException(e);}});
            assertFalse(delegateClosed.await(100,TimeUnit.MILLISECONDS));
            assertTrue("取证writer仍在退出，已取消PTT却仍未软件静音",f.muted());
        }finally{
            writerRelease.countDown();
            if(closing!=null)closing.get(2,TimeUnit.SECONDS);else evidence.close();
            closer.shutdownNow();
        }
    }
    @Test public void lateOnTrackNeverTouchesDisposedNativeWrapper()throws Exception{
        Fixture f=new Fixture();AudioTrack disposed=(AudioTrack)empty(AudioTrack.class);
        f.peer.cancel();f.callbacks.onTrack(transceiver(receiver(disposed)));
        assertEquals(0,f.failed.get());assertEquals(0,f.changed.get());
    }
    @Test public void lateOnAddTrackNeverTouchesDisposedNativeWrapper()throws Exception{
        Fixture f=new Fixture();f.peer.cancel();
        f.callbacks.onAddTrack(receiver((AudioTrack)empty(AudioTrack.class)),new MediaStream[0]);
        assertEquals(0,f.failed.get());assertEquals(0,f.changed.get());
    }
    @Test public void cancellationDuringOwnershipReadCannotUnmuteAfterward()throws Exception{
        Fixture f=new Fixture();f.permitPlayout();f.peer.enablePlayout(()->{});
        try{f.peer.enablePlayout(f.peer::cancel);fail("已取消仍然解静音");}
        catch(java.io.IOException expected){assertEquals("MEDIA_CANCELLED",expected.getMessage());}
        assertTrue(f.muted());
    }
    @Test public void strictOwnershipRejectionKeepsSoftwareMute()throws Exception{
        Fixture f=new Fixture();f.permitPlayout();
        set(f.output,f.output.getClass(),"speakerMute",true);
        try{f.peer.enablePlayout(()->{throw new java.io.IOException("MEDIA_OUTPUT_UNKNOWN");});fail("绕过实际守卫");}
        catch(java.io.IOException expected){assertEquals("MEDIA_OUTPUT_UNKNOWN",expected.getMessage());}
        assertTrue(f.muted());
    }
    @Test public void cancellationDoesNotInvokeWebRtcLogCallbacks()throws Exception{
        Fixture f=new Fixture();AtomicInteger calls=new AtomicInteger();
        java.lang.reflect.Method install=org.webrtc.Logging.class.getDeclaredMethod("injectLoggable",org.webrtc.Loggable.class,org.webrtc.Logging.Severity.class);
        java.lang.reflect.Method remove=org.webrtc.Logging.class.getDeclaredMethod("deleteInjectedLoggable");
        install.setAccessible(true);remove.setAccessible(true);
        try{
            install.invoke(null,(org.webrtc.Loggable)(message,severity,tag)->calls.incrementAndGet(),org.webrtc.Logging.Severity.LS_VERBOSE);
            f.peer.cancel();assertEquals("取消进入了可能阻塞的库日志回调",0,calls.get());
        }finally{remove.invoke(null);}
    }
    @Test public void cancelDoesNotWaitForActiveNativeCallback()throws Exception{
        Fixture f=new Fixture();BlockingTrack track=(BlockingTrack)empty(BlockingTrack.class);
        track.entered=new CountDownLatch(1);track.release=new CountDownLatch(1);
        RtpReceiver receiver=receiver(track);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<?> callback=workers.submit(()->f.callbacks.onAddTrack(receiver,new MediaStream[0]));
            assertTrue(track.entered.await(1,TimeUnit.SECONDS));
            workers.submit(f.peer::cancel).get(500,TimeUnit.MILLISECONDS);
            assertEquals(1,track.release.getCount());
            track.release.countDown();callback.get(1,TimeUnit.SECONDS);
        }finally{track.release.countDown();workers.shutdownNow();}
    }
    @Test public void closeWaitsForEnteredCallbackBeforeClearingResources()throws Exception{
        Fixture f=new Fixture();BlockingTrack track=(BlockingTrack)empty(BlockingTrack.class);
        track.entered=new CountDownLatch(1);track.release=new CountDownLatch(1);
        RtpReceiver receiver=receiver(track);ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<?> callback=workers.submit(()->f.callbacks.onAddTrack(receiver,new MediaStream[0]));
            assertTrue(track.entered.await(1,TimeUnit.SECONDS));f.detachModule();
            CountDownLatch closeEntered=new CountDownLatch(1),closeReturned=new CountDownLatch(1);
            Future<?> close=workers.submit(()->{closeEntered.countDown();try{f.peer.close();}catch(Exception e){throw new RuntimeException(e);}finally{closeReturned.countDown();}});
            assertTrue(closeEntered.await(1,TimeUnit.SECONDS));
            assertFalse("回调仍在使用轨道时close已返回成功",closeReturned.await(100,TimeUnit.MILLISECONDS));
            track.release.countDown();callback.get(1,TimeUnit.SECONDS);close.get(1,TimeUnit.SECONDS);
        }finally{track.release.countDown();workers.shutdownNow();}
    }
    @Test public void hungCallbackMakesCloseFailBoundedly()throws Exception{
        Fixture f=new Fixture();BlockingTrack track=(BlockingTrack)empty(BlockingTrack.class);
        track.entered=new CountDownLatch(1);track.release=new CountDownLatch(1);
        RtpReceiver receiver=receiver(track);ExecutorService workers=Executors.newSingleThreadExecutor();
        try{
            Future<?> callback=workers.submit(()->f.callbacks.onAddTrack(receiver,new MediaStream[0]));
            assertTrue(track.entered.await(1,TimeUnit.SECONDS));f.detachModule();long began=System.nanoTime();
            try{f.peer.close();fail("未退出回调被误报为释放完成");}
            catch(java.io.IOException expected){assertEquals("MEDIA_PTT_CALLBACK_RELEASE_UNCONFIRMED",expected.getMessage());}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<2500);
            track.release.countDown();callback.get(1,TimeUnit.SECONDS);f.peer.close();
        }finally{track.release.countDown();workers.shutdownNow();}
    }
}
