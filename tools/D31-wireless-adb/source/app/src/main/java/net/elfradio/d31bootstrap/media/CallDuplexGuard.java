package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

/** 双向守卫接线合同；不复用单向解析的空闲豁免，回调路径只能读预计算缓存。 */
public interface CallDuplexGuard {
    long MAX_SAMPLE_MS=1500, MAX_AGE_MS=3500;
    /** 调用者已取得媒体租约；核验独立新鲜idle、MODE_NORMAL、免提、STREAM_MUSIC及焦点。 */
    void requireIdleSpeakerRoute() throws Exception;
    /** 禁止Binder、文件、网络或等待；证据应同时核验两源、其它owner、电话及焦点代次。 */
    Evidence current(Identity input, Identity output);

    final class Identity {
        public final boolean input;
        public final int pid, session, rate, channels, format, sourceOrStream;
        public Identity(boolean input,int pid,int session,int rate,int channels,int format,int sourceOrStream)throws IOException {
            if(pid<=0||session<=0||channels!=1||format!=2||rate!=(input?16000:48000)||sourceOrStream!=(input?1:3))
                throw new IOException("MEDIA_CALL_IDENTITY_FORMAT_INVALID");
            this.input=input;this.pid=pid;this.session=session;this.rate=rate;this.channels=channels;this.format=format;this.sourceOrStream=sourceOrStream;
        }
        boolean same(Identity other){return other!=null&&input==other.input&&pid==other.pid&&session==other.session&&rate==other.rate
                &&channels==other.channels&&format==other.format&&sourceOrStream==other.sourceOrStream;}
        JSONObject privateJson()throws Exception{return new JSONObject().put("pid",pid).put("audio_session",session)
                .put("sample_rate",rate).put("channels",channels).put("audio_format",format)
                .put(input?"audio_source":"stream_type",sourceOrStream);}
    }
    /** 原样保留peer提供的身份对象；新观察/失焦/owner变化必须换证据对象或返回null。 */
    final class Evidence {
        public final Identity input, output;
        public final boolean inputOwned, outputOwned;
        public final long began, finished;
        public Evidence(Identity input,Identity output,boolean inputOwned,boolean outputOwned,long began,long finished){
            this.input=input;this.output=output;this.inputOwned=inputOwned;this.outputOwned=outputOwned;this.began=began;this.finished=finished;
        }
        boolean valid(Identity input,Identity output,long now){
            return input!=null&&output!=null&&input.input&&!output.input&&input.pid==output.pid
                    &&this.input==input&&this.output==output&&began>=0&&finished>=began&&finished<=now
                    &&finished-began<=MAX_SAMPLE_MS&&now-began<=MAX_AGE_MS;
        }
    }
}
