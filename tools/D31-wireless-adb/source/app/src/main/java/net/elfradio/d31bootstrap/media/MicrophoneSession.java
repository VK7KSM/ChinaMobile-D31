package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 一次性会话；真实传输/Peer由Android适配器提供，关闭确认前不释放媒体锁。 */
public final class MicrophoneSession implements AutoCloseable {
    public interface Events { void message(String raw); void disconnected(); }
    public interface Transport {
        void connect(RtcOffer offer, Events events) throws Exception;
        void send(JSONObject message) throws Exception;
        void close() throws Exception;
        default void abort(){}
    }
    public interface PeerEvents { void connected(); void failed(); void recording(boolean active); }
    public interface Peer {
        void open(PeerEvents events, Cancellation cancel) throws Exception;
        JSONObject publishOffer(Cancellation cancel) throws Exception;
        void answer(JSONObject description, Cancellation cancel) throws Exception;
        void close() throws Exception;
        default boolean captureReady(){return true;}
        default void requireHealthy()throws Exception{}
        default JSONObject diagnostics()throws Exception{return new JSONObject();}
    }
    public interface Changed { void changed(JSONObject state); }
    private final File root;
    private final RtcOffer offer;
    private final Transport transport;
    private final Peer peer;
    private final AudioGuard guard;
    private final MediaCapture.Clock clock;
    private final Changed listener;
    private final Cancellation cancellation=new Cancellation();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private final Map<Integer,RtcAwait<JSONObject>> pending=new HashMap<Integer,RtcAwait<JSONObject>>();
    private final CountDownLatch released=new CountDownLatch(1);
    private MediaFiles.Lease lease;
    private boolean started,hello,connected,published,ready,stopping;
    private int sequence;
    private long startedAt,endedAt,deadline;
    private long recordStartedAt,recordEndedAt;
    private String state="idle",reason="",cleanupReason="";

    public MicrophoneSession(File privateMediaRoot, RtcOffer offer, Transport transport, Peer peer,
            AudioGuard guard, MediaCapture.Clock clock, Changed listener) throws Exception {
        if(offer==null||transport==null||peer==null||clock==null)throw new IOException("MEDIA_DEPENDENCY_MISSING");
        root=MediaFiles.directory(privateMediaRoot.getAbsoluteFile());this.offer=offer;this.transport=transport;
        this.peer=peer;this.guard=guard;this.clock=clock;this.listener=listener;
    }
    public synchronized void start() throws Exception {
        if(started||stopping)throw new IOException("MEDIA_SESSION_NOT_REUSABLE");
        if(guard==null)throw new IOException("MEDIA_AUDIO_STATE_UNKNOWN");
        guard.requireIdle();
        if(offer.expiresAt<=clock.wall())throw new IOException("MEDIA_EXPIRED");
        lease=MediaFiles.lease(root);started=true;state="connecting";
        deadline=clock.elapsed()+Math.min(45000,offer.expiresAt-clock.wall());
        try {
            watchdog.scheduleWithFixedDelay(new Runnable(){public void run(){checkOwner();}},0,100,TimeUnit.MILLISECONDS);
            worker.execute(new Runnable(){public void run(){try {
                cancellation.check();
                transport.connect(offer,new Events(){public void message(String raw){receive(raw);}public void disconnected(){stop("MEDIA_DISCONNECTED");}});
            }catch(Exception failure){stop("MEDIA_CONNECT_FAILED");}}});
        }catch(Exception failure){stop("MEDIA_START_FAILED");throw failure;}
        changed();
    }
    private void checkOwner(){
        try{synchronized(this){if(stopping)return;if(clock.elapsed()>=deadline)throw new IOException("MEDIA_SESSION_TIMEOUT");}
            guard.requireIdle();peer.requireHealthy();synchronized(this){markReady();}}
        catch(Exception failure){stop(failureCode(failure,"MEDIA_TIMEOUT_OR_AUDIO_BUSY"));}
    }
    private static String failureCode(Exception failure,String fallback){
        String code=failure.getMessage();return code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:fallback;
    }
    private void receive(String raw){
        try {
            if(raw==null||raw.length()>96000)throw new IOException("MEDIA_MESSAGE_INVALID");
            final JSONObject message=new JSONObject(raw);
            synchronized(this){
                if(stopping)return;
                String type=message.optString("type");
                if("rpc".equals(type)){
                    RtcAwait<JSONObject> reply=pending.remove(message.optInt("id",-1));
                    if(reply==null)throw new IOException("MEDIA_RPC_UNEXPECTED");
                    if(message.has("error"))reply.fail("MEDIA_RPC_REJECTED");else reply.succeed(message.getJSONObject("result"));
                    return;
                }
                if("closed".equals(type)){stop("MEDIA_REMOTE_CLOSED");return;}
                if("waiting".equals(type)||"pong".equals(type)||"ready".equals(type))return;
                if(!"hello".equals(type)||hello||!offer.mode.equals(message.optString("mode")))throw new IOException("MEDIA_MESSAGE_UNEXPECTED");
                hello=true;
            }
            worker.execute(new Runnable(){public void run(){publish();}});
        }catch(Exception failure){stop("MEDIA_PROTOCOL_FAILED");}
    }
    private void publish(){
        try {
            cancellation.check();guard.requireIdle();
            peer.open(new PeerEvents(){public void connected(){iceConnected();}public void failed(){stop("MEDIA_PEER_FAILED");}
                public void recording(boolean active){recordingState(active);}},cancellation);
            rpc("new",new JSONObject());
            JSONObject body=peer.publishOffer(cancellation);
            JSONObject reply=rpc("publish",body);
            peer.answer(reply.getJSONObject("sessionDescription"),cancellation);
            rpc("published",new JSONObject());
            synchronized(this){if(stopping)return;published=true;markReady();}
        }catch(Exception failure){stop(failureCode(failure,"MEDIA_NEGOTIATION_FAILED"));}catch(LinkageError failure){stop("MEDIA_JNI_FAILED");}
    }
    private JSONObject rpc(String action,JSONObject body)throws Exception {
        final int id;final RtcAwait<JSONObject> result=new RtcAwait<JSONObject>();
        synchronized(this){cancellation.check();id=++sequence;pending.put(id,result);}
        try{
            transport.send(new JSONObject().put("type","rpc").put("id",id).put("action",action).put("body",body));
            return result.get(cancellation,clock,20000);
        }finally{synchronized(this){pending.remove(id);}}
    }
    private synchronized void iceConnected(){if(stopping)return;connected=true;try{markReady();}catch(Exception failure){stop("MEDIA_READY_FAILED");}}
    private synchronized void recordingState(boolean active){
        if(active){if(stopping)return;if(recordStartedAt==0)recordStartedAt=clock.wall();}
        else if(recordStartedAt>0&&recordEndedAt==0){recordEndedAt=clock.wall();if(!stopping)stop("MEDIA_CAPTURE_STOPPED");}
        changed();
    }
    private void markReady()throws Exception {
        if(ready||stopping||!connected||!published||!peer.captureReady())return;
        guard.requireIdle();
        transport.send(new JSONObject().put("type","ready"));
        ready=true;startedAt=clock.wall();deadline=clock.elapsed()+1800000;state="streaming";changed();
    }
    public synchronized JSONObject snapshot()throws Exception {
        return new JSONObject().put("schemaVersion",1).put("session_id",offer.id).put("mode",offer.mode)
                .put("state",state).put("reason",reason).put("cleanup_reason",cleanupReason).put("started_at_ms",startedAt).put("ended_at_ms",endedAt)
                .put("published",published).put("ice_connected",connected).put("local_recording",false)
                .put("capture_verified",peer.captureReady()).put("cleanup_complete","closed".equals(state))
                .put("diagnostics",peer.diagnostics())
                .put("audio_record_started_at_ms",recordStartedAt).put("audio_record_ended_at_ms",recordEndedAt)
                .put("timing_scope","SESSION_AND_AUDIORECORD_CALLBACKS")
                .put("remote_audio_content","NOT_VERIFIED");
    }
    private void changed(){if(listener!=null)try{listener.changed(snapshot());}catch(Exception ignored){}}
    public synchronized void stop(String code){
        if(stopping)return;stopping=true;cancellation.cancel();state="closing";
        reason=code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"MEDIA_STOPPED";
        for(RtcAwait<JSONObject> wait:pending.values())wait.fail("MEDIA_CANCELLED");pending.clear();watchdog.shutdownNow();
        try{transport.abort();}catch(Exception ignored){}
        // 不能在持会话监视器时等待WebSocket线程退出，关闭过程放入媒体线程。
        worker.execute(new Runnable(){public void run(){cleanup();}});changed();
    }
    private void cleanup(){
        boolean clean=true;String cleanupError="";
        try{transport.close();}catch(Exception failure){clean=false;cleanupError="MEDIA_TRANSPORT_RELEASE_UNCONFIRMED";}
        try{peer.close();}catch(Exception failure){clean=false;cleanupError=failureCode(failure,"MEDIA_NATIVE_RELEASE_UNCONFIRMED");}
        catch(LinkageError failure){clean=false;cleanupError="MEDIA_NATIVE_RELEASE_UNCONFIRMED";}
        synchronized(this){
            endedAt=clock.wall();
            // JNI释放不确定时保留跨进程媒体锁，禁止马上创建新会话抢占。
            if(clean&&lease!=null)try{lease.close();lease=null;}catch(Exception failure){clean=false;cleanupError="MEDIA_LEASE_RELEASE_UNCONFIRMED";}
            cleanupReason=cleanupError;
            state=clean?"closed":"release_unconfirmed";changed();released.countDown();worker.shutdown();
        }
    }
    public boolean awaitClosed(long timeoutMs)throws InterruptedException{return released.await(Math.max(0,timeoutMs),TimeUnit.MILLISECONDS);}
    public void close(){stop("MEDIA_HOST_CLOSED");}
}
