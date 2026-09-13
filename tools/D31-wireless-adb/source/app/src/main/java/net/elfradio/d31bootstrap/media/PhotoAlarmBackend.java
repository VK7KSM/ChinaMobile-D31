package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import java.io.*;
import java.net.URI;
import org.json.JSONObject;
import org.json.JSONArray;

/** 真实APP采集实现；照片不请求麦克风、音频焦点或通话空闲。 */
final class PhotoAlarmBackend implements PhotoAlarmSession.Backend {
    private static final AudioGuard NO_AUDIO=()->{};
    private final Context app;
    private final Handler handler;
    private final File files;
    private final PhotoAlarmUpload upload;
    private final boolean sessionOwnsMedia;
    private final AudioGuard admission;
    private final AndroidAudioOccupancy.Source occupancySource;
    interface Captured { void captured(JSONObject receipt)throws Exception; }
    private volatile AlarmTasks alarms;
    private volatile PreparedAlarm preparedAlarm;
    private volatile AndroidAudioOccupancy occupancy;
    private volatile boolean cancelled,toneOwned,cameraCleanupPending;
    private volatile Exception cancellationFailure;
    private MediaFiles.Lease photoLease;
    PhotoAlarmBackend(Context app,Handler handler,JSONObject identity)throws Exception {
        this(app,handler,identity,false,NO_AUDIO);
    }
    PhotoAlarmBackend(Context app,Handler handler,JSONObject identity,boolean sessionOwnsMedia,AudioGuard admission)throws Exception {
        this(app,handler,identity,sessionOwnsMedia,admission,null);
    }
    PhotoAlarmBackend(Context app,Handler handler,JSONObject identity,boolean sessionOwnsMedia,AudioGuard admission,
            AndroidAudioOccupancy.Source occupancySource)throws Exception {
        this.app=app;this.handler=handler;
        this.sessionOwnsMedia=sessionOwnsMedia;this.admission=admission;
        this.occupancySource=occupancySource==null?()->AndroidAudioOccupancyCheck.inspect(app):occupancySource;
        MediaReadiness.requireApplicationIdentity(app,PhotoAlarmContract.PACKAGE);
        files=app.getFilesDir().getCanonicalFile();
        if(!files.getPath().startsWith(new File(app.getApplicationInfo().dataDir).getCanonicalPath()+File.separator))
            throw new IOException("VISUAL_PRIVATE_DIRECTORY_INVALID");
        upload=new PhotoAlarmUpload(new URI("https://v.elfradio.net"),identity);
    }
    public JSONObject photo(PhotoAlarmOffer offer,Cancellation cancel)throws Exception {
        return photo(offer.id,offer.camera,cancel,null);
    }
    JSONObject photo(String reportId,String camera,Cancellation cancel,Captured listener)throws Exception {
        cancel.check();if(cancelled)throw new IOException("VISUAL_CANCELLED");
        if(!sessionOwnsMedia)photoLease=MediaFiles.lease(new File(files,"media"));
        try{
        MediaCapture capture=new MediaCapture(new File(files,"visual-photo"),new AndroidMediaDevice(app,AndroidMediaDevice.CLOCK),NO_AUDIO,AndroidMediaDevice.CLOCK);
        JSONObject receipt=capture.photo(CaptureRequest.photo(reportId,reportId,camera,System.currentTimeMillis()+20000),cancel);
        if(!"completed".equals(receipt.optString("state"))){
            cameraCleanupPending="MEDIA_CAMERA_RELEASE_PENDING".equals(receipt.optString("error"));
            throw new IOException(receipt.optString("error","VISUAL_CAPTURE_FAILED"));
        }
        if(photoLease!=null){photoLease.close();photoLease=null;}
        cancel.check();if(listener!=null)listener.captured(receipt);
        cancel.check();JSONObject result=upload.upload(receipt,cancel);
        String actual=receipt.getString("source").endsWith(":front")?"front":"back";
        return result.put("camera",actual).put("cameras",android.hardware.Camera.getNumberOfCameras());
        }finally{if(photoLease!=null&&AndroidMediaDevice.cameraReleased()){photoLease.close();photoLease=null;}}
    }
    public void alarm(PhotoAlarmOffer offer)throws Exception {
        alarm(offer.id);
    }
    void alarm(String operationId)throws Exception {
        if(cancelled)throw new IOException("VISUAL_CANCELLED");
        if(admission==null)throw new IOException("VISUAL_AUDIO_STATE_UNKNOWN");
        admission.requireIdle();
        AndroidAudioOccupancy monitor=new AndroidAudioOccupancy(
                ()->alarmObservation(occupancySource,toneOwned),
                ()->android.os.SystemClock.elapsedRealtime(),android.os.Looper.getMainLooper().getThread()).start();occupancy=monitor;
        monitor.awaitFirstSample(1500);monitor.requireIdle();
        if(cancelled)throw new IOException("VISUAL_CANCELLED");
        final AndroidAlarm platform=new AndroidAlarm(app,handler,()->{
            if(cancelled)throw new IOException("VISUAL_CANCELLED");
            admission.requireIdle();monitor.requireIdle();
        });
        AlarmTasks.Player player=duration->{
            if(cancelled)throw new IOException("VISUAL_CANCELLED");
            toneOwned=true;
            final AlarmTasks.Tone tone;
            try{tone=platform.start(duration);}catch(Exception failure){toneOwned=false;throw failure;}
            return new AlarmTasks.Tone(){
                public boolean isActive(){return tone.isActive();}
                public void close()throws Exception{try{tone.close();}finally{toneOwned=false;}}
            };
        };
        if(sessionOwnsMedia) {
            PreparedAlarm instance=new PreparedAlarm(player,platform,()->checkAlarm(),AndroidMediaDevice.CLOCK);
            synchronized(this){if(cancelled){instance.close();throw new IOException("VISUAL_CANCELLED");}preparedAlarm=instance;}
            instance.start();
            if(!toneOwned)throw new IOException("VISUAL_ALARM_NOT_STARTED_NO_REPLAY");
            return;
        }
        AlarmTasks instance=new AlarmTasks(new File(files,"media"),player,platform,()->checkAlarm(),AndroidMediaDevice.CLOCK,null);
        synchronized(this){if(cancelled){instance.close();throw new IOException("VISUAL_CANCELLED");}alarms=instance;}
        JSONObject result=instance.accept(new JSONObject().put("id",operationId).put("type","play_alarm").put("params",new JSONObject())
                .put("expires_at",System.currentTimeMillis()+15000));
        if(!"playing".equals(result.optString("state"))||!toneOwned)throw new IOException("VISUAL_ALARM_NOT_STARTED_NO_REPLAY");
    }
    String alarmState()throws Exception {
        PreparedAlarm prepared=preparedAlarm;if(prepared!=null)return prepared.state();
        AlarmTasks instance=alarms;if(instance==null)throw new IOException("VISUAL_ALARM_NOT_STARTED_NO_REPLAY");
        JSONObject state=instance.snapshot();
        if("ALARM_RELEASE_FAILED".equals(state.optString("error")))cancellationFailure=new IOException("VISUAL_CLEANUP_PENDING");
        if("failed".equals(state.optString("state")))throw new IOException("VISUAL_ALARM_FAILED");
        return state.optString("state");
    }
    public void checkAlarm()throws Exception {
        AndroidAudioOccupancy monitor=occupancy;if(monitor==null)throw new IOException("VISUAL_AUDIO_STATE_UNKNOWN");
        monitor.requireIdle();
    }
    static JSONObject alarmObservation(AndroidAudioOccupancy.Source source,boolean ownsTone)throws Exception {
        return alarmObservation(source.read(),ownsTone);
    }
    static JSONObject alarmObservation(JSONObject raw,boolean ownsTone)throws Exception {
        // API23只有流级活跃信息：仅在持有警报Tone时豁免本地STREAM_ALARM，其他字段保持原样。
        JSONObject value=new JSONObject(raw.toString());
        if(ownsTone){JSONObject audio=value.optJSONObject("audio");JSONArray streams=audio==null?null:audio.optJSONArray("streams");
            if(streams!=null)for(int i=0;i<streams.length();i++){JSONObject row=streams.optJSONObject(i);
                if(row!=null&&row.optInt("stream",-1)==AudioManager.STREAM_ALARM&&Boolean.TRUE.equals(row.opt("active")))row.put("active",false);}}
        return value;
    }
    public void cancel(){cancelled=true;upload.close();AlarmTasks instance=alarms;if(instance!=null)try{instance.close();}catch(Exception failure){cancellationFailure=failure;}
        PreparedAlarm prepared=preparedAlarm;if(prepared!=null)try{prepared.close();}catch(Exception failure){cancellationFailure=failure;}}
    public void close()throws Exception {
        cancelled=true;Exception failure=cancellationFailure;
        try{upload.close();}catch(Exception e){failure=e;}
        AlarmTasks instance=alarms;if(instance!=null)try{instance.close();}catch(Exception e){failure=e;}
        PreparedAlarm prepared=preparedAlarm;if(prepared!=null)try{prepared.close();}catch(Exception e){failure=e;}
        AndroidAudioOccupancy monitor=occupancy;if(monitor!=null)monitor.close();
        if(photoLease!=null&&AndroidMediaDevice.cameraReleased()){photoLease.close();photoLease=null;}
        if(cameraCleanupPending)throw new IOException("VISUAL_CAMERA_CLEANUP_PENDING");
        if(failure!=null)throw failure;
        if(cancellationFailure!=null)throw cancellationFailure;
    }
    /** prepare只拥有本次Tone和定时器，不创建AlarmTasks日志、媒体锁或第二root任务。 */
    static final class PreparedAlarm implements AutoCloseable {
        private final AlarmTasks.Player player;
        private final AlarmTasks.Scheduler scheduler;
        private final AudioGuard guard;
        private final MediaCapture.Clock clock;
        private AlarmTasks.Tone tone;
        private AlarmTasks.Ticket ticket;
        private String state="new";
        private boolean closed,releaseFailed;
        PreparedAlarm(AlarmTasks.Player player,AlarmTasks.Scheduler scheduler,AudioGuard guard,MediaCapture.Clock clock) {
            this.player=player;this.scheduler=scheduler;this.guard=guard;this.clock=clock;
        }
        synchronized void start()throws Exception {
            if(closed||!"new".equals(state))throw new IOException("VISUAL_ALARM_NOT_STARTED_NO_REPLAY");
            state="starting";
            try {
                guard.requireIdle();tone=player.start(0);
                if(tone==null)throw new IOException("ALARM_START_FAILED");
                state="playing";schedule();
            }catch(Exception failure){finish("failed");throw failure;}
        }
        private void schedule()throws Exception {
            ticket=scheduler.after(()->tick(),100);
            if(ticket==null)throw new IOException("ALARM_TIMER_UNAVAILABLE");
        }
        private synchronized void tick() {
            if(closed||!"playing".equals(state))return;
            try {
                if(!tone.isActive()){finish("completed");return;}
                guard.requireIdle();schedule();
            }catch(Exception failure){try{finish("failed");}catch(Exception ignored){}}
        }
        synchronized String state()throws Exception {
            if("failed".equals(state))throw new IOException("VISUAL_ALARM_FAILED");return state;
        }
        private void finish(String outcome)throws Exception {
            if(ticket!=null){try{ticket.cancel();}catch(Exception failure){releaseFailed=true;}finally{ticket=null;}}
            if(tone!=null){try{tone.close();}catch(Exception failure){releaseFailed=true;}finally{tone=null;}}
            state=releaseFailed?"failed":outcome;
            if(releaseFailed)throw new IOException("VISUAL_CLEANUP_PENDING");
        }
        public synchronized void close()throws Exception {
            if(!closed){closed=true;finish("completed".equals(state)?"completed":"interrupted");}
            if(releaseFailed)throw new IOException("VISUAL_CLEANUP_PENDING");
        }
    }
}
