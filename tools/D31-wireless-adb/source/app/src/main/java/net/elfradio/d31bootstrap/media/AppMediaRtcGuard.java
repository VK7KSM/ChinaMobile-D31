package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.io.File;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 本次RTC输入只在真实PID/session及双源观察匹配后解除静音。 */
final class AppMediaRtcGuard implements AudioGuard, AutoCloseable {
    private final AudioGuard before;
    private final AudioCaptureObservation.Reader reader;
    private final Cancellation cancellation=new Cancellation();
    private final ScheduledExecutorService samples=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"d31-rtc-input-observer");t.setDaemon(true);return t;
    });
    private volatile Observation latest;
    private volatile boolean closed,started;
    private volatile int session;
    private volatile long startElapsed;
    private volatile boolean everReady;
    private final AppMediaRtcDiagnostics diagnostics;
    private volatile String failureCode="";
    private volatile JSONObject sampleSummary=new JSONObject();
    private int sampleCount;
    private final AppMediaReadTrace trace=new AppMediaReadTrace();
    static final class Observation {
        final boolean ready,busy;final long began;
        final String inputState,inputReason,externalState;
        final boolean windowValid;
        Observation(boolean ready,boolean busy,long began,String inputState,String inputReason,String externalState,boolean windowValid){
            this.ready=ready;this.busy=busy;this.began=began;this.inputState=inputState;this.inputReason=inputReason;this.externalState=externalState;this.windowValid=windowValid;
        }
        JSONObject summary()throws Exception{return new JSONObject().put("input_state",inputState).put("input_reason",inputReason)
                .put("external_state",externalState).put("window_valid",windowValid).put("input_verified",ready);}
        boolean fresh(long now){return now>=began&&now-began<=AudioInputOwnership.MAX_AGE_MS;}
    }
    static Observation evaluate(AudioCaptureObservation.Sample sample,int pid,int session,long now){
        if(sample==null)return new Observation(false,false,-1,"UNKNOWN","SAMPLE_NOT_RETURNED","UNKNOWN",false);
        AudioInputOwnership.Result inputs=AudioInputOwnership.evaluate(sample.flinger,sample.policy,pid,session,now);
        AudioCaptureLifecycle.External external=AudioCaptureObservation.external(sample,pid,session,now);
        long began=Math.min(sample.began,Math.min(sample.flinger.startedElapsedMs,sample.policy.startedElapsedMs));
        long end=Math.max(sample.finished,Math.max(sample.flinger.finishedElapsedMs,sample.policy.finishedElapsedMs));
        boolean valid=began>=0&&end-began<=AudioInputOwnership.MAX_SAMPLE_MS;
        return new Observation(valid&&inputs.state==AudioInputOwnership.State.SELF_ONLY&&inputs.activeInputs==1
                &&external==AudioCaptureLifecycle.External.IDLE,
                inputs.state==AudioInputOwnership.State.OTHER_ACTIVE||external==AudioCaptureLifecycle.External.BUSY,began,
                inputs.state.name(),inputs.reason,external.name(),valid);
    }
    AppMediaRtcGuard(Context context,AudioGuard before,File diagnosticRoot,String id,String hash){
        this.before=before;reader=new AndroidAudioCaptureObservation(context,trace);
        diagnostics=new AppMediaRtcDiagnostics(diagnosticRoot,id,hash);
    }
    synchronized void recording(int actualSession)throws IOException {
        if(closed||started||actualSession<=0)throw new IOException("MEDIA_RTC_INPUT_IDENTITY");
        session=actualSession;startElapsed=SystemClock.elapsedRealtime();started=true;
        samples.scheduleWithFixedDelay(()->{
            AudioCaptureObservation.Sample value=null;
            trace.reset();
            String readError="";
            try{value=reader.read(cancellation);}catch(Exception|LinkageError failure){
                String code=failure.getMessage();readError=code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_INPUT_READ_FAILED";
            }
            {
                long now=SystemClock.elapsedRealtime();
                Observation observation=evaluate(value,android.os.Process.myPid(),session,now);
                if(!closed){latest=observation;if(observation.ready)everReady=true;}
                try{
                    JSONObject summary=observation.summary().put("read_error",readError).put("read_stage",trace.stage());
                    sampleSummary=summary;
                    // 只保存首两轮以及随后第一轮失败，不持续写盘。
                    if(sampleCount++<2||!observation.ready)diagnostics.save(value,readError,android.os.Process.myPid(),session,now,summary,trace.snapshot());
                }catch(Exception ignored){failureCode="MEDIA_DIAGNOSTIC_SUMMARY_FAILED";}
            }
        },100,2000,TimeUnit.MILLISECONDS);
    }
    boolean ready(){
        if(closed||!started)return false;
        Observation value=latest;long now=SystemClock.elapsedRealtime();
        return value!=null&&value.ready&&value.fresh(now);
    }
    public void requireIdle()throws Exception {
        try{check();}catch(Exception failure){
            String code=failure.getMessage();failureCode=code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_RTC_PRECONDITION_FAILED";
            throw new IOException(failureCode);
        }
    }
    private void check()throws Exception {
        if(closed)throw new IOException("MEDIA_RTC_GUARD_CLOSED");
        if(!started){before.requireIdle();return;}
        // 首次证据期间保持上行静音；通话状态始终独立检查，不等新一轮转储。
        if(!(before instanceof AndroidAudioOccupancy))throw new IOException("MEDIA_RTC_CALL_GUARD_MISSING");
        AppMediaBackend.requireDiagnosticCallsIdle(((AndroidAudioOccupancy)before).snapshot());
        if(ready())return;
        Observation value=latest;
        if(!everReady&&(value==null||!value.busy)&&SystemClock.elapsedRealtime()-startElapsed<3500)return;
        if(value==null||"SAMPLE_NOT_RETURNED".equals(value.inputReason))throw new IOException("MEDIA_RTC_SAMPLE_UNAVAILABLE");
        if(!value.fresh(SystemClock.elapsedRealtime()))throw new IOException("MEDIA_RTC_SAMPLE_STALE");
        if(!value.windowValid)throw new IOException("MEDIA_RTC_SAMPLE_WINDOW");
        if("UNKNOWN".equals(value.inputState))throw new IOException("MEDIA_RTC_INPUT_UNPARSEABLE");
        if(value.busy)throw new IOException("MEDIA_RTC_EXTERNAL_BUSY");
        if(!"SELF_ONLY".equals(value.inputState))throw new IOException("MEDIA_RTC_SELF_INPUT_MISSING");
        throw new IOException("MEDIA_RTC_EXTERNAL_UNKNOWN");
    }
    JSONObject snapshot()throws Exception{
        return new JSONObject().put("failure_code",failureCode).put("last_sample",new JSONObject(sampleSummary.toString()))
                .put("storage",diagnostics.snapshot());
    }
    public void close()throws Exception {
        closed=true;cancellation.cancel();samples.shutdownNow();
        if(!samples.awaitTermination(1750,TimeUnit.MILLISECONDS))throw new IOException("MEDIA_RTC_OBSERVER_RELEASE_UNCONFIRMED");
    }
}
