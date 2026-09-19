package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 警报原生层兜底次数。上层AlarmTasks的定时器一旦失效（进程冻结、Handler队列卡死、异常路径漏掉removeCallbacks），
 * AudioTrack的无限循环会让设备一直响；这里断言取整不截断，且任何输入都不会退回无限循环。
 * 次数语义与AudioTrack.setLoopPoints第三参数一致：它是「再重复几遍」，总播放遍数为次数加一。
 */
public class AlarmLoopCountTest {
    private static final int LOOP_MS=AlarmWaveform.loopMs(AlarmWaveform.FRAMES);

    @Test public void roundsUpSoPlaybackIsNeverCutShort(){
        // 片段长度固定1.4秒：10秒警报再重复7遍，总共8段11.2秒；重复8遍会变成12.6秒，多于一个片段的余量。
        assertEquals(1400,LOOP_MS);
        assertEquals(7,AlarmWaveform.loopCount(AlarmTasks.DURATION_MS,LOOP_MS));
        assertEquals(21,AlarmWaveform.loopCount(AndroidAlarm.REPEAT_BURST_MS,LOOP_MS));
        for(int durationMs:new int[]{1,1399,1400,1401,2800,2801,9999,10000,30000,600000}){
            int count=AlarmWaveform.loopCount(durationMs,LOOP_MS);
            long playedMs=(long)(count+1)*LOOP_MS;
            assertTrue("播放不得早于请求时长结束 durationMs="+durationMs,playedMs>=durationMs);
            assertTrue("余量不得多于一个片段 durationMs="+durationMs,playedMs-LOOP_MS<durationMs);
        }
    }

    @Test public void alwaysFiniteAndNeverTheInfiniteSentinel(){
        // AudioTrack.setLoopPoints 用 -1 表示无限循环，0 表示不循环；任何输入都不得返回 -1 或负数。
        for(int durationMs:new int[]{Integer.MIN_VALUE,-1,0,1,10000,Integer.MAX_VALUE}){
            int count=AlarmWaveform.loopCount(durationMs,LOOP_MS);
            assertNotEquals("不得退回无限循环 durationMs="+durationMs,-1,count);
            assertTrue("次数必须为有限非负数 durationMs="+durationMs,count>=0&&count<Integer.MAX_VALUE);
        }
        try{AlarmWaveform.loopCount(10000,0);fail("片段长度为0应拒绝");}catch(IllegalArgumentException expected){}
        try{AlarmWaveform.loopMs(0);fail("帧数为0应拒绝");}catch(IllegalArgumentException expected){}
    }
}
