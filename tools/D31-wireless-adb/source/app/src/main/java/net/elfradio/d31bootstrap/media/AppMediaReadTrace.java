package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;

/** 仅采样线程持有的有界原件暂存；读失败时保留已完成阶段及管道已收字节。 */
final class AppMediaReadTrace implements AndroidAudioCaptureObservation.Trace {
    private JSONObject value=new JSONObject();
    private Bytes flinger,policy;
    private String stage="NOT_STARTED";
    void reset(){value=new JSONObject();stage="NOT_STARTED";flinger=null;policy=null;}
    String stage(){return stage;}
    public void stage(String next,long elapsed){stage=next;try{value.put("stage",next).put("stage_elapsed_ms",elapsed);}catch(Exception ignored){}}
    public void dump(String name,byte[] bytes,boolean complete,long began,long finished){
        try{
            if(bytes.length>AudioInputOwnership.MAX_CHARS)throw new IllegalArgumentException("采样字节超限");
            Bytes captured=new Bytes(bytes,complete,began,finished);
            if("media.audio_flinger".equals(name))flinger=captured;else policy=captured;
        }catch(Exception failure){stage="TRACE_FAILED";}
    }
    public void external(JSONObject external,long began,long finished){
        try{String raw=external==null?null:external.toString();
            if(raw!=null&&raw.length()>65536)throw new IllegalArgumentException("外部状态超限");
            value.put("external_raw",raw).put("external_started_elapsed_ms",began).put("external_finished_elapsed_ms",finished);
        }catch(Exception failure){stage="TRACE_FAILED";}
    }
    JSONObject snapshot()throws Exception{
        JSONObject result=new JSONObject(value.toString());
        if(flinger!=null)result.put("flinger",flinger.json());if(policy!=null)result.put("policy",policy.json());return result;
    }
    private static final class Bytes {
        final byte[] raw;final boolean complete;final long began,finished;
        Bytes(byte[] raw,boolean complete,long began,long finished){this.raw=raw;this.complete=complete;this.began=began;this.finished=finished;}
        JSONObject json()throws Exception{
            char[] hex=new char[raw.length*2],alphabet="0123456789abcdef".toCharArray();
            for(int i=0;i<raw.length;i++){hex[i*2]=alphabet[(raw[i]>>>4)&15];hex[i*2+1]=alphabet[raw[i]&15];}
            return new JSONObject().put("raw_hex",new String(hex)).put("bytes",raw.length).put("complete",complete)
                    .put("started_elapsed_ms",began).put("finished_elapsed_ms",finished);
        }
    }
}
