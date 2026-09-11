package net.elfradio.d31bootstrap.media;

import java.net.URI;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppMediaControllerTest {
    @Test public void diagnosticOwnerDeathCancellationAndServiceExitReleaseAndKeepIdleQuery()throws Exception {
        for(int action=0;action<3;action++){
            LocalAudioCaptureTest.Recorder record=new LocalAudioCaptureTest.Recorder();
            record.reading=new CountDownLatch(1);record.unblock=new CountDownLatch(1);
            Backend backend=new Backend(){@Override public LocalAudioCapture localCapture(String hash,String id,int ms,Cancellation cancel)throws Exception{
                return new LocalAudioCapture(id,ms,123,10001,c->record,cancel,LocalAudioCaptureTest.CLOCK);}};
            AppMediaController controller=new AppMediaController(backend,LocalAudioCaptureTest.CLOCK);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<JSONObject> job=worker.submit(()->controller.execute("diag-request",AppMediaContractTest.diagnostic(),owner,new Cancellation()));
                assertTrue(record.reading.await(1,TimeUnit.SECONDS));
                assertEquals("diagnosing",controller.query("",owner).getString("state"));
                controller.ownerDied(new Object());assertEquals(0,record.stops);
                if(action==0)controller.ownerDied(owner);else if(action==1)controller.cancelRequest("diag-request");else controller.serviceDestroyed();
                assertEquals("CANCELLED",job.get(2,TimeUnit.SECONDS).getString("state"));
                assertEquals(1,record.releases);assertFalse(controller.hasActive());
                JSONObject query=controller.query("",new Object());assertEquals("idle",query.getString("state"));
                assertTrue(query.getJSONObject("last_diagnostic").getBoolean("release_completed"));assertFalse(query.getBoolean("managed_media"));
            }finally{record.unblock.countDown();controller.serviceDestroyed();worker.shutdownNow();}
        }
    }
    @Test public void diagnosticCanBeStoppedBySeparateRequestAndCannotBeReplayed()throws Exception {
        LocalAudioCaptureTest.Recorder record=new LocalAudioCaptureTest.Recorder();record.reading=new CountDownLatch(1);record.unblock=new CountDownLatch(1);
        Backend backend=new Backend(){@Override public LocalAudioCapture localCapture(String hash,String id,int ms,Cancellation cancel)throws Exception{
            return new LocalAudioCapture(id,ms,1,10001,c->record,cancel,LocalAudioCaptureTest.CLOCK);}};
        AppMediaController controller=new AppMediaController(backend,LocalAudioCaptureTest.CLOCK);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<JSONObject> job=worker.submit(()->controller.execute("diag-request",AppMediaContractTest.diagnostic(),owner,new Cancellation()));
            assertTrue(record.reading.await(1,TimeUnit.SECONDS));
            MediaCaptureTest.rejects("BUSY",()->controller.execute("other",AppMediaContractTest.diagnostic().put("diagnostic_id","other"),new Object(),new Cancellation()));
            assertEquals("diag-request",controller.execute("stop-request",command("stop").put("session_id","local-1"),new Object(),new Cancellation()).getString("operation_request_id"));
            assertEquals("CANCELLED",job.get(2,TimeUnit.SECONDS).getString("state"));assertEquals(1,record.releases);
            assertEquals("diag-request",controller.query("local-1",new Object()).getString("operation_request_id"));
            MediaCaptureTest.rejects("ALREADY_USED",()->controller.execute("replay",AppMediaContractTest.diagnostic(),owner,new Cancellation()));
        }finally{record.unblock.countDown();controller.serviceDestroyed();worker.shutdownNow();}
    }
    @Test public void diagnosticReleaseFailurePreventsAnotherCapture()throws Exception {
        LocalAudioCaptureTest.Recorder record=new LocalAudioCaptureTest.Recorder();record.releaseFails=true;
        Backend backend=new Backend(){@Override public LocalAudioCapture localCapture(String hash,String id,int ms,Cancellation cancel)throws Exception{
            return new LocalAudioCapture(id,ms,1,10001,c->record,cancel,LocalAudioCaptureTest.CLOCK);}};
        AppMediaController controller=new AppMediaController(backend,LocalAudioCaptureTest.CLOCK);
        assertEquals("RELEASE_UNCONFIRMED",controller.execute("diag",AppMediaContractTest.diagnostic(),owner,new Cancellation()).getString("state"));
        assertTrue(controller.hasActive());MediaCaptureTest.rejects("BUSY",()->controller.execute("other",AppMediaContractTest.diagnostic().put("diagnostic_id","other"),owner,new Cancellation()));
    }
    final MediaCaptureTest.Clock clock=new MediaCaptureTest.Clock();
    final Object owner=new Object();
    class Session implements AppMediaController.Session {
        int starts,stops;String state="idle";
        public void start(){starts++;state="streaming";}
        public void stop(){stops++;state="closed";}
        public JSONObject snapshot()throws Exception{return new JSONObject().put("state",state).put("session_id","session-1");}
    }
    class Backend implements AppMediaController.Backend {
        int prepares,creates;Session last;CountDownLatch entered,proceed;
        public URI origin()throws Exception{return new URI("https://control.example");}
        public JSONObject prepare(String hash,Cancellation cancel)throws Exception{prepares++;return new JSONObject().put("state","PREPARED_WITH_GAPS").put("audio_occupancy","NOT_CONFIRMED_IDLE").put("jni_loaded",true).put("managed_media",false);}
        public AppMediaController.Session create(String hash,RtcOffer offer,Cancellation cancel)throws Exception{
            creates++;last=new Session();if(entered!=null){entered.countDown();assertTrue(proceed.await(2,TimeUnit.SECONDS));}return last;
        }
    }
    JSONObject command(String op)throws Exception{
        JSONObject value=new JSONObject().put("operation",op).put("apk_sha256",AppMediaContractTest.HASH);
        if("start".equals(op))value.put("offer",RtcOfferTest.offer());else value.put("session_id","session-1");return value;
    }
    @Test public void prepareDoesNotCreateSessionAndUnknownRemainsUnknown()throws Exception{
        Backend backend=new Backend();AppMediaController controller=new AppMediaController(backend,clock);
        JSONObject result=controller.execute("prepare",command("prepare"),owner,new Cancellation());
        assertEquals(1,backend.prepares);assertEquals(0,backend.creates);assertEquals("NOT_CONFIRMED_IDLE",result.getString("audio_occupancy"));
        assertFalse(result.getBoolean("managed_media"));assertEquals("idle",controller.query("",owner).getString("state"));
    }
    @Test public void emptyQueryAndOtherOwnerDoNotRenewLease()throws Exception{
        Backend backend=new Backend();AppMediaController controller=new AppMediaController(backend,clock);
        controller.execute("start",command("start"),owner,new Cancellation());clock.advance(14000);
        controller.query("",owner);controller.query("session-1",new Object());clock.advance(1000);controller.tick();
        assertEquals("closed",backend.last.state);
    }
    @Test public void explicitOwnedQueryRenewsAndSeparateStopEnds()throws Exception{
        Backend backend=new Backend();AppMediaController controller=new AppMediaController(backend,clock);
        controller.execute("start",command("start"),owner,new Cancellation());clock.advance(14000);controller.query("session-1",owner);
        clock.advance(1000);controller.tick();assertEquals("streaming",backend.last.state);
        controller.execute("other-stop-id",command("stop"),new Object(),new Cancellation());assertEquals("closed",backend.last.state);
    }
    @Test public void deathOnlyStopsItsOwnerAndReplayDoesNotCaptureAgain()throws Exception{
        Backend backend=new Backend();final AppMediaController controller=new AppMediaController(backend,clock);
        controller.execute("start",command("start"),owner,new Cancellation());controller.ownerDied(new Object());assertEquals("streaming",backend.last.state);
        controller.ownerDied(owner);assertEquals("closed",backend.last.state);
        MediaCaptureTest.rejects("ALREADY_USED",new MediaCaptureTest.Operation(){public void run()throws Exception{controller.execute("retry",command("start"),owner,new Cancellation());}});
        assertEquals(1,backend.creates);
    }
    @Test public void queryAndStopWorkDuringPendingNativePreparation()throws Exception{
        final Backend backend=new Backend();backend.entered=new CountDownLatch(1);backend.proceed=new CountDownLatch(1);
        final AppMediaController controller=new AppMediaController(backend,clock);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<?> job=worker.submit(new Callable<Object>(){public Object call()throws Exception{return controller.execute("pending",command("start"),owner,new Cancellation());}});
            assertTrue(backend.entered.await(2,TimeUnit.SECONDS));assertEquals("preparing",controller.query("",owner).getString("state"));
            assertEquals("closing",controller.stop("session-1").getString("state"));backend.proceed.countDown();
            try{job.get(2,TimeUnit.SECONDS);fail("cancel must reject");}catch(ExecutionException expected){assertTrue(expected.getCause().getMessage().contains("CANCELLED"));}
            assertEquals(0,backend.last.starts);assertEquals(1,backend.last.stops);
        }finally{backend.proceed.countDown();worker.shutdownNow();}
    }
    @Test public void lateCancelCannotStopDifferentStartRequest()throws Exception{
        Backend backend=new Backend();AppMediaController controller=new AppMediaController(backend,clock);
        controller.execute("current",command("start"),owner,new Cancellation());controller.cancelRequest("previous");
        assertEquals("streaming",backend.last.state);controller.cancelRequest("current");assertEquals("closed",backend.last.state);
    }
    @Test public void lostServiceClosesSessionWithoutAnotherStart()throws Exception{
        Backend backend=new Backend();AppMediaController controller=new AppMediaController(backend,clock);
        controller.execute("current",command("start"),owner,new Cancellation());controller.serviceDestroyed();
        assertEquals("closed",backend.last.state);assertEquals(1,backend.last.starts);
    }
}
