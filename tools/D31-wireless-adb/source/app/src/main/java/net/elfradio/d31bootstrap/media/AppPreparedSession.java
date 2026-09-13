package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** 同一WebSocket和媒体租约内串行切换六模式；原操作回调永不改绑新代次。 */
public final class AppPreparedSession implements AppMediaController.Session, AutoCloseable {
    private static final long ACTIVATION_MS=15000, RELEASE_MS=10000, RPC_MS=20000;
    private static final long MAX_OPERATION=9007199254740991L;
    private final File files;
    private final RtcOffer offer;
    private final MediaCapture.Clock clock;
    private final MicrophoneSession.Transport wire;
    private final PreparedSessionPort.Factory factory;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16),threads("d31-prepared-session"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(threads("d31-prepared-deadline"));
    private final ExecutorService closer=Executors.newSingleThreadExecutor(threads("d31-prepared-release"));
    private final AtomicBoolean refreshPending=new AtomicBoolean();
    private final CountDownLatch finished=new CountDownLatch(1);
    private final Cancellation cancellation=new Cancellation();
    private volatile PreparedSessionPort.Peer peer;
    private volatile PreparedSessionPort.Extra extra;
    private volatile MediaFiles.Lease lease;
    private volatile boolean mediaReleased;
    private volatile String mediaReleaseError="";
    private boolean started,stopping,terminal,hello,published,subscribed,tracks,subscribing,transportReady,ready;
    private String state="connecting",phase="preparing",reason="",cleanupReason="",remoteSession="";
    private String failedStage="",rpcDiagnostic="{}";
    private String peerMedia="{}";
    private long deadline,operation,closeAtNanos;
    private PreparedSessionPort.Operation current;
    private int rpcSequence,rpcId;
    private String rpcAction="";
    private RtcAwait<JSONObject> pending;
    private ScheduledFuture<?> alarm;

    public AppPreparedSession(Context app,File files,File nativeCache,File apk,String hash,RtcOffer offer,
                              MediaCapture.Clock clock,AudioGuard current) throws Exception {
        this(files,offer,clock,new MediaWebSocket(),new PreparedSessionAndroid(app,files,nativeCache,apk,hash,offer,clock,current));
    }
    public AppPreparedSession(File files,RtcOffer offer,MediaCapture.Clock clock,
                              MicrophoneSession.Transport wire,PreparedSessionPort.Factory factory) throws IOException {
        if(files==null||offer==null||clock==null||wire==null||factory==null||!"prepare".equals(offer.mode))
            throw new IOException("MEDIA_PREPARED_DEPENDENCY_INVALID");
        this.files=files;this.offer=offer;this.clock=clock;this.wire=wire;this.factory=factory;
    }
    private static ThreadFactory threads(String name){return job->{Thread t=new Thread(job,name);t.setDaemon(true);return t;};}
    public synchronized void start() throws Exception {
        if(started||stopping)throw new IOException("MEDIA_SESSION_NOT_REUSABLE");
        long remaining=offer.expiresAt-clock.wall();
        if(remaining<=0||remaining>45000)throw new IOException("MEDIA_OFFER_INVALID");
        started=true;deadline=clock.elapsed()+remaining;arm();
        submit(()->{
            cancellation.check();lease=MediaFiles.lease(new File(files.getAbsoluteFile(),"media"));cancellation.check();
            wire.connect(offer,new MicrophoneSession.Events(){
                public void message(String raw){receive(raw);}
                public void disconnected(){fail("MEDIA_PREPARED_DISCONNECTED");}
            });
        },"MEDIA_PREPARED_CONNECT_FAILED");
    }
    private interface Work {void run() throws Exception;}
    private void submit(Work action,String error){
        synchronized(this){
            if(stopping)return;
            try{worker.execute(()->{
                try{cancellation.check();action.run();}
                catch(Exception|LinkageError failure){if(!cancellation.isCancelled())fail(controlled(failure,error),error);}
            });}catch(RejectedExecutionException full){fail("MEDIA_PREPARED_QUEUE_FULL");}
        }
    }
    void receive(String raw){
        try{
            if(raw==null||raw.length()>96000)throw new IOException();
            JSONObject message=new JSONObject(raw);String type=message.getString("type");
            synchronized(this){if(stopping)return;}
            if("stop".equals(type)||"closed".equals(type)){stop();return;}
            if("rpc".equals(type)){completeRpc(message);return;}
            if("waiting".equals(type)||"pong".equals(type)||"ready".equals(type))return;
            if("hello".equals(type)){
                synchronized(this){
                    if(hello||!"prepare".equals(message.optString("mode")))throw new IOException();
                    hello=true;
                }
                submit(this::prepare,"MEDIA_PREPARED_NEGOTIATION_FAILED");
            }else if("tracks".equals(type)){
                String id=message.getString("sessionId");JSONArray values=message.getJSONArray("tracks");
                if(!id.matches("[A-Za-z0-9_-]{1,128}")||values.length()!=1)throw new IOException();
                JSONObject track=values.getJSONObject(0);
                if(!"audio".equals(track.optString("trackName"))||!"remote".equals(track.optString("location"))
                        ||!id.equals(track.optString("sessionId")))throw new IOException();
                synchronized(this){if(!hello||(tracks&&!id.equals(remoteSession)))throw new IOException();tracks=true;remoteSession=id;}
                refresh();
            }else if("activate".equals(type))activate(message);
            else if("deactivate".equals(type))deactivate(integer(message,"operation"));
            else if("switch".equals(type))switchCamera(message);
            else throw new IOException();
        }catch(Exception|LinkageError invalid){fail("MEDIA_PREPARED_MESSAGE_INVALID");}
    }
    private void prepare() throws Exception {
        PreparedSessionPort.Peer created=factory.createPeer(new PreparedSessionPort.Events(){
            public void changed(){refresh();}
            public void failed(String code){fail(controlled(code,"MEDIA_PREPARED_PEER_FAILED"),"MEDIA_PREPARED_PEER_FAILED");}
        });
        if(created==null)throw new IOException();peer=created;cancellation.check();created.open();cancellation.check();
        JSONObject publish=created.createPublish(rpc("new",new JSONObject()));cancellation.check();
        created.applyPublish(rpc("publish",publish));cancellation.check();rpc("published",new JSONObject());
        synchronized(this){if(stopping)return;published=true;}
        update();
    }
    private JSONObject rpc(String action,JSONObject body) throws Exception {
        RtcAwait<JSONObject> result=new RtcAwait<>();final int id;
        synchronized(this){
            cancellation.check();if(pending!=null||rpcSequence==Integer.MAX_VALUE)throw new IOException();
            id=++rpcSequence;pending=result;rpcId=id;rpcAction=action;
        }
        try{
            send(new JSONObject().put("type","rpc").put("id",id).put("action",action).put("body",body));
            JSONObject reply=result.get(cancellation,clock,RPC_MS);cancellation.check();return reply;
        }finally{synchronized(this){if(pending==result){pending=null;rpcId=0;rpcAction="";}}}
    }
    private void completeRpc(JSONObject message) throws Exception {
        final RtcAwait<JSONObject> result;
        synchronized(this){
            long id=integer(message,"id");
            if(pending==null||id!=rpcId){
                if(id<=rpcSequence)return;
                throw new IOException();
            }
            result=pending;
        }
        if(message.has("error")){
            synchronized(this){rpcDiagnostic=rpcDiagnostic(message.optJSONObject("diagnostic"),rpcAction).toString();}
            result.fail("MEDIA_PREPARED_RPC_REJECTED");return;
        }
        result.succeed(message.getJSONObject("result"));
    }
    private void refresh(){
        if(!refreshPending.compareAndSet(false,true))return;
        submit(()->{refreshPending.set(false);update();},"MEDIA_PREPARED_STATE_FAILED");
    }
    private void update() throws Exception {
        PreparedSessionPort.Peer p=peer;if(p==null)return;
        boolean subscribe;
        synchronized(this){subscribe=!stopping&&published&&tracks&&!subscribing;if(subscribe)subscribing=true;}
        if(subscribe){
            JSONObject answer=p.subscribe(rpc("subscribe",new JSONObject()));cancellation.check();
            if(answer!=null)rpc("answer",answer);
            p.negotiationComplete();cancellation.check();synchronized(this){subscribed=true;}
        }
        cachePeer(p);
        synchronized(this){
            if(stopping)return;
            if(transportReady&&!p.transportReady())throw new IOException("MEDIA_PREPARED_TRANSPORT_LOST");
            if(!transportReady&&published&&subscribed&&p.transportReady()){
                transportReady=true;state="idle";phase="idle";deadline=0;arm();
                send(new JSONObject().put("type","transport_ready"));
            }
            PreparedSessionPort.Operation op=current;
            if(op!=null&&owns(op)&&!ready&&p.operationReady(op.number)
                    &&(!isExtra(op.mode)||(extra!=null&&extra.ready()))){
                ready=true;phase="active";
                // 服务端发送deactivate；本地只在其单次期限之后保留两秒故障兜底，不自行伪造idle。
                deadline="alarm".equals(op.mode)?0:clock.elapsed()+(("ptt".equals(op.mode)||"photo".equals(op.mode))?62000:1802000);
                arm();sendOperation(op,new JSONObject().put("type","ready"));
                sendVideoStatus(op);
            }
        }
    }
    private void activate(JSONObject message) throws Exception {
        long number=integer(message,"operation");String mode=message.getString("mode"),camera=message.getString("camera");
        if(!isMode(mode)||!camera(camera)||!message.optString("report_id").equals(offer.id+"-"+number))throw new IOException();
        final PreparedSessionPort.Operation op;
        synchronized(this){
            if(!transportReady||!"idle".equals(phase)||number!=operation+1)throw new IOException();
            operation=number;current=op=new PreparedSessionPort.Operation(number,mode,camera,offer.id+"-"+number);
            peerMedia="{}";
            ready=false;state="active";phase="activating";deadline=clock.elapsed()+ACTIVATION_MS;arm();
        }
        submit(()->{
            try{
                if(!owns(op))return;
                peer.activate(op.number,op.mode,op.camera);
                if(!owns(op))return;
                if(isExtra(op.mode)){
                    PreparedSessionPort.Extra created=factory.createExtra(op,events(op));
                    if(created==null)throw new IOException();extra=created;
                    if(!owns(op)){created.cancel();return;}
                    created.start();
                }
                if(owns(op))update();
            }catch(Exception failure){if(owns(op))throw failure;}
        },"MEDIA_PREPARED_ACTIVATION_FAILED");
    }
    private PreparedSessionPort.OperationEvents events(final PreparedSessionPort.Operation op){
        return new PreparedSessionPort.OperationEvents(){
            public void changed(){if(owns(op))refresh();}
            public void failed(String code){if(owns(op))fail(controlled(code,"MEDIA_PREPARED_EXTRA_FAILED"),"MEDIA_PREPARED_EXTRA_FAILED");}
            public void message(JSONObject value){
                if(!owns(op))return;
                try{
                    JSONObject frozen=extraMessage(op,value);
                    submit(()->{if(owns(op))sendOperation(op,frozen);},"MEDIA_PREPARED_SEND_FAILED");
                }catch(Exception invalid){if(owns(op))fail("MEDIA_PREPARED_EXTRA_MESSAGE_INVALID");}
            }
        };
    }
    private void deactivate(long number){
        final PreparedSessionPort.Operation op;
        synchronized(this){
            if(stopping||number!=operation)return;
            if("idle".equals(phase)){
                submit(()->{synchronized(this){if(operation==number&&"idle".equals(phase))
                    send(new JSONObject().put("type","idle").put("operation",number));}},"MEDIA_PREPARED_SEND_FAILED");return;
            }
            if("stopping".equals(phase)||current==null)return;
            op=current;op.cancellation.cancel();ready=false;phase="stopping";deadline=clock.elapsed()+RELEASE_MS;arm();
        }
        peer.invalidateOperation(op.number);
        PreparedSessionPort.Extra x=extra;if(x!=null)try{x.cancel();}catch(Exception failure){fail("MEDIA_PREPARED_EXTRA_CANCEL_FAILED");}
        submit(()->{
            Throwable failure=null;PreparedSessionPort.Extra owned=extra;
            if(owned!=null)try{owned.close();}catch(Exception|LinkageError failed){failure=failed;}
            try{peer.deactivate(op.number);}catch(Exception|LinkageError failed){if(failure==null)failure=failed;}
            if(failure!=null)throw new IOException(controlled(failure,"MEDIA_PREPARED_IDLE_UNCONFIRMED"));
            if(!peer.transportReady())throw new IOException("MEDIA_PREPARED_TRANSPORT_LOST");
            cachePeer(peer);
            synchronized(this){
                if(stopping||current!=op)return;
                if(clock.elapsed()>=deadline)throw new IOException();
                extra=null;current=null;phase="idle";state="idle";deadline=0;arm();
                send(new JSONObject().put("type","idle").put("operation",op.number));
            }
        },"MEDIA_PREPARED_IDLE_UNCONFIRMED");
    }
    private void switchCamera(JSONObject message) throws Exception {
        long number=integer(message,"operation");String facing=message.getString("camera");
        if(!camera(facing))throw new IOException();
        final PreparedSessionPort.Operation op;
        synchronized(this){op=current;if(op==null||number!=op.number||!owns(op)||!"active".equals(phase))return;}
        if(!"photo".equals(op.mode)&&!"video".equals(op.mode))return;
        submit(()->{if(!owns(op))return;if("photo".equals(op.mode))extra.switchCamera(facing);else{
            peer.switchCamera(op.number,facing);if(!owns(op))return;cachePeer(peer);sendVideoStatus(op);
        }},
                "MEDIA_PREPARED_CAMERA_SWITCH_FAILED");
    }
    private synchronized void sendVideoStatus(PreparedSessionPort.Operation op) throws Exception {
        if(!"video".equals(op.mode)||!owns(op)||!ready)return;
        JSONObject media=new JSONObject(peerMedia);
        if(media.optLong("operation",-1)!=op.number||!media.has("camera")||!media.has("cameras"))return;
        sendOperation(op,new JSONObject().put("type","status").put("camera",media.getString("camera"))
                .put("cameras",media.getInt("cameras")));
    }
    private synchronized boolean owns(PreparedSessionPort.Operation op){
        return !stopping&&current==op&&!op.cancellation.isCancelled()&&("activating".equals(phase)||"active".equals(phase));
    }
    private synchronized void sendOperation(PreparedSessionPort.Operation op,JSONObject value) throws Exception {
        if(owns(op))send(value.put("operation",op.number));
    }
    private void send(JSONObject value) throws Exception {cancellation.check();wire.send(value);}
    public void tick(){
        String failure=null;
        synchronized(this){
            if(!started||stopping)return;
            if(deadline>0&&clock.elapsed()>=deadline)failure=!transportReady?"MEDIA_PREPARED_CONNECT_TIMEOUT":
                    "stopping".equals(phase)?"MEDIA_PREPARED_IDLE_TIMEOUT":!ready?"MEDIA_PREPARED_ACTIVATION_TIMEOUT":"MEDIA_PREPARED_OPERATION_TIMEOUT";
        }
        if(failure!=null)fail(failure);else refresh();
    }
    private synchronized void arm(){
        if(alarm!=null)alarm.cancel(false);
        if(deadline>0&&!stopping)alarm=timer.schedule(this::tick,Math.max(1,deadline-clock.elapsed()),TimeUnit.MILLISECONDS);
    }
    private void fail(String code){fail(code,code);}
    private void fail(String code,String stage){synchronized(this){if(!stopping)failedStage=stage;}stop(code,true);}
    public void stop(){stop("MEDIA_STOPPED",false);}
    public void close(){stop();}
    private void stop(String code,boolean failure){
        final PreparedSessionPort.Operation op;
        synchronized(this){
            if(stopping)return;
            stopping=true;state="closing";phase="closing";reason=code;ready=false;transportReady=false;
            op=current;if(op!=null)op.cancellation.cancel();
            cancellation.cancel();closeAtNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(RELEASE_MS);
            if(pending!=null)pending.fail("MEDIA_PREPARED_CANCELLED");
            worker.getQueue().clear();timer.shutdownNow();
        }
        // cancel/abort的接口只请求终止；完整释放在独立线程确认，不堵塞Binder或WebSocket回调。
        PreparedSessionPort.Extra x=extra;if(x!=null)try{x.cancel();}catch(Exception ignored){mediaReleaseError="MEDIA_PREPARED_CANCEL_UNCONFIRMED";}
        PreparedSessionPort.Peer p=peer;if(p!=null)try{p.cancel();}catch(Exception ignored){mediaReleaseError="MEDIA_PREPARED_CANCEL_UNCONFIRMED";}
        try{
            closer.execute(()->{
                boolean transportClosed=false;
                try{
                    if(failure)try{
                        JSONObject status=new JSONObject().put("type","status").put("message","通信失败："+code);
                        if(op!=null)status.put("operation",op.number);wire.send(status);
                    }catch(Exception ignored){}
                    String transportError="";
                    try{wire.abort();wire.close();transportClosed=true;}catch(Exception|LinkageError error){transportError=controlled(error,"MEDIA_TRANSPORT_RELEASE_UNCONFIRMED");}
                    long remaining=Math.max(0,closeAtNanos-System.nanoTime());
                    boolean exited=worker.awaitTermination(remaining,TimeUnit.NANOSECONDS);
                    boolean clean=transportClosed&&exited&&mediaReleased&&mediaReleaseError.isEmpty()&&System.nanoTime()<closeAtNanos;
                    if(clean&&lease!=null){try{lease.close();lease=null;}catch(Exception failed){clean=false;}}
                    finish(clean?"":!mediaReleaseError.isEmpty()?mediaReleaseError:!transportError.isEmpty()?transportError:"MEDIA_PREPARED_RELEASE_UNCONFIRMED");
                }catch(Exception|LinkageError failed){finish("MEDIA_PREPARED_RELEASE_UNCONFIRMED");}
            });
        }finally{closer.shutdown();}
        try{worker.execute(()->{
            boolean clean=true;PreparedSessionPort.Extra ownedExtra=extra;PreparedSessionPort.Peer ownedPeer=peer;
            if(ownedPeer!=null)try{cachePeerFailure(ownedPeer);}catch(Exception|LinkageError ignored){}
            if(ownedExtra!=null)try{ownedExtra.close();}catch(Exception|LinkageError error){clean=false;mediaReleaseError=controlled(error,"MEDIA_PREPARED_EXTRA_RELEASE_UNCONFIRMED");}
            if(ownedPeer!=null)try{ownedPeer.close();}catch(Exception|LinkageError error){clean=false;if(mediaReleaseError.isEmpty())mediaReleaseError=controlled(error,"MEDIA_PREPARED_PEER_RELEASE_UNCONFIRMED");}
            mediaReleased=clean;
        });}catch(RejectedExecutionException rejected){mediaReleaseError="MEDIA_PREPARED_RELEASE_QUEUE_REJECTED";}
        finally{worker.shutdown();}
    }
    private synchronized void finish(String cleanup){
        if(terminal)return;terminal=true;cleanupReason=cleanup;state=cleanup.isEmpty()?"closed":"release_unconfirmed";phase=state;finished.countDown();
    }
    public synchronized JSONObject snapshot() throws Exception {
        JSONObject media=new JSONObject(peerMedia);
        boolean live=current!=null&&owns(current)&&media.optLong("operation",-1)==current.number;
        for(String key:new String[]{"capture_started","playback_started","video_started"})media.put(key,live&&Boolean.TRUE.equals(media.opt(key)));
        return new JSONObject().put("schemaVersion",1).put("session_id",offer.id).put("mode","prepare")
                .put("state",state).put("transport_ready",transportReady).put("operation",operation)
                .put("active_mode",current==null?"":current.mode).put("operation_state",phase).put("ready",ready)
                .put("published",published).put("subscribed",subscribed).put("cleanup_complete","closed".equals(state))
                .put("reason",reason).put("cleanup_reason",cleanupReason).put("pending_rpc",rpcAction)
                .put("diagnostics",new JSONObject().put("failed_stage",failedStage).put("rpc",new JSONObject(rpcDiagnostic)))
                .put("capture_started",media.getBoolean("capture_started")).put("playback_started",media.getBoolean("playback_started"))
                .put("video_started",media.getBoolean("video_started")).put("media",media)
                .put("local_recording",false).put("remote_audio_content","NOT_VERIFIED");
    }
    public boolean awaitClosed(long timeoutMs) throws InterruptedException {return finished.await(Math.max(0,timeoutMs),TimeUnit.MILLISECONDS);}
    private void cachePeerFailure(PreparedSessionPort.Peer peer)throws Exception{
        synchronized(this){if(!"MEDIA_PREPARED_PEER_FAILED".equals(failedStage))return;}
        // 仅释放工作线程读失败快照；真实peer失败后只返回缓存，不再查询原生对象。
        JSONObject value=peer.snapshot(),details=value.optJSONObject("diagnostics");if(details==null)return;
        String at=details.optString("failed_stage");
        synchronized(this){
            if(!"MEDIA_PREPARED_PEER_FAILED".equals(failedStage)||!reason.equals(value.optString("error"))
                    ||value.optLong("operation",-1)!=operation)return;
            if(java.util.Arrays.asList("OPEN","PUBLISH","APPLY_PUBLISH","SUBSCRIBE","NEGOTIATION","ACTIVATE","BEFORE_ACTIVATE",
                    "ROUTE_OPEN","BACKEND_ACTIVATE","HARDWARE_STOP","ROUTE_RECOVER","AFTER_DEACTIVATE","SWITCH_CAMERA").contains(at))failedStage=at;
        }
    }
    private void cachePeer(PreparedSessionPort.Peer peer)throws Exception{
        JSONObject source=peer.snapshot().optJSONObject("media"),out=new JSONObject().put("observed_at_elapsed_ms",clock.elapsed());
        if(source!=null){
            Object facing=source.opt("camera"),count=source.opt("cameras");
            if(facing instanceof String&&camera((String)facing))out.put("camera",facing);
            if(count instanceof Integer&&(Integer)count>=0&&(Integer)count<=16)out.put("cameras",count);
            for(String key:new String[]{"capture_started","playback_started","video_started","capture_active","playback_active",
                    "capture_requested","playback_requested","record_object_present","track_object_present"})
                if(source.opt(key) instanceof Boolean)out.put(key,source.opt(key));
            numbers(source,out,"operation software_audio_buffers software_video_frames",Long.MAX_VALUE);
            for(String side:new String[]{"capture_pcm","playback_pcm"}){
                JSONObject pcm=source.optJSONObject(side);if(pcm==null)continue;
                JSONObject stats=new JSONObject().put("contains_audio",false).put("used_for_authorization",false);
                numbers(pcm,stats,"callbacks pcm_frames samples guard_ready_callbacks invalid_callbacks sample_rate",Long.MAX_VALUE);
                numbers(pcm,stats,"peak rms",1);out.put(side,stats);
            }
        }
        synchronized(this){peerMedia=out.toString();}
    }
    private static void numbers(JSONObject from,JSONObject to,String keys,double maximum)throws Exception{
        for(String key:keys.split(" ")){Object value=from.opt(key);if(value instanceof Number){double number=((Number)value).doubleValue();
            if(!Double.isInfinite(number)&&!Double.isNaN(number)&&number>=0&&number<=maximum)to.put(key,value);
        }}
    }
    private static boolean isExtra(String mode){return "photo".equals(mode)||"alarm".equals(mode);}
    private static boolean isMode(String mode){return isExtra(mode)||"ptt".equals(mode)||"call".equals(mode)||"microphone".equals(mode)||"video".equals(mode);}
    private static boolean camera(String camera){return "front".equals(camera)||"back".equals(camera);}
    private static String controlled(String value,String fallback){
        return value!=null&&value.matches("(?:MEDIA|VISUAL)_[A-Z0-9_]{1,96}")?value:fallback;
    }
    private static String controlled(Throwable failure,String fallback){
        String selected=fallback;
        for(int n=0;failure!=null&&n<8;n++,failure=failure.getCause()){
            String found=controlled(failure.getMessage(),null);if(found!=null)selected=found;
        }
        return selected;
    }
    private static JSONObject rpcDiagnostic(JSONObject input,String action)throws Exception{
        JSONObject out=new JSONObject().put("action",action);if(input==null)return out;
        Object status=input.opt("status");if(status instanceof Integer&&(Integer)status>=100&&(Integer)status<=599)out.put("status",status);
        Object code=input.opt("code");if(code instanceof String&&((String)code).matches("[A-Za-z0-9_.:-]{1,96}"))out.put("code",code);
        JSONArray tracks=input.optJSONArray("tracks"),selected=new JSONArray();
        if(tracks!=null)for(int i=0;i<Math.min(2,tracks.length());i++){
            Object value=tracks.opt(i);if(value instanceof String&&((String)value).matches("[A-Za-z0-9_.:-]{1,96}"))selected.put(value);
        }
        return out.put("tracks",selected);
    }
    private static long integer(JSONObject value,String key) throws Exception {
        Object item=value.opt(key);if(!(item instanceof Integer)&&!(item instanceof Long))throw new IOException();
        long n=((Number)item).longValue();if(n<=0||n>MAX_OPERATION)throw new IOException();return n;
    }
    private static JSONObject extraMessage(PreparedSessionPort.Operation op,JSONObject value) throws Exception {
        String type=value.getString("type");JSONObject out=new JSONObject().put("type",type);
        if(value.has("operation")&&integer(value,"operation")!=op.number)throw new IOException();
        if("photo_preview".equals(type)){
            String jpeg=value.getString("jpeg");
            if(!"photo".equals(op.mode)||jpeg.length()>88000||!jpeg.matches("[A-Za-z0-9+/]+={0,2}"))throw new IOException();
            return out.put("jpeg",jpeg).put("captured_at",integer(value,"captured_at"));
        }
        if("result".equals(type)){
            if(!"photo".equals(op.mode)||!op.reportId.equals(value.optString("report_id")))throw new IOException();
            out.put("report_id",op.reportId).put("captured_at",integer(value,"captured_at")).put("message","照片已保存");
        }else if(!"status".equals(type))throw new IOException();
        if("status".equals(type)){
            String message=value.optString("message");
            if("正在响铃，10秒后自动停止".equals(message)||"响铃已结束".equals(message)||"切换相机请停止后重新拍照".equals(message))
                out.put("message",message);
        }
        String facing=value.optString("camera");if(camera(facing))out.put("camera",facing);
        Object cameras=value.opt("cameras");if(cameras instanceof Integer&&(Integer)cameras>=0&&(Integer)cameras<=16)out.put("cameras",cameras);
        return out;
    }
}
