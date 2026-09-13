package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用真实占用采样线程复现警报停止后策略层迟到释放，不访问设备。 */
public class PreparedAlarmIdleTest {
    private final MediaCapture.Clock clock=new MediaCapture.Clock(){
        public long wall(){return System.currentTimeMillis();}
        public long elapsed(){return System.nanoTime()/1000000;}
    };
    private static final class Local implements AndroidAudioOccupancy.LocalOutput {
        boolean confirmed=true,change;
        long generation=1;
        public long generation(){return generation;}
        public void requireMutedOutput()throws Exception {
            if(!confirmed)throw new IOException("MEDIA_OUTPUT_LOCAL_PROOF_MISSING");
            if(change)generation++;
        }
    }
    private static JSONObject sample(boolean alarm)throws Exception {
        JSONObject raw=AndroidAudioOccupancyTest.idle();
        raw.getJSONObject("audio").put("focus_gain",2);
        raw.getJSONObject("audio").getJSONArray("streams").getJSONObject(3).put("active",true);
        raw.getJSONObject("audio").getJSONArray("streams").getJSONObject(4).put("active",alarm);
        return raw;
    }
    private AndroidAudioOccupancy start(AndroidAudioOccupancy.Source source)throws Exception {
        AndroidAudioOccupancy occupancy=new AndroidAudioOccupancy(()->{
            // 首缓存起点严格早于停止边界，避免毫秒精度将两次事件压在同一刻。
            TimeUnit.MILLISECONDS.sleep(2);return source.read();
        },clock::elapsed).start();
        assertTrue(occupancy.awaitFirstSample(1000));return occupancy;
    }
    private void denied(AndroidAudioOccupancy occupancy,Local local,long budget)throws Exception {
        long began=System.nanoTime();
        try{PreparedSessionAndroid.awaitAlarmIdle(occupancy,local,clock,budget);fail("未空闲不得完成停止");}
        catch(IOException expected){assertEquals("MEDIA_PREPARED_IDLE_UNCONFIRMED",expected.getMessage());}
        assertTrue("共用截止时间不能逐轮重置",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<budget+500);
    }
    @Test public void busyFirstPostStopSampleIsRefreshedBeforeNextAlarmAdmission()throws Exception {
        AtomicInteger reads=new AtomicInteger();Local local=new Local();
        try(AndroidAudioOccupancy occupancy=start(()->sample(reads.incrementAndGet()<=2))){
            PreparedSessionAndroid.awaitAlarmIdle(occupancy,local,clock,1500);
            assertTrue(reads.get()>=3);
            JSONObject projected=occupancy.projectMutedOutput(local);
            assertFalse(projected.getJSONObject("audio").getJSONArray("streams").getJSONObject(4).getBoolean("active"));
            // 下一操作的新monitor首次读取直接复用最终空闲缓存。
            try(AndroidAudioOccupancy next=new AndroidAudioOccupancy(()->occupancy.projectMutedOutput(local),clock::elapsed).start()){
                assertTrue(next.awaitFirstSample(1000));next.requireIdle();
            }
        }
    }
    @Test public void preStopIdleCannotOverrideFreshBusyAndPollingIsBounded()throws Exception {
        AtomicInteger reads=new AtomicInteger();
        try(AndroidAudioOccupancy occupancy=start(()->sample(reads.incrementAndGet()>1))){
            denied(occupancy,new Local(),180);
            assertTrue(reads.get()>=2);assertTrue("不允许忙循环重复读取",reads.get()<=4);
        }
    }
    @Test public void otherStreamsSourcesCallsAndUnknownRemainRejected()throws Exception {
        for(int variant=0;variant<6;variant++){
            final int selected=variant;
            try(AndroidAudioOccupancy occupancy=start(()->{
                JSONObject raw=sample(false),audio=raw.getJSONObject("audio");
                switch(selected){
                    case 0:audio.getJSONArray("streams").getJSONObject(4).put("remote_active",true);break;
                    case 1:audio.getJSONArray("streams").getJSONObject(2).put("active",true);break;
                    case 2:audio.getJSONArray("sources").getJSONObject(1).put("active",true);break;
                    case 3:raw.getJSONObject("cellular").put("call_state",1);break;
                    case 4:raw.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");break;
                    default:audio.remove("sources");
                }
                return raw;
            })){denied(occupancy,new Local(),60);}
        }
    }
    @Test public void missingOrChangedMutedOutputProofCannotReleaseIdle()throws Exception {
        for(boolean changed:new boolean[]{false,true}){
            Local local=new Local();local.confirmed=changed;local.change=changed;
            try(AndroidAudioOccupancy occupancy=start(()->sample(false))){denied(occupancy,local,60);}
        }
    }
    @Test public void blockedReadUsesSharedBudgetAndNeverCreatesReplacementReader()throws Exception {
        AtomicInteger reads=new AtomicInteger();CountDownLatch unblock=new CountDownLatch(1),exited=new CountDownLatch(1);
        AndroidAudioOccupancy occupancy=start(()->{
            if(reads.incrementAndGet()>1)try{unblock.await();}finally{exited.countDown();}
            return sample(false);
        });
        try{
            denied(occupancy,new Local(),120);assertEquals(2,reads.get());
            assertEquals(1,exited.getCount());
        }finally{unblock.countDown();occupancy.close();assertTrue(exited.await(1,TimeUnit.SECONDS));}
    }
}
