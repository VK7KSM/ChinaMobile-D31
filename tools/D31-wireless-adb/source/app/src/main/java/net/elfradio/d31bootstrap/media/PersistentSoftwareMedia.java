package net.elfradio.d31bootstrap.media;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.webrtc.*;

/** D22软件保活：空输入严格10ms，摄像头停用时1fps黑帧。 */
final class PersistentSoftwareMedia implements AutoCloseable {
    interface Time {long now();void waitNanos(long nanos);}
    private final Time time;private final Runnable failure;
    private final ScheduledExecutorService frames=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"d31-persistent-black-frames"));
    private final Object videoLock=new Object();private CapturerObserver observer;
    private volatile boolean closed,cameraActive;private long lastAudio;private volatile long audioBuffers,videoFrames;
    PersistentSoftwareMedia(Runnable failure){this(failure,new Time(){public long now(){return System.nanoTime();}public void waitNanos(long nanos){LockSupport.parkNanos(nanos);}});}
    PersistentSoftwareMedia(Runnable failure,Time time){this.failure=failure;this.time=time;}
    long audio(ByteBuffer buffer,int format,int channels,int rate,int bytesRead,long timestamp,boolean capture){
        if(bytesRead==0){long target=lastAudio==0?time.now()+10000000L:lastAudio+10000000L;boolean interrupted=Thread.interrupted();
            while(time.now()<target){time.waitNanos(target-time.now());interrupted|=Thread.interrupted();}
            if(interrupted)Thread.currentThread().interrupt();lastAudio=time.now();timestamp=lastAudio;audioBuffers++;
        }else lastAudio=0;
        boolean valid=buffer!=null&&!buffer.isReadOnly()&&buffer.capacity()==320&&format==2&&channels==1&&rate==16000&&(bytesRead==0||bytesRead==320);
        if(!valid||!capture||bytesRead==0||closed)zero(buffer);
        if(!valid)failure.run();return timestamp;
    }
    static void zero(ByteBuffer buffer){if(buffer!=null&&!buffer.isReadOnly()){for(int i=0;i<buffer.capacity();i++)buffer.put(i,(byte)0);}}
    void start(CapturerObserver target){synchronized(videoLock){observer=target;target.onCapturerStarted(true);}
        frames.scheduleWithFixedDelay(()->{try{synchronized(videoLock){if(closed||cameraActive)return;
            VideoFrame frame=black(time.now());try{observer.onFrameCaptured(frame);videoFrames++;}finally{frame.release();}
        }}catch(Exception|LinkageError e){failure.run();}},0,1000,TimeUnit.MILLISECONDS);
    }
    void camera(boolean active){synchronized(videoLock){cameraActive=active;if(!active&&observer!=null&&!closed)observer.onCapturerStarted(true);}}
    void cameraFrame(VideoFrame frame){synchronized(videoLock){if(!closed&&cameraActive&&observer!=null)observer.onFrameCaptured(frame);}}
    static VideoFrame black(long at){int w=160,h=120;return new VideoFrame(JavaI420Buffer.wrap(w,h,plane(w*h,16),w,plane(w*h/4,128),w/2,plane(w*h/4,128),w/2,null),0,at);}
    private static ByteBuffer plane(int size,int value){ByteBuffer b=ByteBuffer.allocateDirect(size);for(int i=0;i<size;i++)b.put((byte)value);b.flip();return b;}
    long audioBuffers(){return audioBuffers;}long videoFrames(){return videoFrames;}
    public void close()throws Exception{closed=true;frames.shutdown();if(!frames.awaitTermination(500,TimeUnit.MILLISECONDS))throw new java.io.IOException("MEDIA_PERSISTENT_VIDEO_RELEASE_UNCONFIRMED");
        synchronized(videoLock){if(observer!=null){observer.onCapturerStopped();observer=null;}}}
}
