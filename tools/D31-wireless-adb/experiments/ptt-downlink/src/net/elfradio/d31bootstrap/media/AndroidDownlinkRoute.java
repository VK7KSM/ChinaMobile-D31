package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import java.io.IOException;
import org.json.JSONObject;

/** API23适配：PTT使用媒体焦点；任意失焦均请求停止，不降音量后继续播。 */
public final class AndroidDownlinkRoute {
    public static DownlinkRouteLease create(Context context,PttOutputGuard idle,Runnable lost){
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        SharedPreferences preferences=context.getSharedPreferences("ptt-route-recovery",Context.MODE_PRIVATE);
        AudioManager.OnAudioFocusChangeListener listener=change->{if(change<=0){idle.focusOwned(false);lost.run();}};
        return new DownlinkRouteLease(new DownlinkRouteLease.Port(){
            public int mode(){return audio.getMode();}
            public boolean speaker(){return audio.isSpeakerphoneOn();}
            public void speaker(boolean value){audio.setSpeakerphoneOn(value);}
            public boolean focus(){boolean granted=audio.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;idle.focusOwned(granted);return granted;}
            public void abandonFocus()throws IOException{
                idle.focusOwned(false);
                if(audio.abandonAudioFocus(listener)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw new IOException("MEDIA_FOCUS_RELEASE_UNCONFIRMED");
            }
        },new DownlinkRouteLease.Journal(){
            public JSONObject read()throws Exception{String value=preferences.getString("pending",null);return value==null?null:new JSONObject(value);}
            public void write(JSONObject value)throws IOException{
                if(!preferences.edit().putString("pending",value.toString()).commit())throw new IOException("MEDIA_ROUTE_SAVE_FAILED");
            }
            public void clear()throws IOException{
                if(!preferences.edit().remove("pending").commit())throw new IOException("MEDIA_ROUTE_CLEAR_FAILED");
            }
        },idle);
    }
    private AndroidDownlinkRoute(){}
}
