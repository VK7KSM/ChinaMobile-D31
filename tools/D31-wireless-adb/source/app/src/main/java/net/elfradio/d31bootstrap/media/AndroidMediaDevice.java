package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** API23平台实现，不改音频模式、音量、路由，不在构造器打开设备。 */
@SuppressWarnings("deprecation")
public final class AndroidMediaDevice implements MediaCapture.Device {
    // 原生相机退出无法强制中断；释放未确认时跨实例拒绝再次采集。
    private static final AtomicBoolean cameraOccupied=new AtomicBoolean();
    public static final MediaCapture.Clock CLOCK=new MediaCapture.Clock(){
        public long wall(){return System.currentTimeMillis();}
        public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
    };
    private final Context context;
    private final MediaCapture.Clock clock;
    public AndroidMediaDevice(Context context,MediaCapture.Clock clock){this.context=context;this.clock=clock;}
    private void permission(String permission) throws IOException {
        if(context==null||context.checkPermission(permission,android.os.Process.myPid(),android.os.Process.myUid())!=PackageManager.PERMISSION_GRANTED)throw new IOException("MEDIA_PERMISSION_MISSING");
    }
    private void check(Cancellation cancel,long deadline,AudioGuard guard)throws Exception {
        cancel.check(); if(clock.elapsed()>=deadline)throw new IOException("MEDIA_TIMED_OUT"); guard.requireIdle();
    }
    public MediaCapture.Captured capture(CaptureRequest request,File output,Cancellation cancel,long deadline,AudioGuard guard)throws Exception {
        if(cameraOccupied.get())throw new IOException("MEDIA_CAMERA_RELEASE_PENDING");
        return "photo".equals(request.kind)?photo(request,output,cancel,deadline,guard):audio(request,output,cancel,deadline,guard);
    }
    private MediaCapture.Captured photo(final CaptureRequest request,final File output,final Cancellation cancel,final long deadline,final AudioGuard guard)throws Exception {
        permission(Manifest.permission.CAMERA);check(cancel,deadline,guard);
        if(!cameraOccupied.compareAndSet(false,true))throw new IOException("MEDIA_CAMERA_RELEASE_PENDING");
        final HandlerThread thread=new HandlerThread("d31-on-demand-photo");thread.start();
        final Handler handler=new Handler(thread.getLooper());
        final CountDownLatch finished=new CountDownLatch(1),released=new CountDownLatch(1);
        final AtomicBoolean abort=new AtomicBoolean();
        final Camera[] camera=new Camera[1];final SurfaceTexture[] texture=new SurfaceTexture[1];
        final Exception[] error=new Exception[1];final MediaCapture.Captured[] captured=new MediaCapture.Captured[1];
        final AtomicBoolean cleanupAttempted=new AtomicBoolean();
        final Runnable cleanup=new Runnable(){public void run(){
            if(!cleanupAttempted.compareAndSet(false,true))return;
            boolean clean=true;
            try {if(camera[0]!=null){try{camera[0].stopPreview();}catch(Exception ignored){}camera[0].release();}}
            catch(Exception failure){clean=false;error[0]=new IOException("MEDIA_CAMERA_RELEASE_PENDING");}
            finally {
                camera[0]=null;
                try{if(texture[0]!=null)texture[0].release();}catch(Exception failure){clean=false;error[0]=new IOException("MEDIA_CAMERA_RELEASE_PENDING");}
                texture[0]=null;if(clean)cameraOccupied.set(false);released.countDown();
            }
        }};
        try {
            if(!handler.post(new Runnable(){public void run(){try {
                check(cancel,deadline,guard);if(abort.get())throw new IOException("MEDIA_CANCELLED");
                int selected=-1;Camera.CameraInfo info=new Camera.CameraInfo();
                int facing="front".equals(request.camera)?Camera.CameraInfo.CAMERA_FACING_FRONT:Camera.CameraInfo.CAMERA_FACING_BACK;
                for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==facing){selected=i;break;}}
                if(selected<0)throw new IOException("MEDIA_CAMERA_UNAVAILABLE");
                Camera.getCameraInfo(selected,info);camera[0]=Camera.open(selected);
                if(abort.get())throw new IOException("MEDIA_CANCELLED");
                Camera.Parameters parameters=camera[0].getParameters();
                List<Camera.Size> sizes=parameters.getSupportedPictureSizes();Camera.Size chosen=null;
                for(Camera.Size size:sizes)if(size.width*size.height<=640*480&&(chosen==null||size.width*size.height>chosen.width*chosen.height))chosen=size;
                if(chosen==null)throw new IOException("MEDIA_BOUNDED_PHOTO_UNAVAILABLE");
                parameters.setPictureSize(chosen.width,chosen.height);parameters.setJpegQuality(70);parameters.setRotation(info.orientation);
                camera[0].setParameters(parameters);texture[0]=new SurfaceTexture(0);camera[0].setPreviewTexture(texture[0]);camera[0].startPreview();
                final int actualCamera=selected;
                if(!handler.postDelayed(new Runnable(){public void run(){try {
                    check(cancel,deadline,guard);if(abort.get())throw new IOException("MEDIA_CANCELLED");
                    camera[0].takePicture(null,null,new Camera.PictureCallback(){public void onPictureTaken(byte[] data,Camera unused){try {
                        long capturedAt=clock.wall();check(cancel,deadline,guard);if(abort.get())throw new IOException("MEDIA_CANCELLED");
                        if(data==null||data.length<4||data.length>MediaFiles.MAX_BYTES||(data[0]&255)!=255||(data[1]&255)!=216)throw new IOException("MEDIA_JPEG_INVALID");
                        try(FileOutputStream stream=new FileOutputStream(output)){stream.write(data);stream.getFD().sync();}
                        captured[0]=new MediaCapture.Captured(capturedAt,capturedAt,"camera:"+actualCamera+":"+request.camera,"image/jpeg");
                    }catch(Exception failure){error[0]=failure;}finally{cleanup.run();finished.countDown();}}});
                }catch(Exception failure){error[0]=failure;cleanup.run();finished.countDown();}}},400))throw new IOException("MEDIA_CAMERA_THREAD_FAILED");
            }catch(Exception failure){error[0]=failure;cleanup.run();finished.countDown();}}}))throw new IOException("MEDIA_CAMERA_THREAD_FAILED");
            while(!finished.await(100,TimeUnit.MILLISECONDS))check(cancel,deadline,guard);
            if(error[0]!=null)throw error[0];if(captured[0]==null)throw new IOException("MEDIA_CAMERA_RESULT_MISSING");
            return captured[0];
        } finally {
            abort.set(true);handler.removeCallbacksAndMessages(null);handler.post(cleanup);
            boolean interrupted=Thread.interrupted();
            try {if(!released.await(2000,TimeUnit.MILLISECONDS))throw new IOException("MEDIA_CAMERA_RELEASE_PENDING");}
            finally {thread.quitSafely();if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private MediaCapture.Captured audio(CaptureRequest request,File output,Cancellation cancel,long deadline,AudioGuard guard)throws Exception {
        permission(Manifest.permission.RECORD_AUDIO);check(cancel,deadline,guard);
        final MediaRecorder recorder=new MediaRecorder();final AtomicBoolean failure=new AtomicBoolean();boolean started=false;
        try {
            recorder.setOnErrorListener(new MediaRecorder.OnErrorListener(){public void onError(MediaRecorder r,int what,int extra){failure.set(true);}});
            recorder.setOnInfoListener(new MediaRecorder.OnInfoListener(){public void onInfo(MediaRecorder r,int what,int extra){
                if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)failure.set(true);
            }});
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioSamplingRate(16000);recorder.setAudioEncodingBitRate(32000);
            recorder.setMaxFileSize(MediaFiles.MAX_BYTES);recorder.setMaxDuration(request.durationMs+1000);recorder.setOutputFile(output.getAbsolutePath());
            recorder.prepare();check(cancel,deadline,guard);recorder.start();started=true;
            long start=clock.wall(),until=clock.elapsed()+request.durationMs;
            while(clock.elapsed()<until){check(cancel,deadline,guard);if(failure.get())throw new IOException("MEDIA_RECORDER_FAILED");Thread.sleep(100);}
            check(cancel,deadline,guard);recorder.stop();started=false;
            return new MediaCapture.Captured(start,clock.wall(),"microphone:MIC","audio/mp4");
        } finally {
            if(started)try{recorder.stop();}catch(Exception ignored){}
            recorder.release();
        }
    }
}
