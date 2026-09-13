package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.app.AppOpsManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.webrtc.*;

/** 复用固定WebRTC版本的Camera1预览帧链；不调用takePicture或MediaRecorder。 */
public final class AndroidRtcVideoCapture implements RtcVideoCapture.Driver {
    private final Context app;
    private final PeerConnectionFactory factory;
    private final PeerConnection peer;
    private final RtcVideoEncoding encoding;
    private final String requested;
    private final RtcVideoPolicy profile;
    private CameraVideoCapturer camera;
    private SurfaceTextureHelper texture;
    private VideoSource source;
    private VideoTrack track;
    private String senderId;
    private boolean senderAttached;
    private MediaFiles.Lease lease;
    private volatile boolean stopping;
    private boolean released;
    public AndroidRtcVideoCapture(Context app,PeerConnectionFactory factory,PeerConnection peer,RtcVideoEncoding encoding,
            String requested,RtcVideoPolicy profile){
        if(app==null||factory==null||peer==null||encoding==null||profile==null)throw new IllegalArgumentException("MEDIA_VIDEO_DEPENDENCY");
        this.app=app;this.factory=factory;this.peer=peer;this.encoding=encoding;this.requested=requested;this.profile=profile;
    }
    public void start(final RtcVideoCapture.Events events,Cancellation cancellation)throws Exception {
        cancellation.check();MediaReadiness.requireApplicationIdentity(app,"net.elfradio.d31bootstrap");
        if(stopping||camera!=null||lease!=null)throw new IOException("MEDIA_VIDEO_NOT_REUSABLE");
        int uid=android.os.Process.myUid();AppOpsManager ops=(AppOpsManager)app.getSystemService(Context.APP_OPS_SERVICE);
        if(app.checkPermission(Manifest.permission.CAMERA,android.os.Process.myPid(),uid)!=PackageManager.PERMISSION_GRANTED
                ||ops==null||ops.checkOpNoThrow(AppOpsManager.OPSTR_CAMERA,uid,app.getPackageName())!=AppOpsManager.MODE_ALLOWED)
            throw new IOException("MEDIA_VIDEO_CAMERA_PERMISSION");
        // 与静态照片共用相机锁；音频由主线已有会话所有者管理。
        lease=MediaFiles.lease(new File(app.getFilesDir().getCanonicalFile(),"visual-photo"));
        Camera1Enumerator cameras=new Camera1Enumerator(false);String[] names=cameras.getDeviceNames();
        boolean[] front=new boolean[names.length];for(int i=0;i<names.length;i++)front[i]=cameras.isFrontFacing(names[i]);
        int selected=RtcVideoPolicy.camera(front,requested);final String actual=front[selected]?"front":"back";
        camera=cameras.createCapturer(names[selected],new CameraVideoCapturer.CameraEventsHandler(){
            private void failed(String code){if(!stopping)events.failed(code);}
            public void onCameraError(String ignored){failed("MEDIA_VIDEO_CAMERA_ERROR");}
            public void onCameraDisconnected(){failed("MEDIA_VIDEO_CAMERA_DISCONNECTED");}
            public void onCameraFreezed(String ignored){failed("MEDIA_VIDEO_CAMERA_FROZEN");}
            public void onCameraOpening(String ignored){}
            public void onFirstFrameAvailable(){}
            public void onCameraClosed(){failed("MEDIA_VIDEO_CAMERA_CLOSED");}
        });
        if(camera==null)throw new IOException("MEDIA_VIDEO_CAPTURER_MISSING");cancellation.check();
        source=factory.createVideoSource(false);if(source==null)throw new IOException("MEDIA_VIDEO_SOURCE_MISSING");
        texture=SurfaceTextureHelper.create("d31-rtc-camera",encoding.context());
        if(texture==null)throw new IOException("MEDIA_VIDEO_TEXTURE_MISSING");
        final CapturerObserver downstream=source.getCapturerObserver();
        camera.initialize(texture,app,new CapturerObserver(){
            public void onCapturerStarted(boolean success){if(stopping)return;try{downstream.onCapturerStarted(success);events.started(success);}
                catch(RuntimeException|LinkageError failure){events.failed("MEDIA_VIDEO_START_CALLBACK_FAILED");}}
            public void onCapturerStopped(){if(stopping)return;try{downstream.onCapturerStopped();}finally{events.failed("MEDIA_VIDEO_CAPTURE_STOPPED");}}
            public void onFrameCaptured(VideoFrame frame){if(stopping)return;
                try{int width=frame.getRotatedWidth(),height=frame.getRotatedHeight();long stamp=frame.getTimestampNs();
                    if(width<=0||height<=0||stamp<=0)throw new IllegalArgumentException();
                    downstream.onFrameCaptured(frame);events.frame(width,height,stamp);
                }catch(RuntimeException|LinkageError failure){events.failed("MEDIA_VIDEO_FRAME_FORWARD_FAILED");}
            }
        });
        track=factory.createVideoTrack("video",source);if(track==null)throw new IOException("MEDIA_VIDEO_TRACK_MISSING");
        cancellation.check();
        RtpTransceiver transceiver=peer.addTransceiver(track,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
        if(transceiver==null)throw new IOException("MEDIA_VIDEO_TRANSCEIVER_MISSING");senderAttached=true;
        RtpSender sender=transceiver.getSender();senderId=sender.id();
        ArrayList<RtpCapabilities.CodecCapability> codecs=new ArrayList<>(factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs);
        // 只重排实际支持的编码器；保留VP8等回退，不宣称H264硬件编码已验收。
        Collections.sort(codecs,(a,b)->Boolean.compare(!"H264".equalsIgnoreCase(a.name),!"H264".equalsIgnoreCase(b.name)));
        transceiver.setCodecPreferences(codecs);
        RtpParameters parameters=sender.getParameters();
        if(parameters==null||parameters.encodings.isEmpty())throw new IOException("MEDIA_VIDEO_ENCODINGS_MISSING");
        for(RtpParameters.Encoding value:parameters.encodings){value.maxBitrateBps=profile.maxBitrate;value.maxFramerate=profile.fps;}
        if(!sender.setParameters(parameters))throw new IOException("MEDIA_VIDEO_LIMITS_REJECTED");
        events.attached(actual,names.length);cancellation.check();camera.startCapture(profile.width,profile.height,profile.fps);
    }
    /** 主线必须先等待此处释放成功，再销毁Peer、factory和EGL。 */
    public void cancel(){stopping=true;}
    public void close()throws Exception {
        if(released)return;stopping=true;
        if(camera!=null){
            // stopCapture未返回时不销毁其余原生对象；外层独立时钟报告释放未确认。
            releaseStep("CAMERA_STOP",()->{
                camera.stopCapture();
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            });
            // 固定CameraCapturer.stopCapture只投递CameraSession.stop；FIFO屏障完成后才可销毁纹理。
            if(texture!=null){
                releaseStep("CAMERA_BARRIER",()->{
                    CountDownLatch stopped=new CountDownLatch(1);
                    if(!texture.getHandler().post(()->stopped.countDown()))throw new IOException("MEDIA_VIDEO_CAMERA_BARRIER_REJECTED");
                    if(!stopped.await(2500,TimeUnit.MILLISECONDS))throw new IOException("MEDIA_VIDEO_CAMERA_BARRIER_TIMEOUT");
                });
            }
            releaseStep("CAMERA_DISPOSE",()->camera.dispose());camera=null;
        }
        if(senderAttached){
            // getTransceivers会dispose旧sender包装；只保存ID，关闭时取得当前包装。
            final RtpSender[] current=new RtpSender[1];
            releaseStep("SENDER_LOOKUP",()->{
                if(senderId==null||senderId.isEmpty())throw new IOException("MEDIA_VIDEO_SENDER_ID_MISSING");
                for(RtpSender candidate:peer.getSenders())if(senderId.equals(candidate.id())){
                    if(current[0]!=null)throw new IOException("MEDIA_VIDEO_SENDER_AMBIGUOUS");
                    current[0]=candidate;
                }
                if(current[0]==null)throw new IOException("MEDIA_VIDEO_SENDER_NOT_FOUND");
            });
            // removeTrack负责解绑；不先setTrack(null)，也不dispose由Peer拥有的包装。
            releaseStep("SENDER_REMOVE",()->{
                if(!peer.removeTrack(current[0]))throw new IOException("MEDIA_VIDEO_SENDER_REMOVE_REJECTED");
            });
            senderAttached=false;senderId=null;
        }
        if(track!=null){releaseStep("TRACK_DISPOSE",()->track.dispose());track=null;}
        if(source!=null){releaseStep("SOURCE_DISPOSE",()->source.dispose());source=null;}
        if(texture!=null){releaseStep("TEXTURE_DISPOSE",()->texture.dispose());texture=null;}
        if(lease!=null){releaseStep("LEASE_RELEASE",()->lease.close());lease=null;}released=true;
    }
    interface ReleaseOperation {void run()throws Exception;}
    static void releaseStep(String stage,ReleaseOperation operation)throws IOException {
        try{operation.run();}
        catch(Exception|LinkageError failure){
            String code="MEDIA_VIDEO_"+stage+"_EXCEPTION";
            if(failure instanceof InterruptedException){Thread.currentThread().interrupt();code="MEDIA_VIDEO_"+stage+"_INTERRUPTED";}
            else if(failure instanceof IOException&&failure.getMessage()!=null&&failure.getMessage().matches("MEDIA_[A-Z0-9_]{1,80}"))code=failure.getMessage();
            // 不输出异常消息、凭证或会话标识；仅输出操作、类型和代码位置。
            StringBuilder detail=new StringBuilder(code).append(" type=").append(failure.getClass().getSimpleName());
            StackTraceElement[] stack=failure.getStackTrace();
            for(int i=0;i<Math.min(4,stack.length);i++)detail.append(" at=").append(stack[i].getClassName()).append('#').append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
            try{android.util.Log.w("D31VideoRelease",detail.toString());}catch(RuntimeException ignored){}
            throw new IOException(code,failure);
        }
    }
}
