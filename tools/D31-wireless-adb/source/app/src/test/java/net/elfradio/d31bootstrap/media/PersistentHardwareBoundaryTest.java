package net.elfradio.d31bootstrap.media;

import android.content.ContextWrapper;
import java.io.File;
import org.junit.*;
import static org.junit.Assert.*;

/** Android异名子类仅替代硬件系统调用；真实WebRTC字段与释放代码参与执行。 */
public class PersistentHardwareBoundaryTest {
    @Test public void soleBackCameraResolvesDefaultFrontRequestWithActualMetadata()throws Exception {
        AndroidPersistentRtc.CameraSelection selected=AndroidPersistentRtc.selectCamera(new String[]{"camera0"},new int[]{0},"front");
        assertEquals("camera0",selected.name);assertEquals("back",selected.facing);assertEquals(1,selected.count);
        try{AndroidPersistentRtc.selectCamera(new String[]{"camera0","camera1"},new int[]{0,0},"front");fail();}
        catch(java.io.IOException expected){assertEquals("MEDIA_CAMERA_FACING_UNAVAILABLE",expected.getMessage());}
    }
    public static final class Record extends android.media.AudioRecord {
        int state,calls,recording=1;private Record(){super(1,16000,16,2,320);}
        public int getState(){return state;}
        public void release(){calls++;state=0;recording=1;}
        public int getRecordingState(){return recording;}
    }
    public static final class Track extends android.media.AudioTrack {
        int state,calls,playing=1;private Track(){super(3,48000,4,2,960,1);}
        public int getState(){return state;}
        public void release(){calls++;state=0;playing=1;}
        public int getPlayState(){return playing;}
    }
    private final CallInitOnlyReleaseTest logs=new CallInitOnlyReleaseTest();
    @Before public void before()throws Exception{logs.quietLibraryLogs();}
    @After public void after()throws Exception{logs.restoreLibraryLogs();}
    static final class Fixture {
        final CallInitOnlyReleaseTest.Audio audio=new CallInitOnlyReleaseTest.Audio();
        final Record record=(Record)CallInitOnlyReleaseTest.empty(Record.class);final Track track=(Track)CallInitOnlyReleaseTest.empty(Track.class);
        final AndroidPersistentRtc rtc;
        Fixture()throws Exception{
            record.state=1;record.recording=1;track.state=1;track.playing=1;
            CallInitOnlyReleaseTest.set(audio.input,"audioRecord",record);CallInitOnlyReleaseTest.set(audio.output,"audioTrack",track);
            rtc=new AndroidPersistentRtc((ContextWrapper)CallInitOnlyReleaseTest.empty(ContextWrapper.class),new File("unused.apk"),new String(new char[64]).replace('\0','a'),new File("unused-cache"),new MediaCapture.Clock(){public long elapsed(){return System.nanoTime()/1000000;}public long wall(){return 0;}});
            CallInitOnlyReleaseTest.set(rtc,"module",audio.module);rtc.bind(audio.module);
        }
    }
    @Test public void inactiveInitializedAudioObjectsAreAllowedInIdle()throws Exception{Fixture f=new Fixture();assertTrue(f.rtc.idle());assertEquals(1,f.record.getState());assertEquals(1,f.track.getState());f.rtc.close();}
    @Test public void deactivateRetainsInactiveModuleAndObjects()throws Exception{Fixture f=new Fixture();f.rtc.stop(System.nanoTime()+2000000000L);
        assertSame(f.audio.module,CallInitOnlyReleaseTest.get(f.rtc,"module"));assertSame(f.record,CallInitOnlyReleaseTest.get(f.audio.input,"audioRecord"));assertSame(f.track,CallInitOnlyReleaseTest.get(f.audio.output,"audioTrack"));
        assertEquals(0,f.record.calls);assertEquals(0,f.track.calls);assertTrue(f.rtc.idle());f.rtc.close();}
    @Test public void setAudioRecordDisabledAloneDoesNotProveHardwareStopped()throws Exception{Fixture f=new Fixture();f.record.recording=3;
        try{f.rtc.stop(System.nanoTime()+50000000L);fail();}catch(java.io.IOException expected){assertEquals("MEDIA_PERSISTENT_HARDWARE_STOP_TIMEOUT",expected.getMessage());}
        assertEquals(0,f.record.calls);f.record.recording=1;f.rtc.close();}
    @Test public void requestedHardwareCannotBeCalledIdleBeforeActualStart()throws Exception{Fixture f=new Fixture();CallInitOnlyReleaseTest.set(f.audio.input,"useAudioRecord",true);assertFalse(f.rtc.idle());CallInitOnlyReleaseTest.set(f.audio.input,"useAudioRecord",false);f.rtc.close();}
    @Test public void actualRecordingRejectsIdleEvenWithoutStartCallback()throws Exception{Fixture f=new Fixture();f.record.recording=3;assertFalse(f.rtc.idle());f.record.recording=1;f.rtc.close();}
    @Test public void actualPlaybackRejectsIdleEvenWithoutStartCallback()throws Exception{Fixture f=new Fixture();f.track.playing=3;assertFalse(f.rtc.idle());f.track.playing=1;f.rtc.close();}
    @Test public void mutedPlayingTrackSurvivesOperationStopUntilFullClose()throws Exception{Fixture f=new Fixture();
        f.track.playing=3;f.rtc.softStop();assertTrue(f.rtc.idle());f.rtc.stop(System.nanoTime()+2000000000L);
        assertEquals(3,f.track.playing);assertEquals(0,f.track.calls);assertSame(f.track,CallInitOnlyReleaseTest.get(f.audio.output,"audioTrack"));
        f.rtc.close();assertEquals(1,f.track.calls);assertNull(CallInitOnlyReleaseTest.get(f.audio.output,"audioTrack"));}
    @Test public void fullCloseUsesActualInitOnlyReleaseMethods()throws Exception{Fixture f=new Fixture();f.rtc.close();assertEquals(1,f.record.calls);assertEquals(1,f.track.calls);assertEquals(0,f.record.state);assertEquals(0,f.track.state);assertNull(CallInitOnlyReleaseTest.get(f.audio.input,"audioRecord"));assertNull(CallInitOnlyReleaseTest.get(f.audio.output,"audioTrack"));}
    @Test public void mutedIdleCannotSubstituteForPhysicalRelease()throws Exception{Fixture f=new Fixture();f.rtc.softStop();assertTrue(f.rtc.idle());
        try{f.rtc.requireReleased();fail();}catch(java.io.IOException expected){}
        f.rtc.close();f.rtc.requireReleased();}
    @Test public void softStopMutesBothSidesWithoutInvokingLibraryLogging()throws Exception{Fixture f=new Fixture();f.rtc.softStop();assertEquals(true,CallInitOnlyReleaseTest.get(f.audio.input,"microphoneMute"));assertEquals(true,CallInitOnlyReleaseTest.get(f.audio.output,"speakerMute"));f.rtc.close();}
}
