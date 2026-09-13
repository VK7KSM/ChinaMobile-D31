package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** D22持久媒体的D31适配；控制器持有唯一WS，本类只管理RTC及按需硬件。 */
public final class AndroidPersistentMediaPeer implements PreparedSessionPort.Peer {
    public interface Route {
        default void beforeActivate(long operation,String mode)throws Exception {}
        default void afterDeactivate(long operation,String mode)throws Exception {}
        default void prepared()throws Exception {}
        default void checkConnected(String mode)throws Exception {}
        default void close(long operation,long remainingMs)throws Exception {close(operation);}
        default void dispose(long remainingMs)throws Exception {}
        void open(long operation)throws Exception;
        void close(long operation)throws Exception;
    }
    public interface Events extends PreparedSessionPort.Events {}
    interface Backend {
        void open(AndroidPersistentMediaPeer owner,Cancellation cancel)throws Exception;
        JSONObject publish(JSONObject result)throws Exception;
        void applyPublish(JSONObject result)throws Exception;
        JSONObject subscribe(JSONObject result)throws Exception;
        void validate()throws Exception;
        boolean idle()throws Exception;
        void activate(long operation,String mode,String facing)throws Exception;
        boolean ready(long operation,String mode);
        default void invalidateOperation(long operation) {}
        void softStop();
        void stop(long deadlineNanos)throws Exception;
        void switchCamera(long operation,String facing)throws Exception;
        JSONObject snapshot()throws Exception;
        void close()throws Exception;
    }
    private final Backend backend;private final Route route;private final PreparedSessionPort.Events events;private final long closeBudgetMs;
    private final Cancellation cancellation=new Cancellation();
    private final AtomicLong invalidatedOperation=new AtomicLong();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"d31-persistent-peer"));
    private final Object releaseLock=new Object();private Future<?> closing;private long releaseStarted;
    private volatile boolean opened,published,applied,subscribed,negotiated,ice,disposed,releaseFailed;
    private volatile String phase="new",mode="prepare",error="";
    private volatile String stage="OPEN",failedStage="";
    private volatile long operation;private boolean routeOpened;
    public AndroidPersistentMediaPeer(Context context,File apk,String hash,File cache,MediaCapture.Clock clock,Route route,PreparedSessionPort.Events events)throws Exception{
        this(new AndroidPersistentRtc(context,apk,hash,cache,clock),route,events);
    }
    AndroidPersistentMediaPeer(Backend backend,Route route,PreparedSessionPort.Events events){
        this(backend,route,events,8000);
    }
    AndroidPersistentMediaPeer(Backend backend,Route route,PreparedSessionPort.Events events,long closeBudgetMs){
        if(backend==null||route==null||events==null||closeBudgetMs<=0||closeBudgetMs>8000)throw new IllegalArgumentException();this.backend=backend;this.route=route;this.events=events;this.closeBudgetMs=closeBudgetMs;
    }
    public void open()throws Exception{invoke(()->{stage="OPEN";require(!opened,"MEDIA_PERSISTENT_NOT_REUSABLE");opened=true;phase="preparing";
        routeOpened=true;stage="ROUTE_OPEN";route.open(0);cancellation.check();stage="OPEN";backend.open(this,cancellation);return null;},10000);}
    public JSONObject createPublish(JSONObject result)throws Exception{JSONObject value=copy(result);return invoke(()->{
        stage="PUBLISH";require(opened&&!published,"MEDIA_PERSISTENT_PUBLISH_STATE");JSONObject body=backend.publish(value);published=true;return body;},10000);}
    public void applyPublish(JSONObject result)throws Exception{JSONObject value=copy(result);invoke(()->{
        stage="APPLY_PUBLISH";require(published&&!applied,"MEDIA_PERSISTENT_PUBLISH_STATE");backend.applyPublish(value);applied=true;return null;},10000);}
    public JSONObject subscribe(JSONObject result)throws Exception{JSONObject value=copy(result);return invoke(()->{
        stage="SUBSCRIBE";require(applied&&!subscribed,"MEDIA_PERSISTENT_SUBSCRIBE_STATE");JSONObject answer=backend.subscribe(value);subscribed=true;return answer;},10000);}
    public void negotiationComplete()throws Exception{invoke(()->{
        stage="NEGOTIATION";require(subscribed&&!negotiated,"MEDIA_PERSISTENT_NEGOTIATION_STATE");backend.validate();
        require(backend.idle(),"MEDIA_PERSISTENT_IDLE_HARDWARE_ACTIVE");route.prepared();negotiated=true;phase="idle";changed();return null;},10000);}
    public boolean transportReady(){
        if(!negotiated||!ice||cancellation.isCancelled())return false;
        try{return !"idle".equals(phase)||backend.idle();}catch(Exception invalid){return false;}
    }
    public void activate(long next,String requested,String facing)throws Exception{
        require(Arrays.asList("ptt","call","microphone","video","photo","alarm").contains(requested)&&validFacing(facing),"MEDIA_PERSISTENT_OPERATION_INVALID");
        require(transportReady()&&"idle".equals(phase)&&next>0&&next==operation+1,"MEDIA_PERSISTENT_OPERATION_INVALID");
        invokeOperation(next,()->{
            stage="ACTIVATE";
            require(transportReady()&&"idle".equals(phase)&&next>0&&next==operation+1,"MEDIA_PERSISTENT_OPERATION_INVALID");
            require(backend.idle(),"MEDIA_PERSISTENT_IDLE_HARDWARE_ACTIVE");
            operation=next;mode=requested;phase="activating";
            stage="BEFORE_ACTIVATE";checkOperation(next);route.beforeActivate(next,requested);checkOperation(next);
            stage="BACKEND_ACTIVATE";checkOperation(next);backend.activate(next,requested,facing);checkOperation(next);phase="active";changed();return null;
        });
    }
    public void invalidateOperation(long op){
        if(op<=0||op<operation||(op>operation&&!("idle".equals(phase)&&op==operation+1)))return;
        long prior=invalidatedOperation.get();
        while(op>prior&&!invalidatedOperation.compareAndSet(prior,op))prior=invalidatedOperation.get();
        backend.invalidateOperation(op);
    }
    boolean operationAllowed(long op){return op>invalidatedOperation.get()&&!cancellation.isCancelled();}
    void checkOperation(long op)throws IOException{cancellation.check();if(!operationAllowed(op))throw new OperationCancelled();}
    private static final class OperationCancelled extends IOException {}
    private void invokeOperation(long op,Callable<Void> action)throws Exception{
        invoke(()->{
            try{return action.call();}
            catch(Exception failure){
                if(operationAllowed(op)||cancellation.isCancelled())throw failure;
                backend.softStop();phase="stopping";return null;
            }
        },10000);
    }
    private boolean healthy(){
        if(cancellation.isCancelled())return false;
        try{if(negotiated)route.checkConnected(mode);return true;}
        catch(Exception failure){String code=controlledFailure(failure.getMessage());fail(code==null?"MEDIA_PREPARED_GUARD_UNCONFIRMED":code);return false;}
    }
    public boolean operationReady(long op){return operationAllowed(op)&&op==operation&&"active".equals(phase)&&healthy()&&transportReady()&&backend.ready(op,mode);}
    public void deactivate(long op)throws Exception{
        if("idle".equals(phase)){
            if(op<=operation)return;
            require(op==operation+1,"MEDIA_PERSISTENT_OPERATION_INVALID");
            invoke(()->{require("idle".equals(phase)&&op==operation+1,"MEDIA_PERSISTENT_OPERATION_INVALID");operation=op;changed();return null;},2000);return;
        }
        if(op<operation)return;
        require(op==operation,"MEDIA_PERSISTENT_OPERATION_INVALID");
        require("active".equals(phase)||"activating".equals(phase)||"stopping".equals(phase),"MEDIA_PERSISTENT_OPERATION_INVALID");
        invalidateOperation(op);
        phase="stopping";backend.softStop();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        invoke(()->{stage="HARDWARE_STOP";backend.stop(deadline);require(backend.idle(),"MEDIA_PERSISTENT_HARDWARE_STOP_UNCONFIRMED");return null;},2000);
        invoke(()->{stage="AFTER_DEACTIVATE";route.afterDeactivate(op,mode);
            mode="prepare";phase="idle";changed();return null;},4000);
    }
    public void switchCamera(long op,String facing)throws Exception{
        require(validFacing(facing),"MEDIA_PERSISTENT_CAMERA_INVALID");
        if(op==operation&&!operationAllowed(op))return;
        require(op==operation&&"active".equals(phase)&&"video".equals(mode),"MEDIA_PERSISTENT_OPERATION_INVALID");
        invokeOperation(op,()->{checkOperation(op);require(op==operation&&"active".equals(phase)&&"video".equals(mode),"MEDIA_PERSISTENT_OPERATION_INVALID");
            stage="SWITCH_CAMERA";backend.switchCamera(op,facing);checkOperation(op);changed();return null;});
    }
    public JSONObject snapshot()throws Exception{healthy();JSONObject media=error.isEmpty()?backend.snapshot():new JSONObject();JSONObject result=new JSONObject().put("phase",phase).put("mode",mode).put("operation",operation)
            .put("negotiated",negotiated).put("ice_connected",ice).put("transport_ready",transportReady()).put("operation_ready",operationReady(operation))
            .put("error",error).put("release_unconfirmed",releaseFailed).put("peer_cleanup_complete",disposed&&worker.isTerminated()&&!releaseFailed)
            .put("media",media).put("diagnostics",new JSONObject().put("failed_stage",failedStage));
        if(media.optLong("operation",-1)==operation&&validFacing(media.optString("camera"))){
            result.put("camera",media.getString("camera"));Object count=media.opt("cameras");
            if(count instanceof Integer&&(Integer)count>0&&(Integer)count<=16)result.put("cameras",count);
        }
        return result;}
    public void cancel(){cancellation.cancel();backend.softStop();}
    void ice(boolean connected){if(cancellation.isCancelled())return;ice=connected;changed();}
    void changed(){try{events.changed();}catch(Exception ignored){}}
    void fail(String code){fail(code,stage);}
    private void fail(String code,String at){
        synchronized(this){if(!error.isEmpty())return;failedStage=at;error=code;}
        cancel();try{events.failed(code);}catch(Exception ignored){}
    }
    private <T>T invoke(Callable<T> action,long timeout)throws Exception{
        cancellation.check();Future<T> task;
        synchronized(releaseLock){cancellation.check();task=worker.submit(()->{cancellation.check();T value=action.call();cancellation.check();return value;});}
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        try{while(true){cancellation.check();long left=end-System.nanoTime();if(left<=0)throw new TimeoutException();
            try{return task.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);}catch(TimeoutException wait){if(System.nanoTime()>=end)throw wait;}}
        }catch(Exception failure){Throwable cause=failure;
            while(cause instanceof ExecutionException&&cause.getCause()!=null)cause=cause.getCause();
            String code="MEDIA_PERSISTENT_OPERATION_FAILED";Throwable typed=cause;
            for(int n=0;typed!=null&&n<8;n++){
                String selected=controlledFailure(typed.getMessage());if(selected!=null)code=selected;
                if(typed.getCause()==null||typed.getCause()==typed)break;typed=typed.getCause();
            }
            fail(code,stage);release();
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            if(cause instanceof Exception)throw (Exception)cause;throw failure;}
    }
    private static String controlledFailure(String value){
        if(value==null)return null;
        for(String reason:new String[]{"CELLULAR_CALL_ACTIVE","NEXUI_SESSION_ACTIVE","GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED"})
            if(value.equals("MEDIA_AUDIO_BUSY:"+reason))return "MEDIA_AUDIO_BUSY_"+reason;
        for(String reason:new String[]{"CLOSED","NOT_STARTED","NO_SAMPLE","STALE_SAMPLE","SAMPLE_DEADLINE","SOURCE_READ_FAILED",
                "SOURCE_API_UNAVAILABLE","CELLULAR_COVERAGE_UNKNOWN","NEXUI_STATE_UNKNOWN","GLOBAL_AUDIO_COVERAGE_UNKNOWN"})
            if(value.equals("MEDIA_AUDIO_UNKNOWN:"+reason))return "MEDIA_AUDIO_UNKNOWN_"+reason;
        return value.matches("(?:MEDIA|VISUAL)_[A-Z0-9_]{1,96}")?value:null;
    }
    private long remainingReleaseMs(){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(releaseStarted+TimeUnit.MILLISECONDS.toNanos(closeBudgetMs)-System.nanoTime()));}
    private void closeRoute()throws Exception{if(routeOpened){route.close(0,remainingReleaseMs());routeOpened=false;}}
    private Future<?> release(){synchronized(releaseLock){if(closing==null){releaseStarted=System.nanoTime();closing=worker.submit(()->{
        try{try{backend.close();}finally{closeRoute();}}finally{route.dispose(remainingReleaseMs());}
        disposed=true;phase="closed";return null;});worker.shutdown();}return closing;}}
    public void close()throws Exception{
        cancel();Future<?> done=release();if(disposed&&worker.isTerminated()&&!releaseFailed)return;
        long end=releaseStarted+TimeUnit.MILLISECONDS.toNanos(closeBudgetMs);
        try{long remaining=end-System.nanoTime();if(remaining<=0||releaseFailed)throw new IOException("MEDIA_PERSISTENT_RELEASE_UNCONFIRMED");
            done.get(remaining,TimeUnit.NANOSECONDS);long left=end-System.nanoTime();
            if(left<=0||!worker.awaitTermination(left,TimeUnit.NANOSECONDS)||releaseFailed)throw new IOException("MEDIA_PERSISTENT_RELEASE_UNCONFIRMED");
        }catch(Exception failure){releaseFailed=true;if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException("MEDIA_PERSISTENT_RELEASE_UNCONFIRMED",failure);}
    }
    static boolean capture(String mode){return "call".equals(mode)||"microphone".equals(mode)||"video".equals(mode);}
    static boolean playback(String mode){return "call".equals(mode)||"ptt".equals(mode);}
    static boolean validFacing(String value){return "front".equals(value)||"back".equals(value);}
    static void require(boolean ok,String code)throws IOException{if(!ok)throw new IOException(code);}
    static JSONObject copy(JSONObject value)throws Exception{require(value!=null,"MEDIA_PERSISTENT_RESPONSE_MISSING");return new JSONObject(value.toString());}
}
