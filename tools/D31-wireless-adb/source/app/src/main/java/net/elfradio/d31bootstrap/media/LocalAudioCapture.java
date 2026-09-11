package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 单次本地采音诊断；样本仅在有界内存中存在，不提供音频输出接口。 */
final class LocalAudioCapture {
    static final int RATE=16000,MAX_DURATION_MS=5000;
    static final long MAX_TOTAL_MS=8000,RELEASE_WAIT_MS=750,CALL_CHECK_MS=100;
    interface Recorder {
        int state(); int recordingState(); int sessionId();
        void start(Cancellation cancellation)throws Exception;
        int read(short[] samples,int count)throws Exception;
        void stop()throws Exception;
        void release()throws Exception;
        default void tick() { }
        default boolean releasePending() { return false; }
        default boolean completionAllowed() { return true; }
        default JSONObject lifecycleSnapshot() throws Exception { return null; }
    }
    interface Factory {
        Recorder open(Cancellation cancellation)throws Exception;
        default boolean releasePending(){return false;}
        default JSONObject preflight(){return new JSONObject();}
        default void requireCallsIdle()throws Exception { }
    }
    private final String id;
    private final int durationMs,pid,uid;
    private final Factory factory;
    private final Cancellation cancellation=new Cancellation(),requestCancellation;
    private final MediaCapture.Clock clock;
    private final CountDownLatch finished=new CountDownLatch(1);
    private final CountDownLatch stopFinished=new CountDownLatch(1);
    private final AtomicBoolean once=new AtomicBoolean(),stopOnce=new AtomicBoolean();
    private volatile Recorder recorder;
    private volatile Thread worker;
    private volatile String outcome="PENDING",error="",stopError="",releaseError="";
    private volatile long bytes,reads,began=-1,ended=-1,captureBegan=-1;
    private volatile int sessionId=-1,initialState=-1,afterState=-1,afterRecording=-1,readError;
    private volatile boolean created,initialized,startAttempted,recordingStarted,released,returned;
    private volatile boolean durationEnded,captureFinished;
    LocalAudioCapture(String id,int durationMs,int pid,int uid,Factory factory,Cancellation cancellation,MediaCapture.Clock clock)throws IOException {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,96}")||durationMs<1||durationMs>MAX_DURATION_MS)
            throw new IOException("MEDIA_LOCAL_AUDIO_ARGUMENT");
        this.id=id;this.durationMs=durationMs;this.pid=pid;this.uid=uid;
        this.factory=factory;this.requestCancellation=cancellation;this.clock=clock;
    }
    String id(){return id;}
    boolean active(){return finished.getCount()!=0||(created&&(!released||afterState!=0||afterRecording!=1))||(stopOnce.get()&&stopFinished.getCount()!=0)
            ||(recorder!=null&&recorder.releasePending())||factory.releasePending();}
    void tick(){Recorder value=recorder;if(value!=null)value.tick();}
    void cancel(){
        cancellation.cancel();Thread current=worker;if(current!=null)current.interrupt();
        final Recorder value=recorder;
        if(value!=null&&startAttempted){Thread stopper=new Thread(()->stop(value),"d31-local-audio-stop");stopper.setDaemon(true);
            if(stopOnce.compareAndSet(false,true))stopper.start();}
    }
    private void stop(Recorder value){try{value.stop();}catch(Exception|LinkageError failure){stopError=failure.getClass().getSimpleName();}finally{stopFinished.countDown();}}
    JSONObject run(long replyDeadline)throws Exception {
        if(!once.compareAndSet(false,true))throw new IOException("MEDIA_LOCAL_AUDIO_ALREADY_RUN");
        began=clock.elapsed();final long deadline=Math.min(began+MAX_TOTAL_MS,replyDeadline-RELEASE_WAIT_MS);
        worker=new Thread(()->capture(deadline),"d31-local-audio-read");worker.setDaemon(true);worker.start();
        boolean interrupted=false;
        try {
            while(!finished.await(10,TimeUnit.MILLISECONDS)){
                if(clock.elapsed()>=deadline){outcome="TIMED_OUT";cancel();break;}
                if(cancellation.isCancelled()||requestCancellation.isCancelled()){outcome="CANCELLED";cancel();break;}
                tick();
                if(captureBegan>=0&&!captureFinished&&clock.elapsed()>=captureBegan+durationMs){durationEnded=true;cancel();break;}
            }
        }catch(InterruptedException cancelled){interrupted=true;outcome="CANCELLED";cancel();}
        finally {
            if(finished.getCount()!=0){
                try{finished.await(RELEASE_WAIT_MS,TimeUnit.MILLISECONDS);}catch(InterruptedException cancelled){interrupted=true;}
            }
            returned=true;if(interrupted)Thread.currentThread().interrupt();
        }
        return snapshot();
    }
    private void check(long deadline)throws Exception {
        requestCancellation.check();cancellation.check();if(clock.elapsed()>=deadline)throw new IOException("MEDIA_LOCAL_AUDIO_TIMEOUT");
    }
    private void capture(long deadline){
        short[] samples=new short[320];
        try {
            outcome="PREPARING";check(deadline);
            Recorder value=factory.open(cancellation);recorder=value;created=value!=null;
            if(value==null)throw new IOException("MEDIA_LOCAL_AUDIO_CREATE_FAILED");
            check(deadline);initialState=value.state();initialized=initialState==1;sessionId=value.sessionId();
            if(!initialized)throw new IOException("MEDIA_LOCAL_AUDIO_UNINITIALIZED");
            check(deadline);captureBegan=clock.elapsed();startAttempted=true;value.start(cancellation);recordingStarted=value.recordingState()==3;
            if(!recordingStarted)throw new IOException("MEDIA_LOCAL_AUDIO_START_FAILED");
            outcome="READING";long until=Math.min(deadline,captureBegan+durationMs),maxFrames=(long)RATE*durationMs/1000,nextCallsCheck=clock.elapsed();
            while(clock.elapsed()<until&&bytes/2<maxFrames){
                check(deadline);
                if(clock.elapsed()>=nextCallsCheck){factory.requireCallsIdle();nextCallsCheck=clock.elapsed()+CALL_CHECK_MS;}
                int count=(int)Math.min(samples.length,maxFrames-bytes/2);
                int n=value.read(samples,count);reads++;
                if(n<0){readError=n;throw new IOException("MEDIA_LOCAL_AUDIO_READ_FAILED");}
                if(n>count)throw new IOException("MEDIA_LOCAL_AUDIO_READ_SIZE");
                bytes+=n*2L;Arrays.fill(samples,(short)0);
                if(n==0)Thread.sleep(5);
            }
            check(deadline);if(bytes==0)throw new IOException("MEDIA_LOCAL_AUDIO_NO_DATA");outcome="COMPLETED";
        }catch(Exception|LinkageError failure){
            String code=failure.getMessage();error=failure instanceof SecurityException?"MEDIA_LOCAL_AUDIO_PERMISSION_DENIED"
                    :code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:failure.getClass().getSimpleName();
            if(durationEnded&&("MEDIA_CANCELLED".equals(code)||failure instanceof InterruptedException)){
                outcome=bytes>0?"COMPLETED":"FAILED";error=bytes>0?"":"MEDIA_LOCAL_AUDIO_NO_DATA";
            }else if(!"TIMED_OUT".equals(outcome))outcome=cancellation.isCancelled()||requestCancellation.isCancelled()||failure instanceof InterruptedException?"CANCELLED"
                    :"MEDIA_LOCAL_AUDIO_TIMEOUT".equals(code)?"TIMED_OUT":"FAILED";
        }finally {
            captureFinished=true;
            Arrays.fill(samples,(short)0);Recorder value=recorder;
            if(value!=null){
                if(startAttempted&&stopOnce.compareAndSet(false,true))stop(value);
                // stop可能由取消线程执行；不能与尚未完成的stop并发release或报告关闭。
                boolean interrupted=Thread.interrupted();
                while(stopOnce.get()&&stopFinished.getCount()!=0){try{stopFinished.await();}catch(InterruptedException ignored){interrupted=true;}}
                try{value.release();released=true;}catch(Exception|LinkageError failure){releaseError=failure.getClass().getSimpleName();}
                try{afterState=value.state();afterRecording=value.recordingState();}catch(Exception|LinkageError ignored){}
                if("COMPLETED".equals(outcome)&&!value.completionAllowed()){
                    outcome="FAILED";error="MEDIA_INPUT_LIFECYCLE_REVOKED";
                }
                if(interrupted)Thread.currentThread().interrupt();
            }
            ended=clock.elapsed();finished.countDown();
        }
    }
    JSONObject snapshot()throws Exception {
        String state=outcome;
        if(returned&&active())state="RELEASE_UNCONFIRMED";
        JSONObject result=new JSONObject().put("schemaVersion",1).put("operation","local_audio_capture").put("diagnostic_id",id).put("state",state)
                .put("app_pid",pid).put("app_uid",uid).put("audio_session_id",sessionId)
                .put("duration_ms",durationMs).put("sample_rate_hz",RATE).put("channels",1).put("encoding","PCM_16BIT")
                .put("duration_deadline_reached",durationEnded)
                .put("bytes_read",bytes).put("frames_read",bytes/2).put("frame_definition","MONO_PCM_SAMPLE").put("read_calls",reads)
                .put("read_error",readError).put("error_code",error).put("initialized",initialized).put("initial_record_state",initialState)
                .put("audio_record_created",created).put("recording_started",recordingStarted)
                .put("record_state_after_release",afterState).put("recording_state_after_release",afterRecording)
                .put("release_completed",released).put("release_verified",released&&afterState==0&&afterRecording==1&&finished.getCount()==0)
                .put("stop_completed",!startAttempted||stopFinished.getCount()==0).put("stop_error",stopError).put("release_error",releaseError)
                .put("worker_finished",finished.getCount()==0).put("started_elapsed_ms",began).put("capture_started_elapsed_ms",captureBegan)
                .put("finished_elapsed_ms",ended).put("preflight",factory.preflight())
                .put("managed_media",false).put("audio_persisted",false).put("network_started",false);
        result.put("factory_release_pending",factory.releasePending());
        Recorder value=recorder;if(value!=null){JSONObject lifecycle=value.lifecycleSnapshot();if(lifecycle!=null)result.put("input_lifecycle",lifecycle);}
        return result;
    }
}
