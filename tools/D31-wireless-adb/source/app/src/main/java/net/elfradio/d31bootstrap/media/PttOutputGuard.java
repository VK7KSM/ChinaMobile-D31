package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** 核心路径只读缓存；原样本由独立读取线程提供。没有按active布尔值豁免自身。 */
public final class PttOutputGuard implements AudioGuard {
    private final MediaCapture.Clock clock;
    private final int pid;
    private volatile Cached cached;
    private volatile boolean focusOwned;
    private final AtomicLong focusEpoch=new AtomicLong();
    private volatile D31OutputIdentity identity;
    private final AtomicLong muteEpoch=new AtomicLong();
    private volatile boolean muted;
    private long confirmedMute=-1;
    private static final class Cached {
        final long began,focusEpoch,muteEpoch;final D31OutputEvidence.Result outputs;final String preflightError;final boolean needsFocus;final D31OutputIdentity identity;
        String external;boolean streamOwned;long finished;
        Cached(long began,D31OutputEvidence.Result outputs,String error,boolean focus,long epoch,D31OutputIdentity identity,long muteEpoch){this.began=began;this.outputs=outputs;preflightError=error;needsFocus=focus;focusEpoch=epoch;this.identity=identity;this.muteEpoch=muteEpoch;}
    }
    public PttOutputGuard(int pid,MediaCapture.Clock clock){this.pid=pid;this.clock=clock;}
    void sample(AudioCaptureObservation.Sample value,String error){
        sample(value,error,focusEpoch());
    }
    long focusEpoch(){return focusEpoch.get();}
    synchronized void revokeSample(String error){
        sample(null,error,focusEpoch.incrementAndGet(),muteEpoch());
    }
    void sample(AudioCaptureObservation.Sample value,String error,long epoch){
        sample(value,error,epoch,muteEpoch());
    }
    long muteEpoch(){return muteEpoch.get();}
    void sample(AudioCaptureObservation.Sample value,String error,long epoch,long mutedGeneration){
        D31OutputIdentity actual=identity;
        if(value==null){cached=new Cached(-1,new D31OutputEvidence.Result(D31OutputEvidence.State.UNKNOWN,error),error,false,epoch,actual,mutedGeneration);return;}
        long now=clock.elapsed(),began=Math.min(value.began,Math.min(value.flinger.startedElapsedMs,value.policy.startedElapsedMs));
        D31OutputEvidence.Result outputs=actual==null?D31OutputEvidence.evaluate(value.flinger,value.policy,pid,now)
                :D31OutputEvidence.evaluate(value.flinger,value.policy,actual,now);
        boolean needsFocus=false;String failure="";
        try{
            JSONObject external=new JSONObject(value.externalJson),audio=external.getJSONObject("audio");
            needsFocus=audio.opt("focus_gain") instanceof Integer&&audio.getInt("focus_gain")==2;
            if(needsFocus)audio.put("focus_gain",0);
            if(outputs.state==D31OutputEvidence.State.SELF_ONLY||outputs.state==D31OutputEvidence.State.STARTING){
                JSONArray streams=audio.getJSONArray("streams");JSONObject ownStream=streams.getJSONObject(actual.stream);
                if(!(ownStream.opt("stream") instanceof Integer)||ownStream.getInt("stream")!=actual.stream
                        ||!Boolean.TRUE.equals(ownStream.opt("active"))||!Boolean.FALSE.equals(ownStream.opt("remote_active")))
                    throw new IOException("MEDIA_OUTPUT_STREAM_STATE_MISMATCH");
                ownStream.put("active",false);
            }
            AudioCaptureObservation.requireEmpty(new AudioCaptureObservation.Sample(value.flinger,value.policy,external,value.began,value.finished),pid,now);
        }catch(Exception invalid){failure="MEDIA_PTT_PREFLIGHT_UNKNOWN_OR_BUSY";}
        Cached next=new Cached(began,outputs,failure,needsFocus,epoch,actual,mutedGeneration);
        next.external=value.externalJson;
        next.finished=Math.max(value.finished,Math.max(value.flinger.finishedElapsedMs,value.policy.finishedElapsedMs));
        // 投影只清已证实的自身流；输入与其它流的活动不能因投影丢失。
        try{
            long finished=Math.max(value.finished,Math.max(value.flinger.finishedElapsedMs,value.policy.finishedElapsedMs));
            if(muted&&actual!=null&&value.began>=0&&value.finished>=value.began
                    &&began>=0&&finished>=began&&finished<=now&&finished-began<=AudioInputOwnership.MAX_SAMPLE_MS
                    &&AudioInputOwnership.evaluate(value.flinger,value.policy,pid,1,now).state==AudioInputOwnership.State.NO_ACTIVE_INPUT){
                JSONObject raw=new JSONObject(value.externalJson);
                next.streamOwned=D31ActiveOutputEvidence.ownsStream(value.flinger.text,value.policy.text,actual,raw);
            }
        }catch(Exception invalid){next.streamOwned=false;}
        cached=next;
    }
    /** 只能由实际AudioTrack私有回调身份调用；不能使用邀请中的身份字段。 */
    public synchronized void actualOutput(JSONObject raw)throws IOException{
        D31OutputIdentity actual=D31OutputIdentity.from(raw);
        if(actual.pid!=pid)throw new IOException("MEDIA_OUTPUT_IDENTITY_MISMATCH");
        if(!actual.supported())throw new IOException("MEDIA_OUTPUT_IDENTITY_FORMAT_UNVERIFIED");
        if(identity!=null&&!identity.same(actual))throw new IOException("MEDIA_OUTPUT_IDENTITY_CHANGED");
        if(identity==null){identity=actual;muteEpoch.incrementAndGet();confirmedMute=-1;}
    }
    /** 本地硬件静音门切换；每次调用均撤销上一操作的确认，不能由Web状态驱动。 */
    public synchronized long outputMuted(boolean value){
        muted=value;confirmedMute=-1;return muteEpoch.incrementAndGet();
    }
    /** 仅实际同一AudioTrack写后零PCM且本地mute仍成立的回调可确认其原代次。 */
    public synchronized void confirmMuted(long epoch){
        if(identity!=null&&muted&&epoch==muteEpoch())confirmedMute=epoch;
    }
    /** 183连接持有焦点；六模式激活前/停采后检查，活动输入不走此门。 */
    public synchronized void requireOperationIdle()throws Exception{
        requireOwnedOutput();
        Cached value=current();
        requireMuted(value);
    }
    private void requireMuted(Cached value)throws IOException{
        if(value.muteEpoch!=muteEpoch())throw new IOException("MEDIA_OUTPUT_MUTE_OBSERVATION_REVOKED");
        if(!muted||confirmedMute!=muteEpoch())throw new IOException("MEDIA_OUTPUT_MUTE_UNCONFIRMED");
    }
    /** 给既有警报监控的同轮原件副本；仅投影自身静音输出和本连接焦点，不宣称全局空闲。 */
    public synchronized JSONObject projectMutedOutput()throws Exception{
        Cached value=current();requireMuted(value);
        if(!value.streamOwned||value.external==null)throw new IOException("MEDIA_OUTPUT_STREAM_PROJECTION_UNVERIFIED");
        if(!value.needsFocus||!focusOwned)throw new IOException("MEDIA_PTT_FOCUS_NOT_OWNED");
        JSONObject result=new JSONObject(value.external),audio=result.getJSONObject("audio");
        JSONObject own=audio.getJSONArray("streams").getJSONObject(identity.stream);
        if(!(own.opt("stream") instanceof Integer)||own.getInt("stream")!=identity.stream
                ||!Boolean.TRUE.equals(own.opt("active"))||!Boolean.FALSE.equals(own.opt("remote_active")))
            throw new IOException("MEDIA_OUTPUT_STREAM_STATE_MISMATCH");
        own.put("active",false);audio.put("focus_gain",0);return result;
    }
    /** 活动六模式保留电话抢占检查；不把自身活动输入/输出当空闲，也不据此授权媒体。 */
    public synchronized void requireNoCalls()throws Exception{
        Cached value=current();
        AndroidAudioOccupancy.Observation observed=AndroidAudioOccupancy.evaluate(
                value.external==null?null:new JSONObject(value.external),value.began,value.finished);
        if(observed.cellular==AndroidAudioOccupancy.State.BUSY||observed.sip==AndroidAudioOccupancy.State.BUSY)
            throw new IOException("MEDIA_PERSISTENT_EXTERNAL_CALL_ACTIVE");
        if(observed.cellular!=AndroidAudioOccupancy.State.IDLE||observed.sip!=AndroidAudioOccupancy.State.IDLE)
            throw new IOException("MEDIA_PERSISTENT_CALL_STATE_UNKNOWN");
    }
    public synchronized void focusOwned(boolean owned){
        focusOwned=owned;
        if(!owned)focusEpoch.incrementAndGet();
    }
    public boolean observedSince(long began){Cached value=cached;return value!=null&&value.began>=began&&value.identity==identity&&value.focusEpoch==focusEpoch();}
    public void requireIdle()throws Exception{
        Cached value=current();
        if(value.outputs.state!=D31OutputEvidence.State.EMPTY)throw new IOException(value.outputs.reason);
        if(!value.preflightError.isEmpty())throw new IOException(value.preflightError);
        if(value.needsFocus&&!focusOwned)throw new IOException("MEDIA_PTT_FOCUS_NOT_OWNED");
    }
    /** 严格匹配实际身份及活动Policy后，仍要求本次焦点及外部电话/录音均空闲。 */
    public void requireOwnedOutput()throws Exception{
        Cached value=current();D31OutputEvidence.Result result=value.outputs;
        if(result.state==D31OutputEvidence.State.STARTING){
            if(!value.preflightError.isEmpty())throw new IOException(value.preflightError);
            if(!value.needsFocus||!focusOwned)throw new IOException("MEDIA_PTT_FOCUS_NOT_OWNED");
            throw new IOException("MEDIA_OUTPUT_STARTING");
        }
        if(result.state!=D31OutputEvidence.State.SELF_ONLY)
            throw new IOException(result.state==D31OutputEvidence.State.EMPTY?"MEDIA_OUTPUT_NOT_STARTED":result.reason);
        if(!value.preflightError.isEmpty())throw new IOException(value.preflightError);
        if(!value.needsFocus||!focusOwned)throw new IOException("MEDIA_PTT_FOCUS_NOT_OWNED");
    }
    /** 启动过渡只返回待观察；播放授权和恢复空闲门仍分别严格检查。 */
    public int outputProof()throws Exception{
        try{requireOwnedOutput();return 1;}
        catch(IOException failure){
            if("MEDIA_OUTPUT_NOT_STARTED".equals(failure.getMessage())||"MEDIA_OUTPUT_STARTING".equals(failure.getMessage()))return 0;
            throw failure;
        }
    }
    public JSONObject snapshot()throws Exception{
        D31OutputEvidence.Result result;
        try{result=current().outputs;}catch(IOException failure){result=new D31OutputEvidence.Result(D31OutputEvidence.State.UNKNOWN,failure.getMessage());}
        boolean verified=false;try{requireOwnedOutput();verified=true;}catch(Exception denied){}
        return new JSONObject().put("state",result.state.name())
                .put("reason",result.reason).put("self_output_verified",verified)
                .put("active_output_contract","D31_STREAM3_48000_MONO_S16_PRIMARY_PCM32_STEREO");
    }
    private Cached current()throws IOException{
        Cached value=cached;if(value==null)throw new IOException("MEDIA_OUTPUT_SAMPLE_MISSING");
        if(value.began<0)throw new IOException(value.preflightError);
        if(value.identity!=identity)throw new IOException("MEDIA_OUTPUT_IDENTITY_SAMPLE_PENDING");
        // 失焦前开始的在途采样也失效；代次不依赖毫秒时钟的分辨率。
        if(value.focusEpoch!=focusEpoch())throw new IOException("MEDIA_PTT_FOCUS_OBSERVATION_REVOKED");
        long now=clock.elapsed();if(now<value.began||now-value.began>AudioInputOwnership.MAX_AGE_MS)throw new IOException("MEDIA_OUTPUT_SAMPLE_STALE");
        return value;
    }
}
