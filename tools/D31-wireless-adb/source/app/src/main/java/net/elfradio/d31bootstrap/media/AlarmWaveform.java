package net.elfradio.d31bootstrap.media;

/** 与D22相同的880/1320Hz双音，每350毫秒交替并在边界淡入淡出。 */
final class AlarmWaveform {
    static final int RATE=16000;
    /** 一个可无缝循环片段的帧数：四段350毫秒交替音，合计1.4秒。 */
    static final int FRAMES=RATE*35/100*4;

    /** 一个循环片段的毫秒长度；帧数来自samples()，按采样率换算。 */
    static int loopMs(int frames){
        if(frames<=0)throw new IllegalArgumentException("ALARM_LOOP_FRAMES_INVALID");
        return (int)Math.max(1,(long)frames*1000/RATE);
    }

    /**
     * 原生层兜底的循环次数，语义与AudioTrack.setLoopPoints的第三参数一致：
     * 它是「再重复几遍」，0表示不循环（片段仍播放一遍），-1表示无限循环。
     * 因此总播放遍数为返回值加一，本函数按总遍数向上取整后减一。
     * 取整方向为宁可多播一个片段也不截断请求时长；上层定时器正常时仍按其时长停止。
     * 任何输入都必须返回有限非负数，不得返回-1。
     */
    static int loopCount(int durationMs,int loopMs){
        if(loopMs<=0)throw new IllegalArgumentException("ALARM_LOOP_LENGTH_INVALID");
        if(durationMs<=0)return 0;
        long passes=((long)durationMs+loopMs-1)/loopMs;
        return (int)Math.max(0,Math.min(Integer.MAX_VALUE,passes-1));
    }

    static short[] samples(){
        int segment=RATE*35/100;
        short[] samples=new short[FRAMES];
        for(int i=0;i<samples.length;i++){
            double frequency=(i/segment)%2==0?880:1320;
            int local=i%segment;
            double fade=Math.max(0,Math.min(1,Math.min(local,segment-1-local)/160.0));
            samples[i]=(short)(Math.sin(2*Math.PI*frequency*i/RATE)*22000*fade);
        }
        return samples;
    }
}
