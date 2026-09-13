package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

/** 私有实际播放器身份，禁止由Web消息或公开快照填写。 */
public final class D31OutputIdentity {
    final int pid,session,stream,rate,channels,format;
    private D31OutputIdentity(int pid,int session,int stream,int rate,int channels,int format){
        this.pid=pid;this.session=session;this.stream=stream;this.rate=rate;this.channels=channels;this.format=format;
    }
    public static D31OutputIdentity from(JSONObject value)throws IOException{
        try{
            for(String key:new String[]{"pid","audio_session","stream_type","sample_rate","channels","audio_format"})
                if(!(value.opt(key) instanceof Integer))throw new IllegalArgumentException();
            D31OutputIdentity identity=new D31OutputIdentity(value.getInt("pid"),value.getInt("audio_session"),value.getInt("stream_type"),
                    value.getInt("sample_rate"),value.getInt("channels"),value.getInt("audio_format"));
            if(identity.pid<=0||identity.session<=0)throw new IllegalArgumentException();
            return identity;
        }catch(Exception invalid){throw new IOException("MEDIA_OUTPUT_IDENTITY_INVALID");}
    }
    boolean supported(){return stream==3&&rate==48000&&channels==1&&format==2;}
    boolean same(D31OutputIdentity other){return other!=null&&pid==other.pid&&session==other.session&&stream==other.stream
            &&rate==other.rate&&channels==other.channels&&format==other.format;}
}
