package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.*;
import org.json.JSONObject;

/** 主core持有一次，任务受理与播放生命周期分开，不需要普通exec保活。 */
public final class AlarmCoreOwner implements AutoCloseable {
    public interface Reply { void completed(JSONObject result); void failed(String code); }
    private final HandlerThread thread;
    private final Handler handler;
    private final AlarmTasks alarm;
    private boolean closing;
    public AlarmCoreOwner(Context context,File privateMediaRoot,AudioGuard combinedGuard,AlarmTasks.Changed changed)throws Exception {
        thread=new HandlerThread("d31-alarm-owner");thread.start();handler=new Handler(thread.getLooper());
        try{
            AndroidAlarm platform=new AndroidAlarm(context,handler);
            alarm=new AlarmTasks(privateMediaRoot,platform,platform,combinedGuard,AndroidMediaDevice.CLOCK,changed);
        }catch(Exception failure){thread.quitSafely();throw failure;}
    }
    public synchronized void submit(JSONObject task,final Reply reply)throws Exception {
        if(closing||reply==null)throw new IOException("ALARM_OWNER_UNAVAILABLE");
        final JSONObject copied=new JSONObject(task.toString());
        if(!handler.post(new Runnable(){public void run(){
            JSONObject result;
            try{
                JSONObject accepted=alarm.accept(copied);
                String state=accepted.optString("state");
                if("failed".equals(state)||"interrupted".equals(state)||"cancelled".equals(state))throw new IOException("ALARM_NOT_ACCEPTED");
                result=new JSONObject().put("alarm",alarm.reportSnapshot());
            }catch(Exception failure){try{reply.failed("ALARM_NOT_ACCEPTED");}catch(Exception ignored){}return;}
            try{reply.completed(result);}catch(Exception ignored){}
        }}))throw new IOException("ALARM_OWNER_UNAVAILABLE");
    }
    public JSONObject reportSnapshot()throws Exception{return alarm.reportSnapshot();}
    public synchronized void close()throws Exception {
        if(closing)return;closing=true;
        if(!handler.post(new Runnable(){public void run(){try{alarm.close();}catch(Exception ignored){}finally{thread.quitSafely();}}}))
            throw new IOException("ALARM_OWNER_RELEASE_UNCONFIRMED");
    }
}
