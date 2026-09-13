package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalAudioCaptureTest {
    static final MediaCapture.Clock CLOCK=new MediaCapture.Clock(){public long wall(){return System.currentTimeMillis();}public long elapsed(){return System.nanoTime()/1000000;}};
    static class Recorder implements LocalAudioCapture.Recorder {
        int state=1,recording=1,starts,stops,releases,result=Integer.MAX_VALUE;
        short[] buffer;boolean startFails,stopFails,releaseFails;
        CountDownLatch reading,unblock;
        public int state(){return state;}public int recordingState(){return recording;}public int sessionId(){return 73;}
        public void start(Cancellation cancellation)throws Exception {cancellation.check();starts++;if(startFails)throw new IOException("private-start-failure");recording=3;}
        public int read(short[] samples,int count)throws Exception {
            buffer=samples;
            if(reading!=null){reading.countDown();while(unblock.getCount()!=0){try{unblock.await();}catch(InterruptedException ignored){}}}
            int n=Math.min(count,result);for(int i=0;i<n;i++)samples[i]=321;return n;
        }
        public void stop()throws Exception {stops++;recording=1;if(unblock!=null)unblock.countDown();if(stopFails)throw new IOException("private-stop-failure");}
        public void release()throws Exception {releases++;if(releaseFails)throw new IOException("private-release-failure");state=0;recording=1;}
    }
    static LocalAudioCapture capture(Recorder record,int ms,Cancellation cancellation)throws Exception {
        return new LocalAudioCapture("diag-1",ms,123,10001,c->record,cancellation,CLOCK);
    }
    static JSONObject run(LocalAudioCapture capture)throws Exception {return capture.run(CLOCK.elapsed()+10000);}
    @Test public void boundedSamplesAreDiscardedAndActualSessionAndReleaseReported()throws Exception {
        Recorder record=new Recorder();JSONObject result=run(capture(record,2500,new Cancellation()));
        assertEquals("COMPLETED",result.getString("state"));assertEquals(80000,result.getLong("bytes_read"));assertEquals(40000,result.getLong("frames_read"));
        assertEquals(73,result.getInt("audio_session_id"));assertEquals(123,result.getInt("app_pid"));assertEquals(10001,result.getInt("app_uid"));
        assertTrue(result.getBoolean("initialized"));assertTrue(result.getBoolean("recording_started"));assertTrue(result.getBoolean("release_completed"));
        assertEquals(0,result.getInt("record_state_after_release"));assertEquals(1,result.getInt("recording_state_after_release"));
        assertFalse(result.getBoolean("audio_persisted"));assertFalse(result.getBoolean("network_started"));assertFalse(result.getBoolean("managed_media"));
        assertEquals(1,record.stops);assertEquals(1,record.releases);for(short sample:record.buffer)assertEquals(0,sample);
    }
    @Test public void preflightFailureDoesNotConstructRecorder()throws Exception {
        LocalAudioCapture capture=new LocalAudioCapture("diag",100,1,10001,c->{throw new IOException("MEDIA_LOCAL_AUDIO_PRECONDITIONS");},new Cancellation(),CLOCK);
        JSONObject result=run(capture);assertEquals("FAILED",result.getString("state"));assertFalse(result.getBoolean("audio_record_created"));assertFalse(capture.active());
    }
    @Test public void permissionRejectionAtCreationHasKnownCodeAndNoLiveResource()throws Exception {
        for(Exception failure:new Exception[]{new SecurityException("private-platform-identity"),new IOException("MEDIA_LOCAL_AUDIO_PERMISSION_DENIED")}){
            LocalAudioCapture capture=new LocalAudioCapture("denied",100,1,10001,c->{throw failure;},new Cancellation(),CLOCK);
            JSONObject result=run(capture);assertEquals("FAILED",result.getString("state"));
            assertEquals("MEDIA_LOCAL_AUDIO_PERMISSION_DENIED",result.getString("error_code"));
            assertFalse(result.getBoolean("audio_record_created"));assertFalse(result.getBoolean("recording_started"));
            assertEquals(0,result.getLong("bytes_read"));assertFalse(capture.active());
            assertFalse(result.toString().contains("private-platform-identity"));
        }
    }
    @Test public void permissionRevokedAfterCreationStillReleases()throws Exception {
        Recorder record=new Recorder(){@Override public void start(Cancellation cancellation){throw new SecurityException("private-start-identity");}};
        LocalAudioCapture capture=capture(record,100,new Cancellation());JSONObject result=run(capture);
        assertEquals("FAILED",result.getString("state"));assertEquals("MEDIA_LOCAL_AUDIO_PERMISSION_DENIED",result.getString("error_code"));
        assertTrue(result.getBoolean("audio_record_created"));assertFalse(result.getBoolean("recording_started"));
        assertTrue(result.getBoolean("release_verified"));assertEquals(1,record.releases);assertFalse(capture.active());
        assertFalse(result.toString().contains("private-start-identity"));
    }
    @Test public void invalidInitializationStillReleases()throws Exception {
        Recorder record=new Recorder();record.state=0;JSONObject result=run(capture(record,100,new Cancellation()));
        assertEquals("FAILED",result.getString("state"));assertFalse(result.getBoolean("initialized"));assertEquals(0,record.starts);assertEquals(1,record.releases);
    }
    @Test public void startErrorReleasesWithoutPrivateExceptionText()throws Exception {
        Recorder record=new Recorder();record.startFails=true;JSONObject result=run(capture(record,100,new Cancellation()));
        assertEquals("FAILED",result.getString("state"));assertEquals(1,record.stops);assertEquals(1,record.releases);assertFalse(result.toString().contains("private-start"));
    }
    @Test public void negativeReadPreservesErrorAndReleases()throws Exception {
        Recorder record=new Recorder();record.result=-3;JSONObject result=run(capture(record,100,new Cancellation()));
        assertEquals("FAILED",result.getString("state"));assertEquals(-3,result.getInt("read_error"));assertEquals(0,result.getLong("bytes_read"));assertEquals(1,record.releases);
    }
    @Test public void zeroReadHasBoundedNoDataFailure()throws Exception {
        Recorder record=new Recorder();record.result=0;long began=CLOCK.elapsed();JSONObject result=run(capture(record,25,new Cancellation()));
        assertTrue(CLOCK.elapsed()-began<1500);assertEquals("FAILED",result.getString("state"));assertEquals("MEDIA_LOCAL_AUDIO_NO_DATA",result.getString("error_code"));assertEquals(1,record.releases);
    }
    @Test public void cancellationUnblocksReadAndReleasesExactlyOnce()throws Exception {
        Recorder record=new Recorder();record.reading=new CountDownLatch(1);record.unblock=new CountDownLatch(1);
        LocalAudioCapture capture=capture(record,5000,new Cancellation());ExecutorService executor=Executors.newSingleThreadExecutor();
        try{Future<JSONObject> job=executor.submit(()->run(capture));assertTrue(record.reading.await(1,TimeUnit.SECONDS));capture.cancel();
            JSONObject result=job.get(2,TimeUnit.SECONDS);assertEquals("CANCELLED",result.getString("state"));assertTrue(result.getBoolean("release_completed"));
            assertEquals(1,record.stops);assertEquals(1,record.releases);assertFalse(capture.active());
        }finally{capture.cancel();record.unblock.countDown();executor.shutdownNow();}
    }
    @Test public void cancellationDuringNativeReleaseCannotBecomeCompleted()throws Exception {
        for(boolean rootRequest:new boolean[]{false,true}){
            CountDownLatch releasing=new CountDownLatch(1),allow=new CountDownLatch(1);
            Cancellation request=new Cancellation();
            Recorder record=new Recorder(){@Override public void release()throws Exception{
                releasing.countDown();
                while(allow.getCount()!=0){try{allow.await();}catch(InterruptedException ignored){}}
                super.release();
            }};
            LocalAudioCapture capture=capture(record,10,request);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<JSONObject> job=executor.submit(()->run(capture));
                assertTrue(releasing.await(1,TimeUnit.SECONDS));
                if(rootRequest)request.cancel();else capture.cancel();
                allow.countDown();JSONObject result=job.get(2,TimeUnit.SECONDS);
                assertEquals("CANCELLED",result.getString("state"));
                assertTrue(result.getBoolean("release_verified"));assertFalse(capture.active());
                assertEquals(1,record.stops);assertEquals(1,record.releases);
            }finally{allow.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test public void totalDeadlineStopsReadAndDoesNotWaitFullRequestedDuration()throws Exception {
        Recorder record=new Recorder();record.result=0;long began=CLOCK.elapsed();
        JSONObject result=capture(record,5000,new Cancellation()).run(began+850);
        assertTrue(CLOCK.elapsed()-began<1500);assertEquals("TIMED_OUT",result.getString("state"));assertEquals(1,record.releases);
    }
    @Test public void captureDurationStopsStuckReadBeforeTotalRequestBudget()throws Exception {
        Recorder record=new Recorder();record.reading=new CountDownLatch(1);record.unblock=new CountDownLatch(1);
        long began=CLOCK.elapsed();Cancellation request=new Cancellation();LocalAudioCapture capture=capture(record,40,request);
        JSONObject result=run(capture);assertTrue(CLOCK.elapsed()-began<1500);
        assertTrue(result.getBoolean("duration_deadline_reached"));assertTrue(result.getBoolean("release_verified"));
        assertFalse("正常时长截止不能取消Binder请求并丢弃最终回执",request.isCancelled());
        assertTrue(result.getLong("bytes_read")<=1280);assertEquals(1,record.stops);assertEquals(1,record.releases);
    }
    @Test public void lateConstructorCannotStartAfterDeadlineAndIsEventuallyReleased()throws Exception {
        Recorder record=new Recorder();CountDownLatch entered=new CountDownLatch(1),allow=new CountDownLatch(1);
        LocalAudioCapture capture=new LocalAudioCapture("diag",5000,1,10001,c->{entered.countDown();while(allow.getCount()!=0){try{allow.await();}catch(InterruptedException ignored){}}return record;},new Cancellation(),CLOCK);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{Future<JSONObject> job=executor.submit(()->capture.run(CLOCK.elapsed()+800));assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals("RELEASE_UNCONFIRMED",job.get(2,TimeUnit.SECONDS).getString("state"));assertTrue(capture.active());
            allow.countDown();long deadline=CLOCK.elapsed()+1000;while(capture.active()&&CLOCK.elapsed()<deadline)Thread.sleep(5);
            assertFalse(capture.active());assertEquals(0,record.starts);assertEquals(1,record.releases);
        }finally{allow.countDown();capture.cancel();executor.shutdownNow();}
    }
    @Test public void releaseFailureRetainsBusyGuard()throws Exception {
        Recorder record=new Recorder();record.releaseFails=true;LocalAudioCapture capture=capture(record,10,new Cancellation());JSONObject result=run(capture);
        assertEquals("RELEASE_UNCONFIRMED",result.getString("state"));assertTrue(capture.active());assertFalse(result.getBoolean("release_completed"));assertFalse(result.toString().contains("private-release"));
    }
    @Test public void stopFailureDoesNotSkipRelease()throws Exception {
        Recorder record=new Recorder();record.stopFails=true;JSONObject result=run(capture(record,10,new Cancellation()));
        assertTrue(result.getBoolean("release_completed"));assertEquals("IOException",result.getString("stop_error"));assertEquals(1,record.releases);
    }
    @Test public void nativeReadLinkageErrorStillReleases()throws Exception {
        Recorder record=new Recorder(){@Override public int read(short[] samples,int count){throw new UnsatisfiedLinkError("private-native-detail");}};
        JSONObject result=run(capture(record,100,new Cancellation()));
        assertEquals("FAILED",result.getString("state"));assertTrue(result.getBoolean("release_verified"));assertEquals(1,record.releases);
        assertFalse(result.toString().contains("private-native-detail"));
    }
    @Test public void releaseReturnWithoutUninitializedStateCannotUnlockAnotherCapture()throws Exception {
        Recorder record=new Recorder(){@Override public void release(){releases++;}};
        LocalAudioCapture capture=capture(record,10,new Cancellation());JSONObject result=run(capture);
        assertEquals("RELEASE_UNCONFIRMED",result.getString("state"));assertTrue(result.getBoolean("release_completed"));
        assertFalse(result.getBoolean("release_verified"));assertTrue(capture.active());
    }
    @Test public void callStateChangeTerminatesWithoutGlobalAudioExemption()throws Exception {
        java.util.concurrent.atomic.AtomicLong elapsed=new java.util.concurrent.atomic.AtomicLong();
        MediaCapture.Clock clock=new MediaCapture.Clock(){public long wall(){return 1;}public long elapsed(){return elapsed.get();}};
        Recorder record=new Recorder(){@Override public int read(short[] samples,int count)throws Exception{int n=super.read(samples,count);elapsed.addAndGet(10);return n;}};
        int[] checks={0};long[] times={-1,-1};
        LocalAudioCapture.Factory factory=new LocalAudioCapture.Factory(){public LocalAudioCapture.Recorder open(Cancellation c){return record;}
            public void requireCallsIdle()throws Exception{times[checks[0]]=elapsed.get();if(++checks[0]>1)throw new IOException("MEDIA_LOCAL_AUDIO_CALL_STATE_CHANGED");}};
        JSONObject result=new LocalAudioCapture("diag",5000,1,10001,factory,new Cancellation(),clock).run(10000);
        assertEquals(0,times[0]);assertEquals(100,times[1]);assertEquals(2,checks[0]);
        assertEquals("FAILED",result.getString("state"));assertEquals(6400,result.getLong("bytes_read"));assertTrue(result.getBoolean("release_completed"));
    }
    @Test public void unfinishedStopCannotRaceReleaseOrReportClosed()throws Exception {
        CountDownLatch stopEntered=new CountDownLatch(1),allowStop=new CountDownLatch(1);
        Recorder record=new Recorder(){@Override public void stop()throws Exception{
            stopEntered.countDown();while(allowStop.getCount()!=0){try{allowStop.await();}catch(InterruptedException ignored){}}super.stop();}};
        record.reading=new CountDownLatch(1);record.unblock=new CountDownLatch(1);LocalAudioCapture capture=capture(record,5000,new Cancellation());
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{Future<JSONObject> job=executor.submit(()->run(capture));assertTrue(record.reading.await(1,TimeUnit.SECONDS));capture.cancel();
            assertTrue(stopEntered.await(1,TimeUnit.SECONDS));record.unblock.countDown();
            JSONObject result=job.get(2,TimeUnit.SECONDS);assertEquals("RELEASE_UNCONFIRMED",result.getString("state"));
            assertFalse(result.getBoolean("release_completed"));assertEquals(0,record.releases);assertTrue(capture.active());
            allowStop.countDown();long deadline=CLOCK.elapsed()+1000;while(capture.active()&&CLOCK.elapsed()<deadline)Thread.sleep(5);
            assertFalse(capture.active());assertEquals(1,record.releases);
        }finally{allowStop.countDown();record.unblock.countDown();capture.cancel();executor.shutdownNow();}
    }
}
