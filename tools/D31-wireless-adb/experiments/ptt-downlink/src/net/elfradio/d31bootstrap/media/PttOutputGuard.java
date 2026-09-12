package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

/** 核心路径只读缓存；原样本由独立读取线程提供。没有按active布尔值豁免自身。 */
public final class PttOutputGuard implements AudioGuard {
    private final MediaCapture.Clock clock;
    private final int pid;
    private volatile Cached cached;
    private volatile boolean focusOwned;
    private static final class Cached {
        final long began;final D31OutputEvidence.Result outputs;final String preflightError;final boolean needsFocus;
        Cached(long began,D31OutputEvidence.Result outputs,String error,boolean focus){this.began=began;this.outputs=outputs;preflightError=error;needsFocus=focus;}
    }
    public PttOutputGuard(int pid,MediaCapture.Clock clock){this.pid=pid;this.clock=clock;}
    void sample(AudioCaptureObservation.Sample value,String error){
        if(value==null){cached=new Cached(-1,new D31OutputEvidence.Result(D31OutputEvidence.State.UNKNOWN,error),error,false);return;}
        long now=clock.elapsed(),began=Math.min(value.began,Math.min(value.flinger.startedElapsedMs,value.policy.startedElapsedMs));
        D31OutputEvidence.Result outputs=D31OutputEvidence.evaluate(value.flinger,value.policy,pid,now);
        boolean needsFocus=false;String failure="";
        try{
            JSONObject external=new JSONObject(value.externalJson),audio=external.getJSONObject("audio");
            needsFocus=audio.opt("focus_gain") instanceof Integer&&audio.getInt("focus_gain")==2;
            if(needsFocus)audio.put("focus_gain",0);
            AudioCaptureObservation.requireEmpty(new AudioCaptureObservation.Sample(value.flinger,value.policy,external,value.began,value.finished),pid,now);
        }catch(Exception invalid){failure="MEDIA_PTT_PREFLIGHT_UNKNOWN_OR_BUSY";}
        cached=new Cached(began,outputs,failure,needsFocus);
    }
    public void focusOwned(boolean owned){focusOwned=owned;}
    public boolean observedSince(long began){Cached value=cached;return value!=null&&value.began>=began;}
    public void requireIdle()throws Exception{
        Cached value=current();
        if(value.outputs.state!=D31OutputEvidence.State.EMPTY)throw new IOException(value.outputs.reason);
        if(!value.preflightError.isEmpty())throw new IOException(value.preflightError);
        if(value.needsFocus&&!focusOwned)throw new IOException("MEDIA_PTT_FOCUS_NOT_OWNED");
    }
    /** 活动输出缺少真实归属原件，当前必须拒绝；供静音首轮取证明确定位缺口。 */
    public void requireOwnedOutput()throws Exception{
        D31OutputEvidence.Result result=current().outputs;
        throw new IOException(result.state==D31OutputEvidence.State.EMPTY?"MEDIA_OUTPUT_NOT_STARTED":result.reason);
    }
    public JSONObject snapshot()throws Exception{
        D31OutputEvidence.Result result;
        try{result=current().outputs;}catch(IOException failure){result=new D31OutputEvidence.Result(D31OutputEvidence.State.UNKNOWN,failure.getMessage());}
        return new JSONObject().put("state",result.state.name())
                .put("reason",result.reason).put("self_output_verified",false)
                .put("active_output_contract","D31_FULL_ACTIVE_ROW_AND_POLICY_PAIR_MISSING");
    }
    private Cached current()throws IOException{
        Cached value=cached;if(value==null)throw new IOException("MEDIA_OUTPUT_SAMPLE_MISSING");
        if(value.began<0)throw new IOException(value.preflightError);
        long now=clock.elapsed();if(now<value.began||now-value.began>AudioInputOwnership.MAX_AGE_MS)throw new IOException("MEDIA_OUTPUT_SAMPLE_STALE");
        return value;
    }
}
