package net.elfradio.d31bootstrap.media;

import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoAlarmSessionTest {
    static class Clock implements MediaCapture.Clock {volatile long time=100000;public long wall(){return time;}public long elapsed(){return time;}}
    static class Wire implements PhotoAlarmSession.Wire {
        final List<JSONObject> sent=Collections.synchronizedList(new ArrayList<>());final CountDownLatch connected=new CountDownLatch(1);boolean closed;
        public void connect(PhotoAlarmOffer offer,PhotoAlarmSession.Events e){connected.countDown();}
        public void send(JSONObject x){sent.add(x);}public void close(){closed=true;}
    }
    static class Backend implements PhotoAlarmSession.Backend {
        final CountDownLatch invoked=new CountDownLatch(1),release=new CountDownLatch(1),cleaned=new CountDownLatch(1);
        volatile int photos,alarms;volatile boolean cancelled,bad,guardBusy;
        public JSONObject photo(PhotoAlarmOffer offer,Cancellation cancel)throws Exception {
            photos++;invoked.countDown();release.await(3,TimeUnit.SECONDS);cancel.check();if(bad)throw new java.io.IOException("PHOTO_ACK_UNKNOWN_OR_MISMATCH");
            return new JSONObject().put("type","result").put("report_id",offer.id).put("captured_at",100001);
        }
        public void alarm(PhotoAlarmOffer offer){alarms++;invoked.countDown();}
        public void checkAlarm()throws Exception{if(guardBusy)throw new java.io.IOException("VISUAL_CALL_BUSY_OR_UNKNOWN");}
        public void cancel(){cancelled=true;release.countDown();}public void close(){cleaned.countDown();}
    }
    static String hello(String mode)throws Exception{return new JSONObject().put("type","hello").put("mode",mode).put("camera","front").toString();}
    static void await(CountDownLatch latch)throws Exception{assertTrue(latch.await(3,TimeUnit.SECONDS));}
    @Test public void duplicateHelloCapturesExactlyOnceAndStopCancelsPendingUpload()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);session.receive(hello("photo"));await(backend.invoked);session.receive(hello("photo"));
        session.receive("{\"type\":\"closed\"}");await(backend.cleaned);
        assertEquals(1,backend.photos);assertTrue(backend.cancelled);assertTrue(wire.closed);
        assertFalse(wire.sent.stream().anyMatch(x->"result".equals(x.optString("type"))));
    }
    @Test public void noHelloNoCaptureAndOwnerLeaseExpires()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);clock.time+=15001;session.tick();await(backend.cleaned);
        assertEquals(0,backend.photos);assertTrue(session.snapshot().getBoolean("closed"));
    }
    @Test public void alarmTimeoutReleasesToneAndSession()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("alarm")),wire,backend,clock);
        session.start();await(wire.connected);session.receive(hello("alarm"));await(backend.invoked);
        for(int i=0;i<100&&!"playing".equals(session.snapshot().optString("state"));i++)Thread.sleep(5);
        clock.time+=10001;session.tick();await(backend.cleaned);assertEquals(1,backend.alarms);assertTrue(backend.cancelled);
    }
    @Test public void mismatchedHelloNeverOpensDevice()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);session.receive(hello("alarm"));await(backend.cleaned);assertEquals(0,backend.photos);
    }
    @Test public void unknownPhotoAckIsNotResult()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();backend.bad=true;backend.release.countDown();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);session.receive(hello("photo"));await(backend.cleaned);
        assertFalse(wire.sent.stream().anyMatch(x->"result".equals(x.optString("type"))));
        assertEquals("failed",session.snapshot().getString("state"));
    }
    @Test public void switchDoesNotOverwriteOriginalReport()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);session.receive("{\"type\":\"switch\",\"camera\":\"back\"}");session.close();await(backend.cleaned);
        assertEquals(0,backend.photos);assertTrue(wire.sent.stream().anyMatch(x->"status".equals(x.optString("type"))));
    }
    @Test public void closeReturnsWithoutWaitingForHttpOrSocketCleanup()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();CountDownLatch blocked=new CountDownLatch(1),cancelRelease=new CountDownLatch(1);
        Backend backend=new Backend(){public void cancel(){blocked.countDown();try{cancelRelease.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){}super.cancel();}};
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);
        long before=System.nanoTime();session.close();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<500);
        await(blocked);assertFalse(session.snapshot().getBoolean("cleanup_complete"));cancelRelease.countDown();await(backend.cleaned);
    }
    @Test public void failedHardwareCleanupCannotBecomeComplete()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();CountDownLatch attempted=new CountDownLatch(1);
        Backend backend=new Backend(){public void close(){attempted.countDown();throw new IllegalStateException("release pending");}};
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("photo")),wire,backend,clock);
        session.start();await(wire.connected);session.close();await(attempted);
        assertFalse(session.finished());assertFalse(session.snapshot().getBoolean("cleanup_complete"));
    }
    @Test public void callBecomingBusyStopsAnAlarm()throws Exception {
        Clock clock=new Clock();Wire wire=new Wire();Backend backend=new Backend();
        PhotoAlarmSession session=new PhotoAlarmSession(PhotoAlarmOfferTest.parse(PhotoAlarmOfferTest.offer("alarm")),wire,backend,clock);
        session.start();await(wire.connected);session.receive(hello("alarm"));await(backend.invoked);
        for(int i=0;i<100&&!"playing".equals(session.snapshot().optString("state"));i++)Thread.sleep(5);
        backend.guardBusy=true;session.tick();await(backend.cleaned);assertEquals("failed",session.snapshot().getString("state"));
    }
}
