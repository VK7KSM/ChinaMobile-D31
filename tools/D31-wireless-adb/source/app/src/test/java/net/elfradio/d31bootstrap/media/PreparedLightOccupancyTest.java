package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 本地证明由合成回调提供；不访问Flinger、设备或网络。 */
public class PreparedLightOccupancyTest {
    static final class Local implements AndroidAudioOccupancy.LocalOutput {
        long epoch=1;boolean confirmed=true,change;int checks;
        public long generation(){return epoch;}
        public void requireMutedOutput()throws IOException {
            checks++;if(!confirmed)throw new IOException("MEDIA_OUTPUT_LOCAL_PROOF_MISSING");
            if(change)epoch++;
        }
    }
    static JSONObject own()throws Exception {
        JSONObject value=AndroidAudioOccupancyTest.idle();
        value.getJSONObject("audio").put("focus_gain",2);stream(value,3).put("active",true);return value;
    }
    static JSONObject stream(JSONObject value,int stream)throws Exception {
        return value.getJSONObject("audio").getJSONArray("streams").getJSONObject(stream);
    }
    static AndroidAudioOccupancy start(AndroidAudioOccupancy.Source source,AtomicLong now)throws Exception {
        AndroidAudioOccupancy value=new AndroidAudioOccupancy(source,now::get);value.start();
        assertTrue(value.awaitFirstSample(1000));return value;
    }
    interface Checked {void run()throws Exception;}
    static void denied(Checked action)throws Exception {try{action.run();fail("必须拒绝");}catch(IOException expected){}}
    @Test public void projectionUsesCacheAndLeavesOriginalAndSummaryUntouched()throws Exception {
        AtomicLong now=new AtomicLong(100);AtomicInteger reads=new AtomicInteger();JSONObject raw=own();String before=raw.toString();Local local=new Local();
        try(AndroidAudioOccupancy occupancy=start(()->{reads.incrementAndGet();return raw;},now)){
            for(int i=0;i<100;i++){
                JSONObject projected=occupancy.projectMutedOutput(local);
                assertFalse(stream(projected,3).getBoolean("active"));assertEquals(0,projected.getJSONObject("audio").getInt("focus_gain"));
                assertEquals(100,projected.getLong("sample_started_elapsed_ms"));
                projected.getJSONObject("audio").put("mode",99);
                occupancy.requireNoCalls();
            }
            assertEquals(1,reads.get());assertEquals(before,raw.toString());denied(occupancy::requireIdle);
            assertEquals(2,occupancy.projectMutedOutput(local).getJSONObject("audio").getJSONArray("streams").getJSONObject(2).getInt("stream"));
        }
    }
    @Test public void proofMissingLostOrChangedCannotProject()throws Exception {
        try(AndroidAudioOccupancy occupancy=start(PreparedLightOccupancyTest::own,new AtomicLong(100))){
            Local local=new Local();local.confirmed=false;denied(()->occupancy.projectMutedOutput(local));
            local.confirmed=true;local.change=true;denied(()->occupancy.projectMutedOutput(local));
            denied(()->occupancy.projectMutedOutput(null));
        }
    }
    @Test public void foreignFocusRemoteMusicAndMalformedMusicStayRejected()throws Exception {
        for(int change=0;change<4;change++){
            JSONObject raw=own();
            if(change==0)raw.getJSONObject("audio").put("focus_gain",1);
            if(change==1)stream(raw,3).put("remote_active",true);
            if(change==2)stream(raw,3).put("stream","3");
            if(change==3)stream(raw,3).put("active","true");
            try(AndroidAudioOccupancy occupancy=start(()->raw,new AtomicLong(100))){denied(()->occupancy.projectMutedOutput(new Local()));}
        }
    }
    @Test public void otherStreamsInputsPhoneSipAndModeRemainVisibleToAlarm()throws Exception {
        for(int change=0;change<7;change++){
            JSONObject raw=own();stream(raw,4).put("active",true);
            if(change==0)stream(raw,5).put("active",true);
            if(change==1)raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);
            if(change==2)raw.getJSONObject("cellular").put("call_state",1);
            if(change==3)raw.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");
            if(change==4)stream(raw,4).put("remote_active",true);
            if(change==5)raw.getJSONObject("audio").put("mode",3);
            if(change==6)raw.getJSONObject("audio").remove("sources");
            try(AndroidAudioOccupancy occupancy=start(()->raw,new AtomicLong(100))){
                JSONObject projected=occupancy.projectMutedOutput(new Local());assertTrue(stream(projected,4).getBoolean("active"));
                assertNotEquals(AndroidAudioOccupancy.State.IDLE,
                        AndroidAudioOccupancy.evaluate(PhotoAlarmBackend.alarmObservation(projected,true),100,100).overall());
            }
        }
    }
    @Test public void onlyExistingOwnedAlarmMayBeHiddenByItsOriginalMonitor()throws Exception {
        JSONObject raw=own();stream(raw,4).put("active",true);
        try(AndroidAudioOccupancy occupancy=start(()->raw,new AtomicLong(100))){
            JSONObject projected=occupancy.projectMutedOutput(new Local());
            assertEquals(AndroidAudioOccupancy.State.BUSY,AndroidAudioOccupancy.evaluate(PhotoAlarmBackend.alarmObservation(projected,false),100,100).overall());
            assertEquals(AndroidAudioOccupancy.State.IDLE,AndroidAudioOccupancy.evaluate(PhotoAlarmBackend.alarmObservation(projected,true),100,100).overall());
        }
    }
    @Test public void callCheckPermitsOwnDuplexButRejectsBusyUnknownAndStaleCalls()throws Exception {
        JSONObject raw=own();raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);
        AtomicLong now=new AtomicLong(100);
        try(AndroidAudioOccupancy occupancy=start(()->raw,now)){
            occupancy.requireNoCalls();denied(occupancy::requireIdle);
            raw.getJSONObject("cellular").put("call_state",2);occupancy.refresh();denied(occupancy::requireNoCalls);
            raw.getJSONObject("cellular").put("call_state",0);raw.getJSONObject("nexui").getJSONArray("statuses").put(0,"UNKNOWN");
            occupancy.refresh();denied(occupancy::requireNoCalls);
            raw.getJSONObject("nexui").getJSONArray("statuses").put(0,"IDLE");occupancy.refresh();occupancy.requireNoCalls();
            now.set(4101);denied(occupancy::requireNoCalls);denied(()->occupancy.projectMutedOutput(new Local()));
        }
    }
    @Test public void postReleaseRequiresNewPhysicalReadAndNeverUsesProjectedIdle()throws Exception {
        AtomicLong now=new AtomicLong(100);
        try(AndroidAudioOccupancy occupancy=start(AndroidAudioOccupancyTest::idle,now)){
            occupancy.requireIdleSince(100);denied(()->occupancy.requireIdleSince(101));
            now.set(101);occupancy.refresh();occupancy.requireIdleSince(101);
            denied(()->occupancy.requireIdleSince(-1));
        }
        try(AndroidAudioOccupancy occupancy=start(PreparedLightOccupancyTest::own,now)){
            occupancy.projectMutedOutput(new Local());denied(()->occupancy.requireIdleSince(100));
        }
    }
    @Test public void downstreamAlarmCacheCannotRenewOriginalObservationAge()throws Exception {
        AtomicLong now=new AtomicLong(100);Local local=new Local();
        try(AndroidAudioOccupancy upstream=start(PreparedLightOccupancyTest::own,now)){
            now.set(3000);
            try(AndroidAudioOccupancy alarm=start(()->PhotoAlarmBackend.alarmObservation(upstream.projectMutedOutput(local),true),now)){
                alarm.requireIdle();assertEquals(100,alarm.snapshot().getLong("sample_started_elapsed_ms"));
                assertEquals(2900,alarm.snapshot().getLong("age_ms"));
                now.set(4101);denied(alarm::requireIdle);denied(()->upstream.projectMutedOutput(local));
            }
        }
    }
    @Test public void malformedFutureAndPartialSourceTimesNeverBecomeFreshIdle()throws Exception {
        for(int change=0;change<5;change++){
            JSONObject raw=AndroidAudioOccupancyTest.idle().put("sample_started_elapsed_ms",100).put("sample_finished_elapsed_ms",100);
            if(change==0)raw.put("sample_started_elapsed_ms","100");
            if(change==1)raw.put("sample_finished_elapsed_ms",101);
            if(change==2)raw.remove("sample_finished_elapsed_ms");
            if(change==3)raw.put("sample_started_elapsed_ms",-1);
            if(change==4)raw.put("sample_finished_elapsed_ms",99);
            try(AndroidAudioOccupancy occupancy=start(()->raw,new AtomicLong(100))){denied(occupancy::requireIdle);denied(occupancy::requireNoCalls);}
        }
    }
    @Test public void sourceFailureClockRollbackAndCloseRevokeCachedProjection()throws Exception {
        AtomicLong now=new AtomicLong(100);AtomicInteger reads=new AtomicInteger();
        try(AndroidAudioOccupancy occupancy=start(()->{if(reads.incrementAndGet()>1)throw new IOException();return own();},now)){
            occupancy.projectMutedOutput(new Local());now.set(99);denied(()->occupancy.projectMutedOutput(new Local()));
            now.set(100);occupancy.refresh();denied(()->occupancy.projectMutedOutput(new Local()));
        }
        AndroidAudioOccupancy closed=start(PreparedLightOccupancyTest::own,now);closed.close();
        denied(()->closed.projectMutedOutput(new Local()));denied(closed::requireNoCalls);
    }
    @Test public void stoppedMicrophoneRefreshesOldInputBeforeAlarmWithoutExemption()throws Exception {
        JSONObject raw=own();raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);
        AtomicLong now=new AtomicLong(100);AtomicInteger reads=new AtomicInteger();
        try(AndroidAudioOccupancy occupancy=start(()->{reads.incrementAndGet();return raw;},now)){
            raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",false);now.set(101);
            assertTrue(occupancy.projectMutedOutput(new Local()).getJSONObject("audio").getJSONArray("sources").getJSONObject(1).getBoolean("active"));
            occupancy.refreshSince(101,1000);
            JSONObject projected=occupancy.projectMutedOutput(new Local());
            assertEquals(AndroidAudioOccupancy.State.IDLE,AndroidAudioOccupancy.evaluate(projected,101,101).overall());
            assertEquals(2,reads.get());occupancy.refreshSince(101,1000);assertEquals(2,reads.get());
        }
    }
    @Test public void timedOutRefreshKeepsSinglePendingSlotUntilReaderActuallyReturns()throws Exception {
        AtomicLong now=new AtomicLong(100);AtomicInteger reads=new AtomicInteger();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),done=new java.util.concurrent.CountDownLatch(1);
        try(AndroidAudioOccupancy occupancy=start(()->{
            if(reads.incrementAndGet()>1){entered.countDown();try{
                while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            }finally{done.countDown();}}
            return own();
        },now)){
            now.set(101);
            try{
                denied(()->occupancy.refreshSince(101,5));assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
                for(int i=0;i<3;i++)denied(()->occupancy.refreshSince(101,5));
                assertEquals(2,reads.get());release.countDown();occupancy.refreshSince(101,1000);
                assertEquals(2,reads.get());
            }finally{release.countDown();assertTrue(done.await(1,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test public void refreshCannotRebaseOlderCachedSourceOrInventSuccessAfterReadFailure()throws Exception {
        JSONObject raw=own().put("sample_started_elapsed_ms",100).put("sample_finished_elapsed_ms",100);
        AtomicLong now=new AtomicLong(200);AtomicInteger reads=new AtomicInteger();
        try(AndroidAudioOccupancy occupancy=start(()->{if(reads.incrementAndGet()>2)throw new IOException();return raw;},now)){
            denied(()->occupancy.refreshSince(200,1000));assertEquals(100,occupancy.snapshot().getLong("sample_started_elapsed_ms"));
            denied(()->occupancy.refreshSince(200,1000));denied(occupancy::requireNoCalls);
        }
    }
    @Test public void refreshBudgetMainThreadAndLifecycleAreCheckedBeforeAnyRead()throws Exception {
        AtomicInteger reads=new AtomicInteger();
        try(AndroidAudioOccupancy occupancy=new AndroidAudioOccupancy(()->{reads.incrementAndGet();return own();},()->100)){
            denied(()->occupancy.refreshSince(100,1000));
            for(long timeout:new long[]{0,-1,1501,Long.MAX_VALUE}){
                try{occupancy.refreshSince(100,timeout);fail();}catch(IllegalArgumentException expected){}
            }
            assertEquals(0,reads.get());
        }
        try(AndroidAudioOccupancy occupancy=new AndroidAudioOccupancy(()->own(),()->100,Thread.currentThread())){
            try{occupancy.refreshSince(100,1000);fail();}catch(IllegalStateException expected){}
        }
    }
}
