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
    private volatile AlarmTasks alarms;
    private volatile AndroidAudioOccupancy occupancy;
    private volatile boolean cancelled,toneOwned,cameraCleanupPending;
    private volatile Exception cancellationFailure;
    PhotoAlarmBackend(Context app,Handler handler,JSONObject identity)throws Exception {
        this.app=app;this.handler=handler;
        MediaReadiness.requireApplicationIdentity(app,PhotoAlarmContract.PACKAGE);
        files=app.getFilesDir().getCanonicalFile();
        if(!files.getPath().startsWith(new File(app.getApplicationInfo().dataDir).getCanonicalPath()+File.separator))
            throw new IOException("VISUAL_PRIVATE_DIRECTORY_INVALID");
        upload=new PhotoAlarmUpload(new URI("https://v.elfradio.net"),identity);
    }
    public JSONObject photo(PhotoAlarmOffer offer,Cancellation cancel)throws Exception {
        cancel.check();if(cancelled)throw new IOException("VISUAL_CANCELLED");
        MediaCapture capture=new MediaCapture(new File(files,"visual-photo"),new AndroidMediaDevice(app,AndroidMediaDevice.CLOCK),NO_AUDIO,AndroidMediaDevice.CLOCK);
        JSONObject receipt=capture.photo(CaptureRequest.photo(offer.id,offer.id,offer.camera,System.currentTimeMillis()+20000),cancel);
        if(!"completed".equals(receipt.optString("state"))){
            cameraCleanupPending="MEDIA_CAMERA_RELEASE_PENDING".equals(receipt.optString("error"));
            throw new IOException(receipt.optString("error","VISUAL_CAPTURE_FAILED"));
        }
        cancel.check();JSONObject result=upload.upload(receipt,cancel);
        String actual=receipt.getString("source").endsWith(":front")?"front":"back";
        return result.put("camera",actual).put("cameras",android.hardware.Camera.getNumberOfCameras());
    }
    public void alarm(PhotoAlarmOffer offer)throws Exception {
        if(cancelled)throw new IOException("VISUAL_CANCELLED");
        AndroidAudioOccupancy monitor=new AndroidAudioOccupancy(
                ()->alarmObservation(AndroidAudioOccupancyCheck.inspect(app),toneOwned),
                ()->android.os.SystemClock.elapsedRealtime(),android.os.Looper.getMainLooper().getThread()).start();occupancy=monitor;
        monitor.awaitFirstSample(1500);monitor.requireIdle();
        if(cancelled)throw new IOException("VISUAL_CANCELLED");
        final AndroidAlarm platform=new AndroidAlarm(app,handler);
        AlarmTasks.Player player=duration->{
            if(cancelled)throw new IOException("VISUAL_CANCELLED");
            final AlarmTasks.Tone tone=platform.start(duration);toneOwned=true;
            return ()->{try{tone.close();}finally{toneOwned=false;}};
        };
        AlarmTasks instance=new AlarmTasks(new File(files,"media"),player,platform,()->checkAlarm(),AndroidMediaDevice.CLOCK,null);
        synchronized(this){if(cancelled){instance.close();throw new IOException("VISUAL_CANCELLED");}alarms=instance;}
        JSONObject result=instance.accept(new JSONObject().put("id",offer.id).put("type","play_alarm").put("params",new JSONObject())
                .put("expires_at",System.currentTimeMillis()+15000));
        if(!"playing".equals(result.optString("state"))||!toneOwned)throw new IOException("VISUAL_ALARM_NOT_STARTED_NO_REPLAY");
    }
    public void checkAlarm()throws Exception {
        AndroidAudioOccupancy monitor=occupancy;if(monitor==null)throw new IOException("VISUAL_AUDIO_STATE_UNKNOWN");
        monitor.requireIdle();
    }
    static JSONObject alarmObservation(JSONObject raw,boolean ownsTone)throws Exception {
        // API23只有流级活跃信息：仅在持有警报Tone时豁免本地STREAM_ALARM，其他字段保持原样。
        JSONObject value=new JSONObject(raw.toString());
        if(ownsTone){JSONObject audio=value.optJSONObject("audio");JSONArray streams=audio==null?null:audio.optJSONArray("streams");
            if(streams!=null)for(int i=0;i<streams.length();i++){JSONObject row=streams.optJSONObject(i);
                if(row!=null&&row.optInt("stream",-1)==AudioManager.STREAM_ALARM&&Boolean.TRUE.equals(row.opt("active")))row.put("active",false);}}
        return value;
    }
    public void cancel(){cancelled=true;upload.close();AlarmTasks instance=alarms;if(instance!=null)try{instance.close();}catch(Exception failure){cancellationFailure=failure;} }
    public void close()throws Exception {
        cancelled=true;Exception failure=cancellationFailure;
        try{upload.close();}catch(Exception e){failure=e;}
        AlarmTasks instance=alarms;if(instance!=null)try{instance.close();}catch(Exception e){failure=e;}
        AndroidAudioOccupancy monitor=occupancy;if(monitor!=null)monitor.close();
        if(cameraCleanupPending)throw new IOException("VISUAL_CAMERA_CLEANUP_PENDING");
        if(failure!=null)throw failure;
    }
}
