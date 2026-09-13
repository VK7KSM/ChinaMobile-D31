package net.elfradio.d31bootstrap.media;

import java.lang.reflect.Method;
import org.junit.*;
import static org.junit.Assert.*;

/** 固定SDK真实volatile门及写后确认；不访问设备或私有捕获。 */
public class PersistentOutputMuteTest {
    private PersistentHardwareBoundaryTest.Fixture hardware;
    private final CallInitOnlyReleaseTest logs=new CallInitOnlyReleaseTest();
    @Before public void start()throws Exception {
        logs.quietLibraryLogs();hardware=new PersistentHardwareBoundaryTest.Fixture();
        hardware.track.playing=3;
        CallInitOnlyReleaseTest.set(hardware.audio.output,"speakerMute",true);
        CallInitOnlyReleaseTest.set(hardware.rtc,"outputCallbackThread",Thread.currentThread());
        CallInitOnlyReleaseTest.set(hardware.rtc,"actualOutput",hardware.track);
        hardware.rtc.focusOwned(true);
    }
    @After public void finish()throws Exception {
        try{hardware.rtc.close();}finally{logs.restoreLibraryLogs();}
    }
    private void pcm(byte[] bytes)throws Exception {Method m=AndroidPersistentRtc.class.getDeclaredMethod("confirmMutedOutput",byte[].class,int.class,int.class,int.class);
        m.setAccessible(true);m.invoke(hardware.rtc,bytes,2,1,48000);}
    private void mute(boolean value)throws Exception {Method m=AndroidPersistentRtc.class.getDeclaredMethod("outputMute",boolean.class);m.setAccessible(true);m.invoke(hardware.rtc,value);}
    private void denied()throws Exception {try{hardware.rtc.requireMutedOutput();fail();}catch(java.io.IOException expected){}}
    @Test public void onlySecondSameGenerationZeroWriteConfirmsMute()throws Exception {
        denied();pcm(new byte[960]);denied();pcm(new byte[960]);hardware.rtc.requireMutedOutput();
    }
    @Test public void oldGenerationCannotConfirmAfterUnmuteAndRemute()throws Exception {
        pcm(new byte[960]);pcm(new byte[960]);hardware.rtc.requireMutedOutput();
        mute(false);denied();mute(true);denied();pcm(new byte[960]);denied();pcm(new byte[960]);hardware.rtc.requireMutedOutput();
    }
    @Test public void nonzeroOrWrongSizedWriteRevokesConfirmedMute()throws Exception {
        byte[] nonzero=new byte[960];nonzero[959]=1;
        for(byte[] invalid:new byte[][]{nonzero,new byte[958]}){
            pcm(new byte[960]);pcm(new byte[960]);hardware.rtc.requireMutedOutput();pcm(invalid);denied();
        }
    }
    @Test public void repeatedSoftStopDoesNotContinuouslyInvalidateSameMute()throws Exception {
        pcm(new byte[960]);pcm(new byte[960]);long epoch=hardware.rtc.outputGeneration();hardware.rtc.softStop();hardware.rtc.softStop();
        assertEquals(epoch,hardware.rtc.outputGeneration());hardware.rtc.requireMutedOutput();
    }
    @Test public void lostFocusRevokesEvenWhenSameOutputKeepsPlaying()throws Exception {
        pcm(new byte[960]);pcm(new byte[960]);hardware.rtc.requireMutedOutput();hardware.rtc.focusOwned(false);denied();
        hardware.rtc.focusOwned(true);denied();pcm(new byte[960]);denied();pcm(new byte[960]);hardware.rtc.requireMutedOutput();
    }
    @Test public void stoppedThreadOrChangedObjectCannotReuseConfirmation()throws Exception {
        pcm(new byte[960]);pcm(new byte[960]);hardware.rtc.requireMutedOutput();
        CallInitOnlyReleaseTest.set(hardware.rtc,"outputCallbackThread",new Thread());denied();
        CallInitOnlyReleaseTest.set(hardware.rtc,"outputCallbackThread",Thread.currentThread());
        CallInitOnlyReleaseTest.set(hardware.rtc,"actualOutput",new Object());denied();
    }
    @Test public void boundOutputCallbacksNeedNoRepeatedIdentityJson()throws Exception {
        Method m=AndroidPersistentRtc.class.getDeclaredMethod("observeMutedOutput",byte[].class,int.class,int.class,int.class);m.setAccessible(true);
        for(int i=0;i<5;i++)m.invoke(hardware.rtc,new byte[960],2,1,48000);
        hardware.rtc.requireMutedOutput();
    }
}
