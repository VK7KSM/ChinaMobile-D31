package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PreparedFailurePropagationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class PrivateOwnerException extends IOException {
        PrivateOwnerException(){super("PRIVATE_PATH_TOKEN");}
    }
    final class Flow implements AutoCloseable {
        final AppPreparedSessionTest.Time clock=new AppPreparedSessionTest.Time();
        final AppPreparedSessionTest.Wire wire=new AppPreparedSessionTest.Wire();
        final AtomicInteger reads=new AtomicInteger(),hardware=new AtomicInteger();
        final AppPreparedSession session;
        AndroidPersistentMediaPeer peer;
        final String failedAt;final Throwable failure;
        Flow(String at,Throwable failure)throws Exception {
            this.failedAt=at;this.failure=failure;
            File files=temporary.newFolder();
            RtcOffer offer=RtcOffer.parse(AppPreparedSessionTest.offer(clock),URI.create("https://v.elfradio.net"),clock.wall());
            session=new AppPreparedSession(files,offer,clock,wire,new PreparedSessionPort.Factory(){
                public PreparedSessionPort.Peer createPeer(PreparedSessionPort.Events events){
                    peer=new AndroidPersistentMediaPeer(new AndroidPersistentMediaPeer.Backend(){
                        long operation;
                        public void open(AndroidPersistentMediaPeer owner,Cancellation cancel){}
                        public JSONObject publish(JSONObject value){return new JSONObject();}
                        public void applyPublish(JSONObject value){}
                        public JSONObject subscribe(JSONObject value){return null;}
                        public void validate(){peer.ice(true);}
                        public boolean idle(){return true;}
                        public void activate(long op,String mode,String camera)throws Exception{operation=op;hardware.incrementAndGet();hit("BACKEND_ACTIVATE");}
                        public boolean ready(long op,String mode){return op==operation;}
                        public void softStop(){}
                        public void stop(long end){}
                        public void switchCamera(long op,String camera){}
                        public JSONObject snapshot()throws Exception{reads.incrementAndGet();return new JSONObject().put("operation",operation).put("input_identity","PRIVATE_PATH_TOKEN");}
                        public void close(){}
                    },new AndroidPersistentMediaPeer.Route(){
                        public void beforeActivate(long op,String mode)throws Exception{hit("BEFORE_ACTIVATE");}
                        public void open(long op)throws Exception{hit("ROUTE_OPEN");}
                        public void close(long op){}
                    },events);return peer;
                }
                public PreparedSessionPort.Extra createExtra(PreparedSessionPort.Operation op,PreparedSessionPort.OperationEvents events){throw new AssertionError();}
            });
        }
        void hit(String at)throws Exception{
            if(!failedAt.equals(at))return;
            if(failure instanceof Error)throw (Error)failure;
            throw (Exception)failure;
        }
        JSONObject run()throws Exception{
            session.start();AppPreparedSessionTest.await(wire.connected);
            session.receive(new JSONObject().put("type","hello").put("mode","prepare").toString());
            if("ROUTE_OPEN".equals(failedAt)){assertTrue(session.awaitClosed(3000));return session.snapshot();}
            session.receive(new JSONObject().put("type","tracks").put("sessionId","remote-test")
                    .put("tracks",new JSONArray().put(new JSONObject().put("location","remote").put("sessionId","remote-test").put("trackName","audio"))).toString());
            AppPreparedSessionTest.until(()->"idle".equals(session.snapshot().optString("state")));
            session.receive(new JSONObject().put("type","activate").put("operation",1).put("mode","ptt")
                    .put("camera","front").put("report_id","prepared-test-1").toString());
            assertTrue(session.awaitClosed(3000));return session.snapshot();
        }
        public void close()throws Exception{session.stop();assertTrue(session.awaitClosed(3000));}
    }
    void verify(String at,Throwable failure,String code)throws Exception {
        try(Flow flow=new Flow(at,failure)){
            JSONObject value=flow.run(),details=value.getJSONObject("diagnostics");
            assertEquals(code,value.getString("reason"));assertEquals(at,details.getString("failed_stage"));
            assertFalse(details.has("exception_type"));assertTrue(value.getBoolean("cleanup_complete"));
            assertEquals(0,flow.wire.count("ready"));assertFalse(value.toString().contains("PRIVATE"));
            assertEquals("BACKEND_ACTIVATE".equals(at)?1:0,flow.hardware.get());
            int reads=flow.reads.get();
            flow.peer.fail("MEDIA_LATE_ERROR");
            for(int i=0;i<10;i++){
                assertEquals(code,flow.peer.snapshot().getString("error"));
                assertEquals(at,flow.session.snapshot().getJSONObject("diagnostics").getString("failed_stage"));
            }
            assertEquals(reads,flow.reads.get());
        }
    }
    @Test public void everyKnownBusyReasonSurvivesFirstFailureAndCleanup()throws Exception {
        for(String reason:new String[]{"CELLULAR_CALL_ACTIVE","NEXUI_SESSION_ACTIVE","GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED"})
            verify("BEFORE_ACTIVATE",new IOException("MEDIA_AUDIO_BUSY:"+reason),"MEDIA_AUDIO_BUSY_"+reason);
    }
    @Test public void everyKnownUnknownReasonSurvivesFirstFailureAndCleanup()throws Exception {
        for(String reason:new String[]{"CLOSED","NOT_STARTED","NO_SAMPLE","STALE_SAMPLE","SAMPLE_DEADLINE","SOURCE_READ_FAILED",
                "SOURCE_API_UNAVAILABLE","CELLULAR_COVERAGE_UNKNOWN","NEXUI_STATE_UNKNOWN","GLOBAL_AUDIO_COVERAGE_UNKNOWN"})
            verify("BEFORE_ACTIVATE",new IOException("MEDIA_AUDIO_UNKNOWN:"+reason),"MEDIA_AUDIO_UNKNOWN_"+reason);
    }
    @Test public void routeStageRetainsControlledNestedCause()throws Exception {
        verify("ROUTE_OPEN",new ExecutionException(new IOException("MEDIA_AUDIO_UNKNOWN:STALE_SAMPLE")),"MEDIA_AUDIO_UNKNOWN_STALE_SAMPLE");
    }
    @Test public void nativeExceptionHasOnlyFallbackCodeAndHardwareStage()throws Exception {
        verify("BACKEND_ACTIVATE",new IllegalStateException("PRIVATE native disposed path token"),"MEDIA_PERSISTENT_OPERATION_FAILED");
        verify("BACKEND_ACTIVATE",new NoSuchMethodError("PRIVATE native symbol"),"MEDIA_PERSISTENT_OPERATION_FAILED");
    }
    @Test public void unknownGuardSuffixAndCustomClassNamesNeverEscape()throws Exception {
        for(String message:new String[]{"MEDIA_AUDIO_BUSY:PRIVATE_TOKEN","MEDIA_AUDIO_UNKNOWN:CLOSED:PRIVATE_PATH","MEDIA_AUDIO_BUSY:GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED\nPRIVATE"})
            verify("BEFORE_ACTIVATE",new IOException(message),"MEDIA_PERSISTENT_OPERATION_FAILED");
        verify("ROUTE_OPEN",new PrivateOwnerException(),"MEDIA_PERSISTENT_OPERATION_FAILED");
    }
    @Test public void controlledRemoteFailureIsNotMisclassifiedAsGuard()throws Exception {
        verify("BACKEND_ACTIVATE",new IOException("MEDIA_PERSISTENT_REMOTE_INVALID"),"MEDIA_PERSISTENT_REMOTE_INVALID");
    }
}
