package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import static org.junit.Assert.*;

/** 使用固定WebRTC真实释放方法；Android对象仅以异名子类替代硬件调用，无生产同名桩。 */
public class CallInitOnlyReleaseTest {
    static Object empty(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    static void set(Object value,String name,Object fieldValue)throws Exception{
        Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,fieldValue);
    }
    static Object get(Object value,String name)throws Exception{
        Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    public static class Record extends android.media.AudioRecord {
        int state,calls;boolean throwsRelease,keepsState;CountDownLatch entered,unblock;
        private Record(){super(1,16000,16,2,320);}
        @Override public int getState(){return state;}
        @Override public void release(){
            calls++;if(entered!=null){entered.countDown();try{unblock.await();}catch(InterruptedException e){throw new AssertionError(e);}}
            if(throwsRelease)throw new IllegalStateException("合成释放失败");if(!keepsState)state=0;
        }
    }
    public static class Track extends android.media.AudioTrack {
        int state,calls;boolean throwsRelease,keepsState;
        private Track(){super(3,48000,4,2,960,1);}
        @Override public int getState(){return state;}
        @Override public void release(){calls++;if(throwsRelease)throw new IllegalStateException("合成释放失败");if(!keepsState)state=0;}
    }
    static class Audio {
        final JavaAudioDeviceModule module;
        final Object input,output;
        final Record record;
        final Track track;
        Audio()throws Exception{
            module=(JavaAudioDeviceModule)empty(JavaAudioDeviceModule.class);
            input=empty(Class.forName("org.webrtc.audio.WebRtcAudioRecord"));
            output=empty(Class.forName("org.webrtc.audio.WebRtcAudioTrack"));
            record=(Record)empty(Record.class);record.state=1;
            track=(Track)empty(Track.class);track.state=1;
            set(module,"audioInput",input);set(module,"audioOutput",output);set(module,"nativeLock",new Object());
            set(input,"audioRecordStateLock",new Object());
            set(input,"effects",empty(Class.forName("org.webrtc.audio.WebRtcAudioEffects")));
            set(input,"audioSourceMatchesRecordingSessionRef",new AtomicReference<Boolean>());
            set(input,"audioRecord",record);set(output,"audioTrack",track);
        }
        void assertReleased()throws Exception{
            assertNull(get(input,"audioRecord"));assertNull(get(output,"audioTrack"));
            assertEquals(0,record.state);assertEquals(0,track.state);
        }
    }
    @Before public void quietLibraryLogs()throws Exception{
        Method method=Logging.class.getDeclaredMethod("injectLoggable",Loggable.class,Logging.Severity.class);method.setAccessible(true);
        method.invoke(null,(Loggable)(message,severity,tag)->{},Logging.Severity.LS_VERBOSE);
    }
    @After public void restoreLibraryLogs()throws Exception{
        Method method=Logging.class.getDeclaredMethod("deleteInjectedLoggable");method.setAccessible(true);method.invoke(null);
    }
    @Test public void actualPrivateMethodsReleaseBothInitOnlyObjects()throws Exception{
        Audio a=new Audio();assertNull(get(a.input,"audioThread"));assertNull(get(a.output,"audioThread"));
        new AndroidCallPeer.AudioRelease(a.module).finish();a.assertReleased();assertEquals(1,a.record.calls);assertEquals(1,a.track.calls);
    }
    @Test public void actualBackendCloseCannotSucceedWithInitOnlyObjectsRemaining()throws Exception{
        try(CallCancellationBoundaryTest.Fixture f=new CallCancellationBoundaryTest.Fixture()){
            Audio a=new Audio();set(f.backend,"module",a.module);f.peer.close();a.assertReleased();
            assertNull(get(f.backend,"module"));assertTrue(f.peer.snapshot().getBoolean("peer_cleanup_complete"));
        }
    }
    @Test public void alreadyReleasedByNativeDoesNotDoubleRelease()throws Exception{
        Audio a=new Audio();AndroidCallPeer.AudioRelease release=new AndroidCallPeer.AudioRelease(a.module);
        a.record.release();a.track.release();set(a.input,"audioRecord",null);set(a.output,"audioTrack",null);
        release.finish();a.assertReleased();assertEquals(1,a.record.calls);assertEquals(1,a.track.calls);
    }
    @Test public void clearedFieldWithoutReleasedOriginalObjectIsRejected()throws Exception{
        Audio a=new Audio();AndroidCallPeer.AudioRelease release=new AndroidCallPeer.AudioRelease(a.module);set(a.input,"audioRecord",null);
        try{release.finish();fail("清空字段不能代替释放");}catch(IOException expected){assertEquals("MEDIA_CALL_AUDIO_OBJECT_RELEASE_UNCONFIRMED",expected.getMessage());}
        assertEquals(1,a.record.state);assertEquals(0,a.track.state);
    }
    @Test public void releaseReturningWithoutUninitializingRecordIsRejected()throws Exception{
        Audio a=new Audio();a.record.keepsState=true;
        try{new AndroidCallPeer.AudioRelease(a.module).finish();fail();}catch(IOException expected){assertEquals("MEDIA_CALL_AUDIO_OBJECT_RELEASE_UNCONFIRMED",expected.getMessage());}
        assertNull(get(a.input,"audioRecord"));assertEquals(0,a.track.state);
    }
    @Test public void releaseReturningWithoutUninitializingTrackIsRejected()throws Exception{
        Audio a=new Audio();a.track.keepsState=true;
        try{new AndroidCallPeer.AudioRelease(a.module).finish();fail();}catch(IOException expected){assertEquals("MEDIA_CALL_AUDIO_OBJECT_RELEASE_UNCONFIRMED",expected.getMessage());}
        assertEquals(0,a.record.state);
    }
    @Test public void recordReleaseFailureStillReleasesOutputAndRetainsRecord()throws Exception{
        Audio a=new Audio();a.record.throwsRelease=true;
        try{new AndroidCallPeer.AudioRelease(a.module).finish();fail();}catch(IOException expected){assertEquals("MEDIA_CALL_AUDIO_OBJECT_RELEASE_UNCONFIRMED",expected.getMessage());}
        assertSame(a.record,get(a.input,"audioRecord"));assertNull(get(a.output,"audioTrack"));assertEquals(0,a.track.state);
    }
    @Test public void bothReleaseFailuresArePreserved()throws Exception{
        Audio a=new Audio();a.record.throwsRelease=true;a.track.throwsRelease=true;
        try{new AndroidCallPeer.AudioRelease(a.module).finish();fail();}catch(IOException expected){assertEquals(1,expected.getSuppressed().length);}
        assertEquals(1,a.record.calls);assertEquals(1,a.track.calls);
    }
    @Test public void liveRetainedThreadBlocksReleaseEvenWhenLibraryFieldIsNull()throws Exception{
        Audio a=new Audio();AndroidCallPeer.AudioRelease release=new AndroidCallPeer.AudioRelease(a.module);
        CountDownLatch stop=new CountDownLatch(1);Thread running=new Thread(()->{try{stop.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}},"d31-call-test");
        running.start();release.input.threads.add(running);
        try{release.finish();fail();}catch(IOException expected){assertEquals("MEDIA_CALL_AUDIO_THREAD_RELEASE_UNCONFIRMED",expected.getMessage());}
        finally{stop.countDown();running.join(1000);}
        assertEquals(0,a.record.calls);assertEquals(0,a.track.calls);
    }
    @Test public void releaseFailureCannotPublishCleanupComplete()throws Exception{
        try(CallCancellationBoundaryTest.Fixture f=new CallCancellationBoundaryTest.Fixture()){
            Audio a=new Audio();a.record.throwsRelease=true;set(f.backend,"module",a.module);
            try{f.peer.close();fail();}catch(IOException expected){}
            assertFalse(f.peer.snapshot().getBoolean("peer_cleanup_complete"));assertTrue(f.peer.snapshot().getBoolean("release_unconfirmed"));
            assertSame(a.module,get(f.backend,"module"));assertEquals(0,a.track.state);
        }
    }
    @Test public void slowHardwareReleaseStaysInsideExistingCallerBudgetAndFailureIsSticky()throws Exception{
        try(CallCancellationBoundaryTest.Fixture f=new CallCancellationBoundaryTest.Fixture()){
            Audio a=new Audio();a.record.entered=new CountDownLatch(1);a.record.unblock=new CountDownLatch(1);set(f.backend,"module",a.module);
            set(f.peer,"releaseTimeout",100L);ExecutorService caller=Executors.newSingleThreadExecutor();
            try{
                Future<?> closing=caller.submit(()->{try{f.peer.close();throw new AssertionError();}catch(IOException expected){}catch(Exception e){throw new RuntimeException(e);}});
                assertTrue(a.record.entered.await(1,TimeUnit.SECONDS));closing.get(1,TimeUnit.SECONDS);
                assertTrue(f.peer.snapshot().getBoolean("release_unconfirmed"));assertFalse(f.peer.snapshot().getBoolean("peer_cleanup_complete"));
                a.record.unblock.countDown();
                ExecutorService worker=(ExecutorService)get(f.peer,"worker");assertTrue(worker.awaitTermination(1,TimeUnit.SECONDS));
                try{f.peer.close();fail();}catch(IOException expected){}
                a.assertReleased();assertTrue(f.peer.snapshot().getBoolean("release_unconfirmed"));assertFalse(f.peer.snapshot().getBoolean("peer_cleanup_complete"));
            }finally{a.record.unblock.countDown();caller.shutdownNow();}
        }
    }
}
