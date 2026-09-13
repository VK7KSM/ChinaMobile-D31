package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** 双向联合观察；身份、焦点与失效代次均先冻结，PCM查询只读不可变缓存。 */
public final class CachedCallDuplexGuard implements CallDuplexGuard, AudioGuard {
    static final class Generation {
        final Identity input, output;
        final boolean focus, closed;
        Generation(Identity input,Identity output,boolean focus,boolean closed) {
            this.input=input;this.output=output;this.focus=focus;this.closed=closed;
        }
    }
    private static final class Cached {
        final Generation generation;
        final Evidence evidence;
        final boolean idle,speaker,focus;
        final long began,finished;
        Cached(Generation generation,Evidence evidence,boolean idle,boolean speaker,boolean focus,long began,long finished) {
            this.generation=generation;this.evidence=evidence;this.idle=idle;this.speaker=speaker;this.focus=focus;this.began=began;this.finished=finished;
        }
    }
    private final int pid;
    private final MediaCapture.Clock clock;
    private final AtomicReference<Generation> generation=new AtomicReference<>(new Generation(null,null,false,false));
    private volatile Cached cached;
    private volatile String reason="MEDIA_CALL_SAMPLE_MISSING";
    public CachedCallDuplexGuard(int pid,MediaCapture.Clock clock) {
        if(pid<=0||clock==null)throw new IllegalArgumentException("MEDIA_CALL_DEPENDENCY_MISSING");
        this.pid=pid;this.clock=clock;
    }
    Generation generation(){return generation.get();}
    /** 仅由Operations取得实际ADM对象后登记，禁止从Web身份或PCM查询反向建档。 */
    public void bind(Identity input,Identity output)throws IOException {
        for(;;){
            Generation old=generation.get();
            if(old.closed)throw new IOException("MEDIA_CALL_GUARD_CLOSED");
            if(input!=null&&(!input.input||input.pid!=pid)||output!=null&&(output.input||output.pid!=pid)) {
                invalidate();throw new IOException("MEDIA_CALL_IDENTITY_MISMATCH");
            }
            if(old.input!=null&&old.input!=input||old.output!=null&&old.output!=output) {
                invalidate();throw new IOException("MEDIA_CALL_IDENTITY_CHANGED");
            }
            if(old.input==input&&old.output==output)return;
            if(generation.compareAndSet(old,new Generation(input,output,old.focus,false)))return;
        }
    }
    public void focusOwned(boolean owned) {
        for(;;){Generation old=generation.get();if(old.closed)return;
            if(generation.compareAndSet(old,new Generation(old.input,old.output,owned,false)))return;}
    }
    void invalidate(){invalidate(null);}
    void invalidate(Generation expected) {
        for(;;){Generation old=generation.get();if(expected!=null&&old!=expected)return;
            if(generation.compareAndSet(old,new Generation(old.input,old.output,old.focus,old.closed))) {
                reason="MEDIA_CALL_OBSERVATION_REVOKED";return;
            }
        }
    }
    public void close(){for(;;){Generation old=generation.get();
        if(generation.compareAndSet(old,new Generation(old.input,old.output,false,true)))return;}}

    /** 两份完整转储、外部占用及免提读回必须处在同一轮1500ms窗口内。 */
    void sample(AudioCaptureObservation.Sample value,boolean speaker,long began,long finished,Generation token) {
        try {
            if(token==null||token!=generation.get()||token.closed)return;
            require(value!=null&&value.flinger!=null&&value.policy!=null);
            long now=clock.elapsed();
            require(began>=0&&finished>=began&&finished<=now&&finished-began<=MAX_SAMPLE_MS&&now-began<=MAX_AGE_MS);
            require(value.began>=began&&value.finished<=finished&&value.finished>=value.began);
            for(AudioInputOwnership.Dump dump:new AudioInputOwnership.Dump[]{value.flinger,value.policy})
                require(dump.startedElapsedMs>=began&&dump.finishedElapsedMs<=finished);
            Identity in=token.input,out=token.output;
            AudioInputOwnership.Result inputs=AudioInputOwnership.evaluate(value.flinger,value.policy,pid,in==null?1:in.session,now);
            require(inputs.state==AudioInputOwnership.State.NO_ACTIVE_INPUT
                    ||in!=null&&inputs.state==AudioInputOwnership.State.SELF_ONLY&&inputs.activeInputs==1);
            boolean inputOwned=inputs.state==AudioInputOwnership.State.SELF_ONLY;
            D31OutputEvidence.Result outputs;
            if(!inputOwned) {
                outputs=out==null?D31OutputEvidence.evaluate(value.flinger,value.policy,pid,now)
                        :D31OutputEvidence.evaluate(value.flinger,value.policy,D31OutputIdentity.from(out.privateJson()),now);
            } else {
                outputs=CallOutputEvidence.evaluate(value,out);
            }
            boolean outputOwned=outputs.state==D31OutputEvidence.State.SELF_ONLY;
            boolean starting=outputs.state==D31OutputEvidence.State.STARTING;
            require(outputs.state==D31OutputEvidence.State.EMPTY||outputOwned||starting);
            JSONObject external=new JSONObject(value.externalJson),audio=external.getJSONObject("audio");
            require(audio.getJSONArray("sources").length()==9&&audio.getJSONArray("streams").length()>=10
                    &&audio.getJSONArray("streams").length()<=32);
            Object focus=audio.opt("focus_gain");require(focus instanceof Integer);
            boolean ownFocus=((Integer)focus)==2&&token.focus;
            if(ownFocus)audio.put("focus_gain",0);
            if(inputOwned)clearOwn(audio.getJSONArray("sources"),1,"source",false);
            if(outputOwned||starting)clearOwn(audio.getJSONArray("streams"),3,"stream",true);
            require(AndroidAudioOccupancy.evaluate(external,began,finished).overall()==AndroidAudioOccupancy.State.IDLE);
            boolean idle=!inputOwned&&outputs.state==D31OutputEvidence.State.EMPTY;
            require(idle||ownFocus&&speaker);
            Evidence proof=in!=null&&out!=null&&ownFocus&&speaker
                    ?new Evidence(in,out,inputOwned,outputOwned,began,finished):null;
            Cached next=new Cached(token,proof,idle,speaker,ownFocus,began,finished);
            if(token==generation.get()){cached=next;reason=idle?"MEDIA_CALL_IDLE_OBSERVED"
                    :inputOwned&&outputOwned?"MEDIA_CALL_DUPLEX_OBSERVED":"MEDIA_CALL_DUPLEX_PENDING";}
        }catch(Exception|LinkageError invalid){invalidate(token);}
    }
    private static void clearOwn(JSONArray rows,int index,String key,boolean remote)throws Exception {
        JSONObject row=rows.getJSONObject(index);
        require(row.opt(key) instanceof Integer&&row.getInt(key)==index&&Boolean.TRUE.equals(row.opt("active")));
        if(remote)require(Boolean.FALSE.equals(row.opt("remote_active")));
        row.put("active",false);
    }
    private static void require(boolean value)throws IOException {if(!value)throw new IOException("MEDIA_CALL_OBSERVATION_INVALID");}
    private Cached fresh() {
        Cached value=cached;Generation state=generation.get();long now=clock.elapsed();
        return value!=null&&value.generation==state&&!state.closed&&now>=value.finished&&now>=value.began
                &&now-value.began<=MAX_AGE_MS?value:null;
    }
    public Evidence current(Identity input,Identity output) {
        Cached value=fresh();Evidence proof=value==null?null:value.evidence;
        return proof!=null&&proof.valid(input,output,clock.elapsed())?proof:null;
    }
    public void requireIdle()throws IOException {
        Cached value=fresh();if(value==null||!value.idle)throw new IOException("MEDIA_CALL_IDLE_UNCONFIRMED");
    }
    public void requireIdleSpeakerRoute()throws IOException {
        Cached value=fresh();if(value==null||!value.idle||!value.speaker||!value.focus)
            throw new IOException("MEDIA_CALL_IDLE_ROUTE_UNCONFIRMED");
    }
    boolean observedSince(long since){Cached value=fresh();return value!=null&&value.began>=since;}
    public JSONObject snapshot()throws Exception {
        Cached value=fresh();Evidence proof=value==null?null:value.evidence;
        return new JSONObject().put("fresh",value!=null).put("idle",value!=null&&value.idle)
                .put("input_owned",proof!=null&&proof.inputOwned).put("output_owned",proof!=null&&proof.outputOwned)
                .put("reason",value==null?"MEDIA_CALL_SAMPLE_UNAVAILABLE":reason).put("max_age_ms",MAX_AGE_MS)
                .put("max_sample_ms",MAX_SAMPLE_MS).put("local_recording",false);
    }
}
