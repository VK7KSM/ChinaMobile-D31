package net.elfradio.d31bootstrap.media;

/** 与D22相同的880/1320Hz双音，每350毫秒交替并在边界淡入淡出。 */
final class AlarmWaveform {
    static final int RATE=16000;
    static short[] samples(){
        int segment=RATE*35/100;
        short[] samples=new short[segment*4];
        for(int i=0;i<samples.length;i++){
            double frequency=(i/segment)%2==0?880:1320;
            int local=i%segment;
            double fade=Math.max(0,Math.min(1,Math.min(local,segment-1-local)/160.0));
            samples[i]=(short)(Math.sin(2*Math.PI*frequency*i/RATE)*22000*fade);
        }
        return samples;
    }
}
