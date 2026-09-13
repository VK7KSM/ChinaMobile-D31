package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppPreparedExtrasTest {
    static void await(CountDownLatch latch)throws Exception {assertTrue("bounded wait",latch.await(2,TimeUnit.SECONDS));}
    static final class Events implements PreparedSessionPort.OperationEvents {
        final CountDownLatch changed=new CountDownLatch(1),result=new CountDownLatch(1),failed=new CountDownLatch(1),preview=new CountDownLatch(1),ended=new CountDownLatch(1);
        final List<JSONObject> messages=new CopyOnWriteArrayList<>();
        volatile String error;
        public void changed(){changed.countDown();}
        public void failed(String code){error=code;failed.countDown();}
        public void message(JSONObject value){messages.add(value);String type=value.optString("type");
            if("result".equals(type))result.countDown();if("photo_preview".equals(type))preview.countDown();
            if("响铃已结束".equals(value.optString("message")))ended.countDown();}
    }
    static class Backend implements AppPreparedExtras.Backend {
        final CountDownLatch entered=new CountDownLatch(1),upload=new CountDownLatch(1),cancelEntered=new CountDownLatch(1),cancelGate=new CountDownLatch(1),closed=new CountDownLatch(1);
        final AtomicInteger closeCalls=new AtomicInteger();
        volatile String reportId,camera,state="playing";
        volatile boolean blockPhoto,blockCancel,closeFails,badResult,earlyResult,lateCapture;
        public JSONObject photo(String id,String camera,Cancellation cancel,AppPreparedExtras.Captured captured)throws Exception {
            reportId=id;this.camera=camera;entered.countDown();
            if(lateCapture)upload.await();
            if(!earlyResult)captured.captured(new JSONObject().put("report_id",id).put("state","completed"));
            if(blockPhoto)upload.await();
            return new JSONObject().put("type","result").put("report_id",badResult?"other-99":id).put("captured_at",123L);
        }
        public void alarm(String id)throws Exception{reportId=id;entered.countDown();}
        public String alarmState(){return state;}
        public void cancel(){cancelEntered.countDown();if(blockCancel)try{cancelGate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        public void close()throws Exception{closeCalls.incrementAndGet();closed.countDown();if(closeFails)throw new IOException("private cleanup detail");}
    }
    static PreparedSessionPort.Operation op(long n,String mode){return new PreparedSessionPort.Operation(n,mode,"front","session-"+n);}
    static AppPreparedExtras extra(PreparedSessionPort.Operation op,Events e,Backend b,long timeout)throws Exception {
        return new AppPreparedExtras(op,e,()->b,receipt->new JSONObject().put("type","photo_preview").put("jpeg","/9j/2Q==").put("captured_at",123L),timeout);
    }
    @Test public void captureReadyAndPreviewPrecedeUploadResultWithSameOperation()throws Exception {
        Backend b=new Backend();b.blockPhoto=true;Events e=new Events();AppPreparedExtras x=extra(op(7,"photo"),e,b,1000);
        try{x.start();await(e.preview);assertTrue(x.ready());assertEquals(1,e.result.getCount());
            b.upload.countDown();await(e.result);assertEquals("session-7",b.reportId);assertEquals("front",b.camera);
            assertEquals(2,e.messages.size());for(JSONObject row:e.messages)assertEquals(7,row.getLong("operation"));
            assertEquals("session-7",e.messages.get(1).getString("report_id"));assertTrue(x.ready());
        }finally{b.upload.countDown();x.close();}assertEquals(1,b.closeCalls.get());
    }
    @Test public void cancelDuringUploadSuppressesLateResultAndCanCreateNextOperation()throws Exception {
        Backend b=new Backend();b.blockPhoto=true;Events e=new Events();AppPreparedExtras x=extra(op(1,"photo"),e,b,1000);
        x.start();await(e.preview);x.cancel();b.upload.countDown();x.close();assertFalse(x.ready());assertEquals(1,e.result.getCount());assertNull(e.error);
        Backend next=new Backend();Events n=new Events();AppPreparedExtras y=extra(op(2,"photo"),n,next,1000);
        try{y.start();await(n.result);assertEquals("session-2",next.reportId);assertEquals(2,n.messages.get(1).getLong("operation"));}finally{y.close();}
    }
    @Test public void cancelBeforeLateCaptureSuppressesReadyPreviewAndResult()throws Exception {
        Backend b=new Backend();b.lateCapture=true;Events e=new Events();AppPreparedExtras x=extra(op(3,"photo"),e,b,1000);
        x.start();await(b.entered);x.cancel();b.upload.countDown();x.close();assertFalse(x.ready());assertTrue(e.messages.isEmpty());assertNull(e.error);
    }
    @Test public void cancellingUncooperativeWorkIsNonBlockingAndCloseCannotClaimReleased()throws Exception {
        Backend b=new Backend();b.blockPhoto=true;Events e=new Events();AppPreparedExtras x=extra(op(4,"photo"),e,b,30);
        x.start();await(e.preview);CountDownLatch returned=new CountDownLatch(1);Thread t=new Thread(()->{x.cancel();returned.countDown();});t.start();await(returned);
        try{x.close();fail();}catch(IOException expected){assertEquals("VISUAL_CLEANUP_PENDING",expected.getMessage());}
        assertEquals(0,b.closeCalls.get());b.upload.countDown();await(b.closed);x.close();assertEquals(1,b.closeCalls.get());
    }
    @Test public void closeFailureIsStickyAndSanitized()throws Exception {
        Backend b=new Backend();b.closeFails=true;Events e=new Events();AppPreparedExtras x=extra(op(5,"photo"),e,b,1000);
        x.start();await(e.result);for(int i=0;i<2;i++)try{x.close();fail();}catch(IOException expected){assertEquals("VISUAL_CLEANUP_PENDING",expected.getMessage());}
        assertEquals(1,b.closeCalls.get());
    }
    @Test public void closeBeforeStartDoesNotConstructBackend()throws Exception {
        AtomicInteger creations=new AtomicInteger();Events e=new Events();
        AppPreparedExtras x=new AppPreparedExtras(op(6,"photo"),e,()->{creations.incrementAndGet();return new Backend();},r->null,1000);
        x.close();assertEquals(0,creations.get());try{x.start();fail();}catch(IOException expected){assertEquals("PREPARED_EXTRA_NOT_STARTABLE",expected.getMessage());}
    }
    @Test public void cancellationWhileFactoryReturnsStillClosesNewBackend()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),gate=new CountDownLatch(1);Backend b=new Backend();Events e=new Events();
        AppPreparedExtras x=new AppPreparedExtras(op(8,"photo"),e,()->{entered.countDown();gate.await();return b;},r->null,1000);
        x.start();await(entered);x.cancel();gate.countDown();x.close();assertEquals(1,b.closeCalls.get());assertEquals(1,b.entered.getCount());assertTrue(e.messages.isEmpty());
    }
    @Test public void mismatchedResultCannotBeRelabelled()throws Exception {
        Backend b=new Backend();b.badResult=true;Events e=new Events();AppPreparedExtras x=extra(op(9,"photo"),e,b,1000);
        try{x.start();await(e.failed);assertEquals("VISUAL_PHOTO_RESULT_BINDING",e.error);assertEquals(1,e.result.getCount());}finally{x.close();}
    }
    @Test public void resultWithoutCaptureDoesNotBecomeReady()throws Exception {
        Backend b=new Backend();b.earlyResult=true;Events e=new Events();AppPreparedExtras x=extra(op(10,"photo"),e,b,1000);
        try{x.start();await(e.failed);assertFalse(x.ready());assertTrue(e.messages.isEmpty());}finally{x.close();}
    }
    @Test public void alarmNaturalCompletionKeepsReadyWithoutIdleUntilClose()throws Exception {
        Backend b=new Backend();Events e=new Events();AppPreparedExtras x=extra(op(11,"alarm"),e,b,1000);
        try{x.start();await(e.changed);assertTrue(x.ready());assertEquals("session-11",b.reportId);b.state="completed";await(e.ended);
            assertTrue(x.ready());assertEquals(0,b.closeCalls.get());for(JSONObject row:e.messages)assertEquals("status",row.getString("type"));
        }finally{x.close();}assertEquals(1,b.closeCalls.get());
    }
    @Test public void alarmInterruptionFailsAndCloseStillReleases()throws Exception {
        Backend b=new Backend();b.state="failed";Events e=new Events();AppPreparedExtras x=extra(op(12,"alarm"),e,b,1000);
        try{x.start();await(e.failed);assertEquals("VISUAL_ALARM_FAILED",e.error);}finally{x.close();}assertEquals(1,b.closeCalls.get());
    }
    @Test public void privateExceptionDetailsNeverReachOperationEvents()throws Exception {
        Events e=new Events();AppPreparedExtras x=new AppPreparedExtras(op(13,"photo"),e,()->{throw new IOException("private-token=device-value");},r->null,1000);
        try{x.start();await(e.failed);assertEquals("VISUAL_OPERATION_FAILED",e.error);assertTrue(e.messages.isEmpty());}finally{x.close();}
    }
    @Test public void switchingPhotoDoesNotCreateAnotherCapture()throws Exception {
        Backend b=new Backend();Events e=new Events();AppPreparedExtras x=extra(op(14,"photo"),e,b,1000);
        try{x.start();await(e.result);x.switchCamera("back");assertEquals("front",b.camera);assertEquals("front",e.messages.get(2).getString("camera"));}finally{x.close();}
    }
    @Test public void longReportIdIsRejectedWithoutTruncatingOrHashing()throws Exception {
        String id=new String(new char[97]).replace('\0','a');
        try{extra(new PreparedSessionPort.Operation(1,"photo","front",id),new Events(),new Backend(),1000);fail();}
        catch(IOException expected){assertEquals("PREPARED_EXTRA_OPERATION_INVALID",expected.getMessage());}
    }
    @Test public void blockedPlatformCancelDoesNotBlockCallerOrPermitFalseCleanup()throws Exception {
        Backend b=new Backend();b.blockCancel=true;Events e=new Events();AppPreparedExtras x=extra(op(15,"photo"),e,b,30);
        x.start();await(e.result);CountDownLatch returned=new CountDownLatch(1);
        new Thread(()->{x.cancel();returned.countDown();}).start();await(returned);await(b.cancelEntered);
        try{x.close();fail();}catch(IOException expected){assertEquals("VISUAL_CLEANUP_PENDING",expected.getMessage());}
        assertEquals(0,b.closeCalls.get());b.cancelGate.countDown();await(b.closed);x.close();
    }
    static RtcOffer offer(JSONObject identity)throws Exception {
        return RtcOffer.parse(new JSONObject().put("session_id","session").put("mode","prepare").put("camera","front")
                .put("expires_at",110000).put("token","fixture_session_token")
                .put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=session").put("_credentials",identity),
                new java.net.URI("https://v.elfradio.net"),100000);
    }
    @Test public void uploadIdentityIsCopiedFromParentWithoutChangingItsWsOffer()throws Exception {
        JSONObject identity=new JSONObject().put("device_id","fixture_device").put("token","fixture_upload");RtcOffer offer=offer(identity);
        identity.put("token","changed_fixture");JSONObject copy=AppPreparedExtras.uploadIdentity(offer,op(16,"photo"));
        assertEquals("fixture_upload",copy.getString("token"));copy.put("token","changed_copy");
        assertEquals("fixture_upload",AppPreparedExtras.uploadIdentity(offer,op(17,"photo")).getString("token"));
        assertEquals("prepare",offer.mode);assertEquals("session_id=session",offer.uri.getQuery());assertEquals("fixture_session_token",offer.token);
    }
    @Test public void wrongSessionReportCannotBorrowUploadIdentity()throws Exception {
        RtcOffer offer=offer(new JSONObject().put("device_id","fixture").put("token","fixture"));
        try{AppPreparedExtras.uploadIdentity(offer,new PreparedSessionPort.Operation(1,"photo","front","other-1"));fail();}
        catch(IOException expected){assertEquals("PREPARED_EXTRA_BINDING_INVALID",expected.getMessage());}
    }
    @Test public void missingOrInvalidRootIdentityFailsWithoutPrivateErrorText()throws Exception {
        JSONObject[] identities={null,new JSONObject(),new JSONObject().put("device_id","invalid/private").put("token","private-value"),
                new JSONObject().put("device_id","fixture").put("token","private\r\nvalue")};
        for(JSONObject identity:identities)try{AppPreparedExtras.uploadIdentity(offer(identity),op(1,"photo"));fail();}
        catch(IOException expected){assertEquals("VISUAL_IDENTITY_INVALID",expected.getMessage());assertNull(expected.getCause());}
    }
}
