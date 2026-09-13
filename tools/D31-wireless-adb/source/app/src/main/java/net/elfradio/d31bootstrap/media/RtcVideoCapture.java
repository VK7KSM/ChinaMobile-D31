package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

/** 单次视频轨道所有者；不创建Peer、不订阅音频、不上传、不更改系统音频状态。 */
public final class RtcVideoCapture implements AutoCloseable {
    public interface Events {
        void attached(String facing,int cameras);
        void started(boolean success);
        void frame(int width,int height,long timestampNs);
        void failed(String code);
    }
    public interface Driver {
        void start(Events events,Cancellation cancellation)throws Exception;
        default void cancel(){}
        void close()throws Exception;
    }
    public interface Changed {void changed(JSONObject value);}
    public static final long START_TIMEOUT_MS=10000,FRAME_TIMEOUT_MS=5000,RELEASE_TIMEOUT_MS=3000;
    private final Driver driver;
    private final MediaCapture.Clock clock;
    private final Changed changed;
    private final Cancellation cancellation=new Cancellation();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(threads("d31-video-capture"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(threads("d31-video-watchdog"));
    private final CountDownLatch readySignal=new CountDownLatch(1),closedSignal=new CountDownLatch(1);
    private boolean submitted,stopping,captureStarted,attached,frameSeen,ready;
    private long began,lastFrame,firstFrame,stopAt,lastTimestamp;
    private int width,height,cameras;
    private String facing="",state="idle",error="";
    public RtcVideoCapture(Context app,PeerConnectionFactory factory,PeerConnection peer,RtcVideoEncoding encoding,
            String requestedFacing,boolean unmetered,Changed changed){
        this(new AndroidRtcVideoCapture(app,factory,peer,encoding,requestedFacing,RtcVideoPolicy.profile(unmetered)),AndroidMediaDevice.CLOCK,changed);
    }
    public RtcVideoCapture(Driver driver,MediaCapture.Clock clock,Changed changed){
        if(driver==null||clock==null)throw new IllegalArgumentException("MEDIA_VIDEO_DEPENDENCY");this.driver=driver;this.clock=clock;this.changed=changed;
    }
    private static ThreadFactory threads(String name){return job->{Thread t=new Thread(job,name);t.setDaemon(true);return t;};}
    public synchronized void start()throws IOException {
        if(submitted||stopping)throw new IOException("MEDIA_VIDEO_NOT_REUSABLE");submitted=true;began=clock.elapsed();state="starting";
        timer.scheduleWithFixedDelay(()->tick(),100,100,TimeUnit.MILLISECONDS);
        worker.execute(()->{try{cancellation.check();driver.start(new Events(){
            public void attached(String actual,int count){synchronized(RtcVideoCapture.this){if(stopping)return;facing=actual;cameras=count;attached=true;markReady();}}
            public void started(boolean success){if(!success){stop("MEDIA_VIDEO_START_FAILED");return;}synchronized(RtcVideoCapture.this){if(stopping)return;captureStarted=true;markReady();}}
            public void frame(int w,int h,long stamp){synchronized(RtcVideoCapture.this){
                if(stopping)return;if(w<=0||h<=0||stamp<=0){stop("MEDIA_VIDEO_FRAME_INVALID");return;}
                if(stamp<=lastTimestamp)return;lastTimestamp=stamp;
                width=w;height=h;lastFrame=clock.elapsed();if(!frameSeen){firstFrame=lastFrame;frameSeen=true;}markReady();
            }}
            public void failed(String code){stop(code);}
        },cancellation);}catch(Exception|LinkageError failure){stop(code(failure,"MEDIA_VIDEO_DRIVER_FAILED"));}});
        notifyChanged();
    }
    private static String code(Throwable failure,String fallback){String text=failure.getMessage();return text!=null&&text.matches("MEDIA_[A-Z0-9_]{1,80}")?text:fallback;}
    private void markReady(){if(!ready&&attached&&captureStarted&&frameSeen){ready=true;state="capturing";readySignal.countDown();notifyChanged();}}
    public synchronized boolean ready(){return ready&&!stopping&&clock.elapsed()-lastFrame<FRAME_TIMEOUT_MS;}
    /** 仅在主线媒体工作线程调用，返回前已见真实首帧及轨道挂接。 */
    public void awaitReady(long timeoutMs,Cancellation caller)throws Exception {
        long end=System.nanoTime()/1000000L+Math.max(1,Math.min(timeoutMs,START_TIMEOUT_MS));
        for(;;){try{caller.check();}catch(IOException cancelled){stop("MEDIA_VIDEO_CANCELLED");throw cancelled;}
            synchronized(this){if(ready())return;if(stopping)throw new IOException(error.isEmpty()?"MEDIA_VIDEO_STOPPED":error);}
            long left=end-System.nanoTime()/1000000L;if(left<=0){stop("MEDIA_VIDEO_FIRST_FRAME_TIMEOUT");throw new IOException("MEDIA_VIDEO_FIRST_FRAME_TIMEOUT");}
            readySignal.await(Math.min(left,100),TimeUnit.MILLISECONDS);
        }
    }
    public synchronized void tick(){
        if(!submitted&&!stopping)return;long now=clock.elapsed();
        if(stopping){if(closedSignal.getCount()!=0&&now-stopAt>=RELEASE_TIMEOUT_MS){state="release_unconfirmed";error="MEDIA_VIDEO_RELEASE_TIMEOUT";notifyChanged();timer.shutdown();}return;}
        if(!ready&&now-began>=START_TIMEOUT_MS)stop("MEDIA_VIDEO_FIRST_FRAME_TIMEOUT");
        else if(ready&&now-lastFrame>=FRAME_TIMEOUT_MS)stop("MEDIA_VIDEO_FRAMES_STOPPED");
    }
    private synchronized void stop(String reason){
        if(stopping)return;stopping=true;ready=false;stopAt=clock.elapsed();state="closing";
        error=reason!=null&&reason.matches("MEDIA_[A-Z0-9_]{1,80}")?reason:"MEDIA_VIDEO_FAILED";cancellation.cancel();readySignal.countDown();
        try{driver.cancel();}catch(RuntimeException ignored){}
        if(!submitted)timer.scheduleWithFixedDelay(()->tick(),100,100,TimeUnit.MILLISECONDS);
        worker.execute(()->{boolean released=false;String failure="";
            try{driver.close();released=true;}catch(Exception|LinkageError error){failure=code(error,"MEDIA_VIDEO_RELEASE_UNCONFIRMED");}
            synchronized(RtcVideoCapture.this){state=released?"closed":"release_unconfirmed";if(!failure.isEmpty())error=failure;
                if(released)closedSignal.countDown();notifyChanged();}timer.shutdownNow();worker.shutdown();
        });notifyChanged();
    }
    public synchronized JSONObject snapshot(){try{return new JSONObject().put("state",state).put("error",error).put("camera",facing).put("cameras",cameras)
            .put("track_attached",attached).put("capturer_started",captureStarted).put("first_frame_received",frameSeen)
            .put("first_frame_elapsed_ms",firstFrame).put("width",width).put("height",height).put("cleanup_complete","closed".equals(state))
            .put("local_recording",false).put("audio_created",false);}catch(Exception ignored){return new JSONObject();}}
    private void notifyChanged(){if(changed!=null)try{changed.changed(snapshot());}catch(Exception ignored){}}
    public boolean awaitClosed(long timeoutMs)throws InterruptedException{return closedSignal.await(Math.max(0,timeoutMs),TimeUnit.MILLISECONDS);}
    public void close(){stop("MEDIA_VIDEO_HOST_CLOSED");}
}
