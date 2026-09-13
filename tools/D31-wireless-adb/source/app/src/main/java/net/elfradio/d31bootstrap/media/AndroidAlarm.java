package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioManager;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AndroidAlarm implements AlarmTasks.Player,AlarmTasks.Scheduler {
    private static final String DESCRIPTOR="net.elfradio.d31bootstrap.ALARM";
    private final Context context;
    private final Handler handler;
    private final AudioGuard beforeSound;
    public AndroidAlarm(Context context,Handler ownerHandler){this(context,ownerHandler,()->{});}
    public AndroidAlarm(Context context,Handler ownerHandler,AudioGuard beforeSound){this.context=context;this.handler=ownerHandler;this.beforeSound=beforeSound;}
    public AlarmTasks.Tone start(int durationMs)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("ALARM_MAIN_THREAD_FORBIDDEN");
        Session session=new Session(durationMs==0);
        try{session.start();return session;}catch(Exception failure){session.close();throw failure;}
    }
    private final class Session extends Binder implements AlarmTasks.Tone {
        private final AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        private final CountDownLatch visible=new CountDownLatch(1);
        private final boolean repeating;
        private AudioTrack track;
        private int originalVolume;
        private volatile boolean active=true;
        private volatile long lastUiPoll;
        private boolean volumeChanged,closed;
        private final Runnable sound=()->cycle(true),pause=()->cycle(false);
        private final Runnable screenWatch=this::watchScreen;
        Session(boolean repeating){this.repeating=repeating;}
        void start()throws Exception {
            if(audio==null||handler==null)throw new IOException("ALARM_AUDIO_UNAVAILABLE");
            originalVolume=audio.getStreamVolume(AudioManager.STREAM_ALARM);
            Bundle lease=new Bundle();lease.putBinder("owner",this);lease.putInt("original_volume",originalVolume);
            lease.putString("session",java.util.UUID.randomUUID().toString());
            context.startActivity(new Intent().setClassName(PhotoAlarmContract.PACKAGE,
                    PhotoAlarmContract.PACKAGE+".media.AlarmActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("alarm_lease",lease));
            // UI在独立进程保存原音量后确认；播放进程意外死亡时仍能还原。
            if(!visible.await(4000,TimeUnit.MILLISECONDS))throw new IOException("ALARM_SCREEN_UNAVAILABLE");
            synchronized(this){
                if(!active)throw new IOException("ALARM_CANCELLED");
                beforeSound.requireIdle();
                volumeChanged=true;
                int maximum=audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audio.setStreamVolume(AudioManager.STREAM_ALARM,maximum,0);
                if(audio.getStreamVolume(AudioManager.STREAM_ALARM)!=maximum)throw new IOException("ALARM_VOLUME_NOT_APPLIED");
                short[] samples=AlarmWaveform.samples();
                track=new AudioTrack(AudioManager.STREAM_ALARM,AlarmWaveform.RATE,AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,samples.length*2,AudioTrack.MODE_STATIC);
                if(track.getState()!=AudioTrack.STATE_NO_STATIC_DATA||track.write(samples,0,samples.length)!=samples.length)
                    throw new IOException("ALARM_AUDIO_INITIALIZATION_FAILED");
                if(track.setLoopPoints(0,samples.length,-1)!=AudioTrack.SUCCESS)throw new IOException("ALARM_LOOP_FAILED");
                if(android.os.Build.VERSION.SDK_INT>=23)for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){track.setPreferredDevice(device);break;}
                beforeSound.requireIdle();
                track.play();
                android.util.Log.i("D31Alarm","playing volume="+maximum+" elapsed_ms="+android.os.SystemClock.elapsedRealtime());
                if(!handler.postDelayed(screenWatch,250))throw new IOException("ALARM_TIMER_UNAVAILABLE");
                if(repeating&&!handler.postDelayed(pause,30000))throw new IOException("ALARM_TIMER_UNAVAILABLE");
            }
        }
        private synchronized void cycle(boolean playing){
            if(closed||!active||track==null)return;
            try{
                if(playing)track.play();else track.pause();
                android.util.Log.i("D31Alarm",(playing?"playing":"paused")+" elapsed_ms="+android.os.SystemClock.elapsedRealtime());
                if(!handler.postDelayed(playing?pause:sound,playing?30000:10000))throw new IOException("ALARM_TIMER_UNAVAILABLE");
            }catch(Exception failure){try{close();}catch(Exception ignored){}}
        }
        private synchronized void watchScreen(){
            if(closed||!active)return;
            try{
                if(android.os.SystemClock.elapsedRealtime()-lastUiPoll>2000)close();
                else if(!handler.postDelayed(screenWatch,250))close();
            }catch(Exception ignored){}
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code<1||code>3)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DESCRIPTOR);
            if(code==1)lastUiPoll=android.os.SystemClock.elapsedRealtime();
            if(code==2){
                int volume=data.readInt();
                if(volume<0||audio==null||volume>audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))throw new RemoteException("ALARM_ORIGINAL_VOLUME_INVALID");
                originalVolume=volume;visible.countDown();
            }
            if(code==3)try{close();}catch(Exception failure){throw new RemoteException("ALARM_RELEASE_FAILED");}
            reply.writeNoException();if(code==1)reply.writeInt(active?1:0);return true;
        }
        public boolean isActive(){return active;}
        public synchronized void close()throws Exception {
            if(closed)return;
            Exception failure=null;
            if(handler!=null){handler.removeCallbacks(sound);handler.removeCallbacks(pause);handler.removeCallbacks(screenWatch);}
            if(track!=null){try{track.stop();}catch(Exception ignored){}try{track.release();}catch(Exception e){failure=e;}track=null;}
            if(volumeChanged)try{
                audio.setStreamVolume(AudioManager.STREAM_ALARM,originalVolume,0);
                if(audio.getStreamVolume(AudioManager.STREAM_ALARM)!=originalVolume)throw new IOException("ALARM_VOLUME_RESTORE_FAILED");
                volumeChanged=false;
            }catch(Exception e){failure=e;}
            active=false;visible.countDown();closed=failure==null;
            android.util.Log.i("D31Alarm","stopped volume_restored="+(failure==null));
            if(failure!=null)throw new IOException("ALARM_RELEASE_FAILED",failure);
        }
    }
    public AlarmTasks.Ticket after(final Runnable callback,long delayMs)throws IOException {
        if(handler==null||!handler.postDelayed(callback,delayMs))throw new IOException("ALARM_TIMER_UNAVAILABLE");
        return new AlarmTasks.Ticket(){public void cancel(){handler.removeCallbacks(callback);}};
    }
}
