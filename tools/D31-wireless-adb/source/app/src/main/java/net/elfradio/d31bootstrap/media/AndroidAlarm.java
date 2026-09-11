package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import java.io.IOException;

public final class AndroidAlarm implements AlarmTasks.Player,AlarmTasks.Scheduler {
    private final Context context;
    private final Handler handler;
    public AndroidAlarm(Context context,Handler ownerHandler){this.context=context;this.handler=ownerHandler;}
    public AlarmTasks.Tone start(int durationMs)throws Exception {
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(audio==null||audio.getStreamVolume(AudioManager.STREAM_ALARM)==0)throw new IOException("ALARM_MUTED");
        final ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_ALARM,100);
        try {if(!tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,durationMs))throw new IOException("ALARM_START_FAILED");}
        catch(Exception failure){tone.release();throw failure;}
        return new AlarmTasks.Tone(){public void close(){try{tone.stopTone();}finally{tone.release();}}};
    }
    public AlarmTasks.Ticket after(final Runnable callback,long delayMs)throws IOException {
        if(handler==null||!handler.postDelayed(callback,delayMs))throw new IOException("ALARM_TIMER_UNAVAILABLE");
        return new AlarmTasks.Ticket(){public void cancel(){handler.removeCallbacks(callback);}};
    }
}
