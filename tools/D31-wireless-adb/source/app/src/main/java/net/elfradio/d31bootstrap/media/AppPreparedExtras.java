package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 每个prepare操作独占一个后端；父控制器拥有WS、媒体锁及idle发布权。 */
public final class AppPreparedExtras implements PreparedSessionPort.Extra {
    interface Captured { void captured(JSONObject receipt)throws Exception; }
    interface Backend extends AutoCloseable {
        JSONObject photo(String reportId,String camera,Cancellation cancellation,Captured captured)throws Exception;
        void alarm(String operationId)throws Exception;
        String alarmState()throws Exception;
        void cancel();
        void close()throws Exception;
    }
    interface Factory { Backend create()throws Exception; }
    interface Preview { JSONObject create(JSONObject receipt)throws Exception; }
    private final PreparedSessionPort.Operation operation;
    private final PreparedSessionPort.OperationEvents events;
    private final Factory factory;
    private final Preview preview;
    private final long closeTimeoutMs;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final AtomicBoolean abortStarted=new AtomicBoolean();
    private final CountDownLatch workDone=new CountDownLatch(1),abortDone=new CountDownLatch(1),closeDone=new CountDownLatch(1);
    private volatile Backend backend;
    private volatile boolean ready;
    private volatile String cleanupError="";
    private boolean started,closeStarted;

    public AppPreparedExtras(Context app,File files,RtcOffer offer,PreparedSessionPort.Operation operation,
            PreparedSessionPort.OperationEvents events,AudioGuard current)throws Exception {
        this(app,files,offer,operation,events,current,null);
    }
    public AppPreparedExtras(Context app,File files,RtcOffer offer,PreparedSessionPort.Operation operation,
            PreparedSessionPort.OperationEvents events,AudioGuard current,AndroidAudioOccupancy.Source occupancySource)throws Exception {
        this(operation,events,platform(app,files,offer,operation,current,occupancySource),PreparedPhotoPreview::create,5000);
    }
    private static Factory platform(Context app,File files,RtcOffer offer,PreparedSessionPort.Operation op,AudioGuard current,
            AndroidAudioOccupancy.Source occupancySource)throws Exception {
        if(app==null||files==null)throw new IOException("PREPARED_EXTRA_BINDING_INVALID");
        if(!files.getCanonicalFile().equals(app.getFilesDir().getCanonicalFile()))throw new IOException("VISUAL_PRIVATE_DIRECTORY_INVALID");
        final JSONObject identity=uploadIdentity(offer,op);
        return ()->{
            final PhotoAlarmBackend delegate=new PhotoAlarmBackend(app,new Handler(Looper.getMainLooper()),identity,true,current,occupancySource);
            return new Backend() {
                public JSONObject photo(String id,String camera,Cancellation cancel,Captured captured)throws Exception {
                    return delegate.photo(id,camera,cancel,captured::captured);
                }
                public void alarm(String id)throws Exception {delegate.alarm(id);}
                public String alarmState()throws Exception {return delegate.alarmState();}
                public void cancel(){delegate.cancel();}
                public void close()throws Exception {delegate.close();}
            };
        };
    }
    static JSONObject uploadIdentity(RtcOffer offer,PreparedSessionPort.Operation op)throws Exception {
        if(offer==null||op==null||!"prepare".equals(offer.mode)||!((offer.id+"-"+op.number).equals(op.reportId)))
            throw new IOException("PREPARED_EXTRA_BINDING_INVALID");
        // 复用上传器身份规则；副本只留在本操作内存，不进入事件或异常文本。
        try {
            JSONObject identity=offer.uploadIdentity();
            if(identity==null)throw new IOException();
            try(PhotoAlarmUpload checked=new PhotoAlarmUpload(new java.net.URI("https://v.elfradio.net"),identity)) { }
            return identity;
        }catch(Exception invalid){throw new IOException("VISUAL_IDENTITY_INVALID");}
    }
    AppPreparedExtras(PreparedSessionPort.Operation operation,PreparedSessionPort.OperationEvents events,
            Factory factory,Preview preview,long closeTimeoutMs)throws Exception {
        if(operation==null||events==null||factory==null||preview==null||closeTimeoutMs<=0
                ||operation.number<=0||!("photo".equals(operation.mode)||"alarm".equals(operation.mode))
                ||!operation.reportId.matches("[A-Za-z0-9_-]{1,96}")
                ||!("front".equals(operation.camera)||"back".equals(operation.camera)))
            throw new IOException("PREPARED_EXTRA_OPERATION_INVALID");
        this.operation=operation;this.events=events;this.factory=factory;this.preview=preview;this.closeTimeoutMs=closeTimeoutMs;
    }
    public synchronized void start()throws Exception {
        if(started||cancelled.get()||operation.cancellation.isCancelled())throw new IOException("PREPARED_EXTRA_NOT_STARTABLE");
        started=true;
        thread("d31-prepared-extra",()->run()).start();
    }
    private void run() {
        try {
            check();backend=factory.create();check();
            if("photo".equals(operation.mode)) {
                JSONObject result=backend.photo(operation.reportId,operation.camera,operation.cancellation,receipt->{
                    check();
                    if(!operation.reportId.equals(receipt.optString("report_id"))||!"completed".equals(receipt.optString("state")))
                        throw new IOException("VISUAL_PHOTO_RESULT_BINDING");
                    markReady();
                    JSONObject small=preview.create(receipt);check();if(small!=null)message(small);
                });
                check();
                if(!ready||!operation.reportId.equals(result.optString("report_id"))||!"result".equals(result.optString("type")))
                    throw new IOException("VISUAL_PHOTO_RESULT_BINDING");
                message(result);
            } else {
                backend.alarm(operation.reportId);check();markReady();
                message(new JSONObject().put("type","status").put("message","警报已开启，响30秒、停10秒循环，停止或断开连接后结束"));
                for(;;) {
                    check();String state=backend.alarmState();
                    if("completed".equals(state)) {
                        message(new JSONObject().put("type","status").put("message","响铃已结束"));break;
                    }
                    if(!"playing".equals(state))throw new IOException("VISUAL_ALARM_FAILED");
                    Thread.sleep(100);
                }
            }
        }catch(Exception failure) {
            if(!cancelled.get()&&!operation.cancellation.isCancelled())events.failed(code(failure));
        }finally {workDone.countDown();}
    }
    private void check()throws Exception {
        operation.cancellation.check();if(cancelled.get())throw new IOException("MEDIA_CANCELLED");
    }
    private void markReady()throws Exception {check();ready=true;events.changed();}
    private void message(JSONObject value)throws Exception {
        check();String type=value.optString("type");
        if(!("status".equals(type)||"photo_preview".equals(type)||"result".equals(type)))throw new IOException("PREPARED_EXTRA_MESSAGE_INVALID");
        // 控制器仍须在实际发送时核验闭包的原代次，取消不能等待WS发送线程。
        events.message(new JSONObject(value.toString()).put("operation",operation.number));
    }
    public boolean ready(){return ready&&!cancelled.get()&&!operation.cancellation.isCancelled();}
    public void switchCamera(String camera)throws Exception {
        check();
        if(!("front".equals(camera)||"back".equals(camera)))throw new IOException("MEDIA_CAMERA_INVALID");
        message(new JSONObject().put("type","status").put("camera",operation.camera)
                .put("message","切换相机请停止后重新拍照"));
    }
    public void cancel() {
        cancelled.set(true);operation.cancellation.cancel();
        if(abortStarted.compareAndSet(false,true))thread("d31-prepared-extra-cancel",()->{
            try{Backend current=backend;if(current!=null)current.cancel();}
            catch(Exception failure){cleanupError="VISUAL_CANCEL_PENDING";}
            finally{abortDone.countDown();}
        }).start();
    }
    public void close()throws Exception {
        cancel();
        synchronized(this) {
            if(!closeStarted) {
                closeStarted=true;if(!started)workDone.countDown();
                thread("d31-prepared-extra-close",()->{
                    try {
                        workDone.await();abortDone.await();
                        Backend current=backend;if(current!=null)current.close();
                    }catch(Exception failure){cleanupError="VISUAL_CLEANUP_PENDING";}
                    finally{closeDone.countDown();}
                }).start();
            }
        }
        if(!closeDone.await(closeTimeoutMs,TimeUnit.MILLISECONDS))throw new IOException("VISUAL_CLEANUP_PENDING");
        if(!cleanupError.isEmpty())throw new IOException(cleanupError);
    }
    static String code(Exception failure) {
        String value=failure.getMessage();
        if(value!=null&&value.matches("[A-Z0-9_]{1,48}:[A-Z0-9_]{1,48}"))value=value.replace(':','_');
        if(value!=null&&value.matches("ALARM_[A-Z0-9_]+"))value="VISUAL_"+value;
        return value!=null&&value.matches("[A-Z0-9_]{1,100}")?value:"VISUAL_OPERATION_FAILED";
    }
    private static Thread thread(String name,Runnable job){Thread thread=new Thread(job,name);thread.setDaemon(true);return thread;}
}
