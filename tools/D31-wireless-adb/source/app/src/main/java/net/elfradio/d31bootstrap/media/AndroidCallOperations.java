package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 通话媒体执行适配；真实释放优先，恢复失败保留既有路由日志及外层媒体租约。 */
public final class AndroidCallOperations implements CallSessionController.Operations {
    public static final long CLEANUP_MS=7500;
    interface Peer {
        void open(CallProtocol.Route route)throws Exception;
        JSONObject createPublish(JSONObject value)throws Exception;
        void applyPublish(JSONObject value)throws Exception;
        CallProtocol.SubscriptionResult subscribe(JSONObject value)throws Exception;
        void negotiationComplete()throws Exception;
        void prepareMuted()throws Exception;
        CallDuplexGuard.Identity input(); CallDuplexGuard.Identity output();
        int inputProof()throws Exception; int outputProof()throws Exception;
        boolean captureFrames(); boolean playbackFrames();
        void unmute()throws Exception; void cancel(); void close()throws Exception;
        default JSONObject snapshot()throws Exception{return new JSONObject();}
    }
    interface Observation {
        void start()throws Exception; void awaitFirst()throws Exception; void refresh()throws Exception;
        void awaitIdle(long budget)throws Exception;void restored();void close(long budget)throws Exception;
        default JSONObject snapshot()throws Exception{return new JSONObject();}
    }
    interface Routing {void recover()throws Exception;void open()throws Exception;void releaseFocus()throws Exception;}
    private final Context context;
    private final File apk,nativeCache;
    private final String hash;
    private final MediaCapture.Clock clock;
    private final AppCallSession.Sender sender;
    private final AppCallSession.Signals signals;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Object releaseLock=new Object();
    private final ExecutorService transport=Executors.newSingleThreadExecutor(r->daemon(r,"d31-call-transport-release"));
    private final ExecutorService cleanup=Executors.newSingleThreadExecutor(r->daemon(r,"d31-call-release"));
    private volatile long deadline=Long.MAX_VALUE;
    private volatile boolean ice;
    private volatile Peer peer;
    private AppCallEvidence evidence;
    private CachedCallDuplexGuard guard;
    private Observation observer;
    private Routing route;
    private boolean routeTouched,opened;
    private Future<?> transportClosing,closing;
    private volatile String releaseError="";
    private long cleanupBudget=CLEANUP_MS;
    private volatile boolean released;
    public static AppCallSession.Factory factory(Context context,File apk,String hash,File nativeCache,MediaCapture.Clock clock) {
        return (sender,signals)->new AndroidCallOperations(context,apk,hash,nativeCache,clock,sender,signals);
    }
    public AndroidCallOperations(Context context,File apk,String hash,File nativeCache,MediaCapture.Clock clock,
            AppCallSession.Sender sender,AppCallSession.Signals signals) {
        this.context=context;this.apk=apk;this.hash=hash;this.nativeCache=nativeCache;this.clock=clock;this.sender=sender;this.signals=signals;
    }
    AndroidCallOperations(Peer peer,CachedCallDuplexGuard guard,Observation observer,Routing route,
            MediaCapture.Clock clock,AppCallSession.Sender sender) {
        this(null,null,null,null,clock,sender,null);this.peer=peer;this.guard=guard;this.observer=observer;this.route=route;
    }
    void cleanupBudget(long budget) {
        if(opened||cancelled.get()||budget<=0||budget>CLEANUP_MS)throw new IllegalArgumentException("MEDIA_CALL_CLEANUP_BUDGET_INVALID");
        cleanupBudget=budget;
    }
    private static Thread daemon(Runnable action,String name){Thread thread=new Thread(action,name);thread.setDaemon(true);return thread;}
    private void initialize()throws Exception {
        if(peer!=null)return;
        guard=new CachedCallDuplexGuard(android.os.Process.myPid(),clock);
        String dataDir=context.getApplicationInfo().dataDir;
        File files=AppMediaBackend.privateDirectory(dataDir==null?null:new File(dataDir),context.getFilesDir());
        evidence=new AppCallEvidence(files,hash,clock,this::snapshot,signals::diagnostics);
        CallDuplexObserver observing=new CallDuplexObserver(context,guard,clock,()->{if(!cancelled.get())signals.changed(ice);},
                new AppMediaReadTrace(),evidence);
        observer=new Observation(){
            public void start()throws Exception{observing.start();}public void awaitFirst()throws Exception{observing.awaitFirst();}
            public void refresh()throws Exception{observing.refresh();}public void awaitIdle(long budget)throws Exception{observing.awaitIdle(budget);}
            public void restored(){observing.routeRestored();}public void close(long budget)throws Exception{observing.close(budget);}
            public JSONObject snapshot()throws Exception{return observing.snapshot();}
        };
        DownlinkRouteLease lease=AndroidDownlinkRoute.create(context,()->{withinBudget();guard.requireIdle();},guard::focusOwned,
                ()->{cancel();signals.failed("MEDIA_CALL_FOCUS_LOST");});
        route=new Routing(){public void recover()throws Exception{lease.recover();}public void open()throws Exception{lease.openPtt();}
            public void releaseFocus()throws Exception{lease.releaseFocus();}};
        AndroidRtcCall rtc=new AndroidRtcCall(context,apk,hash,nativeCache,clock,guard,new AndroidRtcCall.Events(){
            public void changed(boolean connected){ice=connected;if(!cancelled.get())signals.changed(connected);}
            public void failed(String code){cancel();signals.failed("MEDIA_CALL_PEER_FAILED");}
        });
        peer=new Peer(){
            public void open(CallProtocol.Route value)throws Exception{rtc.open(value);}
            public JSONObject createPublish(JSONObject value)throws Exception{return rtc.createPublish(value);}
            public void applyPublish(JSONObject value)throws Exception{rtc.applyPublish(value);}
            public CallProtocol.SubscriptionResult subscribe(JSONObject value)throws Exception{return rtc.subscribe(value);}
            public void negotiationComplete()throws Exception{rtc.negotiationComplete();}
            public void prepareMuted()throws Exception{rtc.prepareMuted();}
            public CallDuplexGuard.Identity input(){return rtc.inputIdentity();}public CallDuplexGuard.Identity output(){return rtc.outputIdentity();}
            public int inputProof()throws Exception{return rtc.inputProof();}public int outputProof()throws Exception{return rtc.outputProof();}
            public boolean captureFrames(){return rtc.captureFrames();}public boolean playbackFrames(){return rtc.playbackFrames();}
            public void unmute()throws Exception{rtc.unmute();}public void cancel(){rtc.cancel();}public void close()throws Exception{rtc.close();}
            public JSONObject snapshot()throws Exception{return rtc.snapshot();}
        };
    }
    public void open(CallProtocol.Route requested)throws Exception {
        check();if(opened||requested!=CallProtocol.Route.SPEAKER)throw new IOException("MEDIA_CALL_ROUTE_OR_REUSE_INVALID");
        opened=true;initialize();check();observer.start();observer.awaitFirst();check();guard.requireIdle();
        routeTouched=true;route.recover();check();route.open();check();observer.refresh();check();guard.requireIdleSpeakerRoute();
        peer.open(requested);check();
    }
    public void send(JSONObject message)throws Exception{check();sender.send(message);}
    public JSONObject createPublish(JSONObject value)throws Exception{check();return peer.createPublish(value);}
    public void applyPublish(JSONObject value)throws Exception{check();peer.applyPublish(value);}
    public CallProtocol.SubscriptionResult subscribe(JSONObject value)throws Exception{check();return peer.subscribe(value);}
    public void negotiationComplete()throws Exception{check();peer.negotiationComplete();}
    public void prepareMuted()throws Exception{check();observer.refresh();check();guard.requireIdleSpeakerRoute();peer.prepareMuted();check();}
    public int inputProof()throws Exception{check();guard.bind(peer.input(),peer.output());return peer.inputProof();}
    public int outputProof()throws Exception{check();return peer.outputProof();}
    public boolean captureFrames(){return !cancelled.get()&&peer!=null&&peer.captureFrames();}
    public boolean playbackFrames(){return !cancelled.get()&&peer!=null&&peer.playbackFrames();}
    public void unmute()throws Exception{check();peer.unmute();check();}
    public void cancel() {
        synchronized(releaseLock){
            if(cancelled.compareAndSet(false,true))deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(cleanupBudget);
            if(transportClosing==null){transportClosing=transport.submit(()->{
                try{sender.abort();}catch(Exception|LinkageError ignored){}
                try{sender.close();}catch(Exception|LinkageError failure){throw new IOException("MEDIA_TRANSPORT_RELEASE_UNCONFIRMED");}
                return null;
            });transport.shutdown();}
        }
        Peer current=peer;if(current!=null)try{current.cancel();}catch(Exception|LinkageError failure){releaseError="MEDIA_CALL_RELEASE_UNCONFIRMED";}
        // 不关闭guard：peer退出后仍须通过新的无输入/无输出观察恢复路由。
    }
    private void check()throws IOException{if(cancelled.get())throw new IOException("MEDIA_CANCELLED");}
    private void withinBudget()throws IOException{if(deadline!=Long.MAX_VALUE&&remaining()<=0)throw new IOException("MEDIA_CALL_CLEANUP_TIMEOUT");}
    private long remaining(){return CallDuplexObserver.remaining(deadline);}
    private void release()throws Exception {
        String error="";boolean peerClosed=false;
        try{withinBudget();if(peer!=null)peer.close();peerClosed=true;}
        catch(Exception|LinkageError failure){error="MEDIA_CALL_RELEASE_UNCONFIRMED";}
        if(peerClosed&&routeTouched)try{
            withinBudget();route.releaseFocus();withinBudget();observer.awaitIdle(Math.min(4000,remaining()));
            withinBudget();route.recover();observer.restored();
        }catch(Exception|LinkageError failure){error="MEDIA_ROUTE_RESTORE_UNCONFIRMED";}
        if(observer!=null)try{observer.close(Math.min(CallDuplexObserver.FIRST_WAIT_MS,remaining()));}
        catch(Exception|LinkageError failure){if(error.isEmpty())error="MEDIA_CALL_OBSERVER_RELEASE_UNCONFIRMED";}
        if(!error.isEmpty())throw new IOException(error);
    }
    public void close()throws Exception {
        cancel();Future<?> work;
        synchronized(releaseLock){if(closing==null){closing=cleanup.submit(()->{release();return null;});cleanup.shutdown();}work=closing;}
        try {
            work.get(remaining(),TimeUnit.MILLISECONDS);transportClosing.get(remaining(),TimeUnit.MILLISECONDS);
            if(!cleanup.awaitTermination(remaining(),TimeUnit.MILLISECONDS)||!transport.awaitTermination(remaining(),TimeUnit.MILLISECONDS))
                throw new IOException("MEDIA_CALL_RELEASE_UNCONFIRMED");
            if(!releaseError.isEmpty())throw new IOException(releaseError);
            released=true;
        }catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            releaseError="MEDIA_CALL_RELEASE_UNCONFIRMED";throw new IOException(releaseError);
        }finally{
            if(evidence!=null)try{evidence.finish(snapshot(),remaining());}
            catch(Exception|LinkageError failure){released=false;
                if(releaseError.isEmpty())releaseError="MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED";
                throw new IOException(releaseError);}
        }
    }
    public JSONObject snapshot()throws Exception {
        Peer current=peer;Observation watching=observer;
        return new JSONObject().put("peer",current==null?new JSONObject():current.snapshot())
                .put("duplex",watching==null?new JSONObject():watching.snapshot()).put("cleanup_complete",released)
                .put("cleanup_reason",releaseError).put("local_recording",false);
    }
}
