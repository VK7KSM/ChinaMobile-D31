package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 相机/上传在单工作线程，停止信令和独立时钟不排在采集后面。 */
public final class PhotoAlarmSession implements AutoCloseable {
    public interface Events { void message(String raw); void disconnected(); }
    public interface Wire { void connect(PhotoAlarmOffer offer,Events events)throws Exception; void send(JSONObject value)throws Exception; void close(); }
    public interface Backend extends AutoCloseable {
        JSONObject photo(PhotoAlarmOffer offer,Cancellation cancel)throws Exception;
        void alarm(PhotoAlarmOffer offer)throws Exception;
        void checkAlarm()throws Exception;
        void cancel();
        void close()throws Exception;
    }
    private final PhotoAlarmOffer offer;
    private final Wire wire;
    private final Backend backend;
    private final MediaCapture.Clock clock;
    private final Cancellation cancellation=new Cancellation();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(threads("d31-visual-session"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(threads("d31-visual-deadline"));
    private final ExecutorService aborter=Executors.newFixedThreadPool(2,threads("d31-visual-release"));
    private volatile boolean closed,cleaned,wireClosed;
    private boolean hello;
    private volatile String state="connecting",error="";
    private volatile String photoResult="";
    private volatile long deadline;
    private long lease;
    public PhotoAlarmSession(PhotoAlarmOffer offer,Wire wire,Backend backend,MediaCapture.Clock clock){
        this.offer=offer;this.wire=wire;this.backend=backend;this.clock=clock;
        deadline=clock.elapsed()+Math.max(0,offer.expiresAt-clock.wall());lease=clock.elapsed()+15000;
    }
    static ThreadFactory threads(final String name){return job->{Thread t=new Thread(job,name);t.setDaemon(true);return t;};}
    public void start(){
        timer.scheduleWithFixedDelay(()->tick(),100,100,TimeUnit.MILLISECONDS);
        worker.execute(()->{try{wire.connect(offer,new Events(){
            public void message(String raw){receive(raw);}
            public void disconnected(){stop("DISCONNECTED");}
        });}catch(Exception failure){fail(failure);}});
    }
    public synchronized void renew(){if(!closed)lease=clock.elapsed()+15000;}
    public void tick(){
        long now=clock.elapsed();if(closed)return;
        if(now>=deadline){stop("TIMED_OUT");return;}
        synchronized(this){if(now>=lease){stop("OWNER_EXPIRED");return;}}
        if("playing".equals(state))try{backend.checkAlarm();}catch(Exception failure){fail(failure);}
    }
    void receive(String raw){
        try {
            if(raw==null||raw.length()>96000)throw new IOException("VISUAL_MESSAGE_SIZE");
            JSONObject value=new JSONObject(raw);String type=value.optString("type");
            if("closed".equals(type)||"stop".equals(type)){stop("STOPPED");return;}
            if("switch".equals(type)){
                if(!closed)wire.send(new JSONObject().put("type","status").put("camera",offer.camera).put("cameras",1)
                        .put("message","切换相机请结束后重新拍照"));return;
            }
            if(!"hello".equals(type))return;
            synchronized(this){
                if(closed||hello)return;
                if(clock.wall()>=offer.expiresAt||!offer.mode.equals(value.optString("mode"))||!offer.camera.equals(value.optString("camera")))
                    throw new IOException("VISUAL_HELLO_MISMATCH");
                hello=true;state="starting";deadline=clock.elapsed()+60000;
            }
            worker.execute(()->activate());
        }catch(Exception failure){fail(failure);}
    }
    private void activate(){
        try {
            cancellation.check();
            if("photo".equals(offer.mode)){
                state="capturing";wire.send(new JSONObject().put("type","status").put("message","正在拍照").put("camera",offer.camera).put("cameras",1));
                wire.send(new JSONObject().put("type","ready"));
                JSONObject result=backend.photo(offer,cancellation);cancellation.check();
                if(!offer.id.equals(result.optString("report_id"))||!"result".equals(result.optString("type")))throw new IOException("VISUAL_PHOTO_RESULT_BINDING");
                wire.send(result);photoResult=result.toString();state="completed";
                // 保持很短的控制连接让结果先发送；不等待下一次core周期。
                deadline=clock.elapsed()+1000;
            }else{
                backend.alarm(offer);cancellation.check();state="playing";deadline=clock.elapsed()+AlarmTasks.DURATION_MS;
                wire.send(new JSONObject().put("type","ready"));
                wire.send(new JSONObject().put("type","status").put("message","正在响铃，10秒后自动停止"));
            }
        }catch(Exception failure){fail(failure);}
    }
    private void fail(Exception failure){String code=failure.getMessage();error=code!=null&&code.matches("[A-Z0-9_]{1,100}")?code:"VISUAL_OPERATION_FAILED";
        try{wire.send(new JSONObject().put("type","status").put("message","操作失败："+error));}catch(Exception ignored){}stop("FAILED");}
    private void stop(String reason){
        synchronized(this){if(closed)return;closed=true;if(!"completed".equals(state))state=reason.toLowerCase(java.util.Locale.US);}
        cancellation.cancel();timer.shutdownNow();
        // stop可从APP主线程或Binder死亡回调进入；socket及HTTP退出也不能阻塞调用者。
        aborter.execute(()->{try{wire.close();wireClosed=true;}catch(Exception failed){error="VISUAL_SOCKET_CLEANUP_PENDING";}});
        aborter.execute(()->{
            try{backend.cancel();}catch(Exception failed){error="VISUAL_CANCEL_PENDING";}
            worker.execute(()->{try{backend.close();cleaned=true;}catch(Exception failed){error="VISUAL_CLEANUP_PENDING";}finally{worker.shutdown();}});
        });
        aborter.shutdown();
    }
    public JSONObject snapshot(){try{JSONObject value=new JSONObject().put("session_id",offer.id).put("mode",offer.mode).put("state",state)
                .put("closed",closed).put("cleanup_complete",cleaned&&wireClosed).put("error",error);
        if(!photoResult.isEmpty())value.put("result",new JSONObject(photoResult));return value;}catch(Exception ignored){return new JSONObject();}}
    public boolean finished(){return closed&&cleaned&&wireClosed;}
    public void close(){stop("STOPPED");}
}
