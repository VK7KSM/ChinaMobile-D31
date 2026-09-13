package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioManager;
import android.telephony.TelephonyManager;
import java.io.IOException;

public final class AndroidCallGuard implements AudioGuard {
    private final Context context;
    private final AudioGuard sipAndOtherOwners;
    public AndroidCallGuard(Context context,AudioGuard sipAndOtherOwners){this.context=context;this.sipAndOtherOwners=sipAndOtherOwners;}
    public void requireIdle() throws Exception {
        if(context==null||sipAndOtherOwners==null)throw new IOException("MEDIA_AUDIO_STATE_UNKNOWN");
        sipAndOtherOwners.requireIdle();
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        TelephonyManager phone=(TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        if(audio==null||phone==null)throw new IOException("MEDIA_AUDIO_STATE_UNKNOWN");
        try {
            if(audio.getMode()!=AudioManager.MODE_NORMAL||phone.getCallState()!=TelephonyManager.CALL_STATE_IDLE)
                throw new IOException("MEDIA_PHONE_BUSY");
        }catch(SecurityException denied){throw new IOException("MEDIA_PHONE_STATE_UNREADABLE");}
    }
}
