package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;

/** 只保留静音处理后PCM16的累计数值，不留声音字节，不参与录音授权。 */
final class AppMediaPcmStats {
    private long callbacks,frames,samples,invalidCallbacks,guardReadyCallbacks;
    private int peak;
    private double squares;
    synchronized void accept(byte[] data,int format,int channels,int rate,boolean guardReady){
        callbacks++;
        if(data==null||format!=2||channels!=1||rate!=16000||data.length==0||data.length>8192||data.length%2!=0){
            invalidCallbacks++;return;
        }
        if(guardReady)guardReadyCallbacks++;
        int count=data.length/2;
        for(int i=0;i<data.length;i+=2){
            int value=(short)((data[i]&255)|(data[i+1]<<8));
            peak=Math.max(peak,Math.abs(value));squares+=(double)value*value;
        }
        samples+=count;frames+=count/channels;
    }
    synchronized JSONObject snapshot()throws Exception{
        return new JSONObject().put("scope","PCM16_CALLBACK_AFTER_WEBRTC_SOFTWARE_MUTE")
                .put("callbacks",callbacks).put("pcm_frames",frames).put("samples",samples)
                .put("guard_ready_callbacks",guardReadyCallbacks).put("invalid_callbacks",invalidCallbacks)
                .put("peak",peak/32768.0).put("rms",samples==0?0:Math.sqrt(squares/samples)/32768.0)
                .put("contains_audio",false).put("used_for_authorization",false);
    }
}
