package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 单Peer双向适配；不拥有路由、传输或租约，不在构造时启动线程/JNI/音频。 */
public final class AndroidRtcCall implements AutoCloseable {
    public static final long OPERATION_TIMEOUT_MS=10000, RELEASE_TIMEOUT_MS=3000;
    public interface Events {void changed(boolean ice);void failed(String code);}
    interface SoftwareMute {void set(boolean muted);}
    interface Backend {
        void open(AndroidRtcCall callbacks,Cancellation cancel)throws Exception;
        JSONObject createPublish(JSONObject result)throws Exception;
        void applyPublish(JSONObject result)throws Exception;
        CallProtocol.SubscriptionResult subscribe(JSONObject result)throws Exception;
        void negotiationComplete()throws Exception;
        void prepareMuted()throws Exception;
        void close()throws Exception;
    }
    private final Backend backend;
    private final CallDuplexGuard guard;
    private final MediaCapture.Clock clock;
    private final Events events;
    private final Cancellation cancellation=new Cancellation();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"d31-call-peer"));
    private final Object releaseLock=new Object(),audioLock=new Object();
    private final AtomicBoolean failed=new AtomicBoolean();
    private final AppMediaPcmStats inputPcm=new AppMediaPcmStats(16000),outputPcm=new AppMediaPcmStats(48000);
    private volatile CallDuplexGuard.Identity input,output;
    private volatile boolean opened,publishCreated,publishApplied,subscribed,negotiated,prepared,ice;
    private volatile boolean captureStarted,playbackStarted,captureFrames,playbackFrames,unmuted,disposed;
    private volatile String error="";
    private CallDuplexGuard.Evidence pairedProof;
    private SoftwareMute softwareMute;
    private Boolean muted;
    private Future<?> release;
    private volatile boolean releaseTimedOut;
    private final long operationTimeout,releaseTimeout;

    public AndroidRtcCall(Context context,File apk,String sha256,File nativeCache,MediaCapture.Clock clock,
            CallDuplexGuard guard,Events events)throws Exception{
        this(new AndroidCallPeer(context,apk,sha256,nativeCache,clock),clock,guard,events,OPERATION_TIMEOUT_MS,RELEASE_TIMEOUT_MS);
    }
    AndroidRtcCall(Backend backend,MediaCapture.Clock clock,CallDuplexGuard guard,Events events,long operationTimeout,long releaseTimeout){
        if(backend==null||clock==null||guard==null||events==null||operationTimeout<=0||releaseTimeout<=0)
            throw new IllegalArgumentException("MEDIA_CALL_DEPENDENCY_MISSING");
        this.backend=backend;this.clock=clock;this.guard=guard;this.events=events;this.operationTimeout=operationTimeout;this.releaseTimeout=releaseTimeout;
    }
    public void open(CallProtocol.Route route)throws Exception{
        invoke(()->{require(route==CallProtocol.Route.SPEAKER,"MEDIA_CALL_ROUTE_UNSUPPORTED");require(!opened,"MEDIA_PEER_NOT_REUSABLE");
            guard.requireIdleSpeakerRoute();check();opened=true;backend.open(this,cancellation);return null;});
    }
    public JSONObject createPublish(JSONObject result)throws Exception{
        JSONObject frozen=copy(result);
        return invoke(()->{require(opened&&!publishCreated,"MEDIA_CALL_PUBLISH_STATE");JSONObject body=backend.createPublish(frozen);check();publishCreated=true;return copy(body);});
    }
    public void applyPublish(JSONObject result)throws Exception{
        JSONObject frozen=copy(result);
        invoke(()->{require(publishCreated&&!publishApplied,"MEDIA_CALL_PUBLISH_STATE");backend.applyPublish(frozen);check();publishApplied=true;return null;});
    }
    public CallProtocol.SubscriptionResult subscribe(JSONObject result)throws Exception{
        JSONObject frozen=copy(result);
        return invoke(()->{require(publishApplied&&!subscribed,"MEDIA_CALL_SUBSCRIBE_STATE");
            CallProtocol.SubscriptionResult answer=backend.subscribe(frozen);require(answer!=null,"MEDIA_CALL_SUBSCRIBE_INVALID");check();subscribed=true;return answer;});
    }
    public void negotiationComplete()throws Exception{
        invoke(()->{require(subscribed&&!negotiated,"MEDIA_CALL_NEGOTIATION_STATE");backend.negotiationComplete();check();negotiated=true;return null;});
    }
    public void prepareMuted()throws Exception{
        invoke(()->{require(negotiated&&ice&&!prepared,"MEDIA_CALL_NOT_CONNECTED");guard.requireIdleSpeakerRoute();check();
            synchronized(audioLock){applyMute(true);prepared=true;}backend.prepareMuted();return null;});
    }
    /** 调用者串行消费；观察换代只接受同一新证据里的双真，不拼接旧输入。 */
    public int inputProof()throws Exception{
        check();pairedProof=evidence();return pairedProof!=null&&pairedProof.inputOwned?1:0;
    }
    public int outputProof()throws Exception{
        check();CallDuplexGuard.Evidence current=evidence();
        if(current==null||pairedProof==null)return 0;
        if(current!=pairedProof){
            if(!pairedProof.inputOwned||!current.inputOwned||!current.outputOwned)return 0;
            pairedProof=current;
        }
        return current.outputOwned?1:0;
    }
    public void unmute()throws Exception{
        invoke(()->{CallDuplexGuard.Evidence current=evidence();
            require(prepared&&ice&&captureStarted&&playbackStarted&&captureFrames&&playbackFrames&&current!=null
                    &&current.inputOwned&&current.outputOwned,"MEDIA_CALL_DUPLEX_UNVERIFIED");
            synchronized(audioLock){
                check();CallDuplexGuard.Evidence latest=evidence();
                require(softwareMute!=null&&latest!=null&&latest.inputOwned&&latest.outputOwned,"MEDIA_CALL_DUPLEX_UNVERIFIED");
                applyMute(false);check();unmuted=true;
            }
            return null;});
    }
    public boolean captureFrames(){return !cancellation.isCancelled()&&captureFrames;}
    public boolean playbackFrames(){return !cancellation.isCancelled()&&playbackFrames;}
    CallDuplexGuard.Identity inputIdentity(){return input;}
    CallDuplexGuard.Identity outputIdentity(){return output;}
    public JSONObject privateInputIdentity()throws Exception{CallDuplexGuard.Identity value=input;return value==null?new JSONObject():value.privateJson();}
    public JSONObject privateOutputIdentity()throws Exception{CallDuplexGuard.Identity value=output;return value==null?new JSONObject():value.privateJson();}
    public JSONObject snapshot()throws Exception{return new JSONObject().put("mode","call").put("route","SPEAKER")
            .put("ice_connected",ice).put("negotiated",negotiated).put("prepared",prepared).put("unmuted",unmuted)
            .put("input_identity_observed",input!=null).put("output_identity_observed",output!=null)
            .put("capture_started",captureStarted).put("playback_started",playbackStarted)
            .put("capture_frames_seen",captureFrames).put("playback_frames_seen",playbackFrames)
            .put("capture_pcm",inputPcm.snapshot()).put("playback_pcm",outputPcm.snapshot())
            .put("error",error).put("peer_cleanup_complete",disposed&&worker.isTerminated()&&!releaseTimedOut)
            .put("release_unconfirmed",releaseTimedOut).put("audio_content","NOT_VERIFIED");}

    void attachMute(SoftwareMute setter){synchronized(audioLock){softwareMute=setter;muted=null;applyMute(true);}}
    private void applyMute(boolean value){
        if(!value&&cancellation.isCancelled())return;
        if(softwareMute!=null&&(muted==null||muted!=value)){softwareMute.set(value);muted=value;}
    }
    void identity(CallDuplexGuard.Identity value){
        if(cancellation.isCancelled())return;
        boolean changedIdentity=false;
        synchronized(audioLock){
            if(cancellation.isCancelled())return;
            CallDuplexGuard.Identity previous=value.input?input:output;
            if(previous!=null&&!previous.same(value))changedIdentity=true;
            else if(value.input){if(input==null)input=value;captureStarted=true;}
            else{if(output==null)output=value;playbackStarted=true;}
        }
        if(changedIdentity){fail("MEDIA_CALL_IDENTITY_CHANGED");return;}
        changed();
    }
    void stopped(boolean inputSide){
        if(inputSide)captureStarted=false;else playbackStarted=false;
        if(!cancellation.isCancelled())fail(inputSide?"MEDIA_CALL_CAPTURE_STOPPED":"MEDIA_CALL_PLAYOUT_STOPPED");
    }
    void ice(boolean connected){if(cancellation.isCancelled())return;boolean lost=ice&&!connected;ice=connected;if(lost)fail("MEDIA_CALL_ICE_LOST");else changed();}
    void pcm(boolean inputSide,byte[] data,int format,int channels,int rate){
        if(cancellation.isCancelled())return;
        boolean valid=data!=null&&data.length>0&&data.length<=8192&&data.length%2==0&&format==2&&channels==1&&rate==(inputSide?16000:48000);
        CallDuplexGuard.Evidence proof=unmuted?evidence():null;
        boolean allowed=unmuted&&proof!=null&&proof.inputOwned&&proof.outputOwned;
        (inputSide?inputPcm:outputPcm).accept(data,format,channels,rate,allowed);
        if(!valid){fail("MEDIA_CALL_PCM_FORMAT_INVALID");return;}
        if(unmuted&&!allowed){fail("MEDIA_CALL_DUPLEX_REVOKED");return;}
        boolean notify=false;
        if(prepared){if(inputSide&&!captureFrames){captureFrames=true;notify=true;}else if(!inputSide&&!playbackFrames){playbackFrames=true;notify=true;}}
        if(notify)changed();
    }
    private CallDuplexGuard.Evidence evidence(){
        if(cancellation.isCancelled()||!prepared||!captureStarted||!playbackStarted)return null;
        CallDuplexGuard.Identity in=input,out=output;
        try{CallDuplexGuard.Evidence value=guard.current(in,out);return value!=null&&value.valid(in,out,clock.elapsed())?value:null;}
        catch(Exception failure){return null;}
    }
    private void changed(){try{events.changed(ice);}catch(Exception ignored){}}
    void fail(String code){cancel();if(failed.compareAndSet(false,true)){error=code;try{events.failed(code);}catch(Exception ignored){}}}
    public void cancel(){
        cancellation.cancel();synchronized(audioLock){unmuted=false;
            try{applyMute(true);}catch(Exception|LinkageError failure){error="MEDIA_CALL_SOFTWARE_MUTE_FAILED";}
        }
    }
    private void check()throws IOException{cancellation.check();}
    private <T>T invoke(Callable<T> operation)throws Exception{
        check();Future<T> task;
        synchronized(releaseLock){check();task=worker.submit(()->{check();T value=operation.call();check();return value;});}
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(operationTimeout);
        try{
            for(;;){check();long left=deadline-System.nanoTime();if(left<=0)throw new IOException("MEDIA_CALL_OPERATION_TIMEOUT");
                try{return task.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException wait){}
            }
        }catch(Exception failure){
            cancel();requestRelease();
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(failure instanceof ExecutionException){Throwable cause=failure.getCause();
                if(cause instanceof Exception)throw (Exception)cause;throw new IOException("MEDIA_CALL_NATIVE_FAILED",cause);}
            throw failure;
        }
    }
    private Future<?> requestRelease(){synchronized(releaseLock){
        if(release==null){release=worker.submit(()->{backend.close();disposed=true;return null;});worker.shutdown();}
        return release;
    }}
    public void close()throws Exception{
        cancel();Future<?> closing=requestRelease();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(releaseTimeout);
        try{
            closing.get(releaseTimeout,TimeUnit.MILLISECONDS);
            long left=deadline-System.nanoTime();
            if(left<=0||!worker.awaitTermination(left,TimeUnit.NANOSECONDS))throw new TimeoutException();
            if(releaseTimedOut)throw new IOException("MEDIA_CALL_RELEASE_UNCONFIRMED");
        }catch(Exception failure){releaseTimedOut=true;if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IOException("MEDIA_CALL_RELEASE_UNCONFIRMED",failure);}
    }
    static JSONObject copy(JSONObject value)throws Exception{if(value==null)throw new IOException("MEDIA_CALL_RESPONSE_MISSING");return new JSONObject(value.toString());}
    static void require(boolean condition,String code)throws IOException{if(!condition)throw new IOException(code);}
}
