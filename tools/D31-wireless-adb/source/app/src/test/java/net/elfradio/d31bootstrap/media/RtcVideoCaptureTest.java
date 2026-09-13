package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class RtcVideoCaptureTest {
    static final class Clock implements MediaCapture.Clock {volatile long now=1000;public long elapsed(){return now;}public long wall(){return now;}}
    static class Driver implements RtcVideoCapture.Driver {
        volatile RtcVideoCapture.Events events;
        final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);
        boolean failStart,failRelease;volatile boolean cancelled;int closes;
        public void start(RtcVideoCapture.Events events,Cancellation cancel)throws Exception {this.events=events;entered.countDown();if(failStart)throw new IOException("MEDIA_VIDEO_FIXTURE_START_FAILED");}
        public void cancel(){cancelled=true;}
        public void close()throws Exception {closes++;released.countDown();if(failRelease)throw new IOException("MEDIA_VIDEO_FIXTURE_RELEASE_FAILED");}
    }
    static void await(CountDownLatch latch)throws Exception {assertTrue(latch.await(2,TimeUnit.SECONDS));}
    @Test public void attachedAndStartedDoNotBecomeReadyWithoutFrame()throws Exception {
        Driver d=new Driver();Clock c=new Clock();RtcVideoCapture v=new RtcVideoCapture(d,c,null);v.start();await(d.entered);
        d.events.attached("back",1);d.events.started(true);assertFalse(v.ready());
        d.events.frame(320,240,100);v.awaitReady(100,new Cancellation());assertTrue(v.ready());
        assertEquals("back",v.snapshot().getString("camera"));assertEquals(320,v.snapshot().getInt("width"));
        v.close();assertTrue(v.awaitClosed(1000));assertTrue(v.snapshot().getBoolean("cleanup_complete"));
    }
    @Test public void firstFrameTimeoutClosesDriver()throws Exception {
        Driver d=new Driver();Clock c=new Clock();RtcVideoCapture v=new RtcVideoCapture(d,c,null);v.start();await(d.entered);
        c.now+=RtcVideoCapture.START_TIMEOUT_MS;v.tick();assertTrue(v.awaitClosed(1000));
        assertEquals("MEDIA_VIDEO_FIRST_FRAME_TIMEOUT",v.snapshot().getString("error"));assertEquals(1,d.closes);
    }
    @Test public void failedStartAlwaysReleasesPartialResources()throws Exception {
        Driver d=new Driver();d.failStart=true;RtcVideoCapture v=new RtcVideoCapture(d,new Clock(),null);v.start();
        assertTrue(v.awaitClosed(1000));assertEquals(1,d.closes);assertEquals("MEDIA_VIDEO_FIXTURE_START_FAILED",v.snapshot().getString("error"));
    }
    @Test public void failedCameraStartedCallbackIsNotReady()throws Exception {
        Driver d=new Driver();RtcVideoCapture v=new RtcVideoCapture(d,new Clock(),null);v.start();await(d.entered);d.events.started(false);
        assertTrue(v.awaitClosed(1000));assertFalse(v.ready());assertEquals("MEDIA_VIDEO_START_FAILED",v.snapshot().getString("error"));
    }
    @Test public void duplicateFramesDoNotMaskStall()throws Exception {
        Driver d=new Driver();Clock c=new Clock();RtcVideoCapture v=new RtcVideoCapture(d,c,null);v.start();await(d.entered);
        d.events.attached("back",1);d.events.started(true);d.events.frame(640,480,100);assertTrue(v.ready());
        c.now+=RtcVideoCapture.FRAME_TIMEOUT_MS;d.events.frame(640,480,100);v.tick();assertTrue(v.awaitClosed(1000));
        assertEquals("MEDIA_VIDEO_FRAMES_STOPPED",v.snapshot().getString("error"));
    }
    @Test public void callbacksAfterCloseCannotReopenSession()throws Exception {
        Driver d=new Driver();RtcVideoCapture v=new RtcVideoCapture(d,new Clock(),null);v.start();await(d.entered);
        v.close();assertTrue(v.awaitClosed(1000));d.events.attached("back",1);d.events.started(true);d.events.frame(640,480,100);
        assertFalse(v.ready());assertEquals("closed",v.snapshot().getString("state"));
        try{v.start();fail();}catch(IOException expected){assertEquals("MEDIA_VIDEO_NOT_REUSABLE",expected.getMessage());}
    }
    @Test public void releaseFailureStaysUnconfirmed()throws Exception {
        Driver d=new Driver();d.failRelease=true;RtcVideoCapture v=new RtcVideoCapture(d,new Clock(),null);v.start();await(d.entered);v.close();await(d.released);
        assertFalse(v.awaitClosed(100));assertFalse(v.snapshot().getBoolean("cleanup_complete"));assertEquals("release_unconfirmed",v.snapshot().getString("state"));
    }
    @Test public void blockingReleaseNeverBlocksCloseCallerOrClaimsSuccess()throws Exception {
        CountDownLatch unblock=new CountDownLatch(1),closing=new CountDownLatch(1);
        Driver d=new Driver(){public void close()throws Exception {closing.countDown();unblock.await(2,TimeUnit.SECONDS);super.close();}};
        Clock c=new Clock();RtcVideoCapture v=new RtcVideoCapture(d,c,null);v.start();await(d.entered);
        long before=System.nanoTime();v.close();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<250);await(closing);
        c.now+=RtcVideoCapture.RELEASE_TIMEOUT_MS;v.tick();assertFalse(v.awaitClosed(1));assertEquals("release_unconfirmed",v.snapshot().getString("state"));
        unblock.countDown();assertTrue(v.awaitClosed(1000));
    }
    @Test public void callerCancellationStopsCaptureInsteadOfLeavingItBehind()throws Exception {
        Driver d=new Driver();RtcVideoCapture v=new RtcVideoCapture(d,new Clock(),null);v.start();await(d.entered);
        Cancellation caller=new Cancellation();caller.cancel();try{v.awaitReady(1000,caller);fail();}catch(IOException expected){}
        assertTrue(v.awaitClosed(1000));assertTrue(d.cancelled);
    }
    @Test public void normalFramesDoNotWakeReporterEveryFrame()throws Exception {
        Driver d=new Driver();Clock c=new Clock();AtomicInteger wake=new AtomicInteger();RtcVideoCapture v=new RtcVideoCapture(d,c,x->wake.incrementAndGet());
        v.start();await(d.entered);d.events.attached("back",1);d.events.started(true);d.events.frame(640,480,1);int first=wake.get();
        for(int i=2;i<100;i++){c.now++;d.events.frame(640,480,i);}assertEquals(first,wake.get());v.close();assertTrue(v.awaitClosed(1000));
    }
    @Test public void closeBeforeStartDoesNotOpenDevice()throws Exception {
        Driver d=new Driver();RtcVideoCapture v=new RtcVideoCapture(d,new Clock(),null);v.close();assertTrue(v.awaitClosed(1000));assertNull(d.events);
    }
}
