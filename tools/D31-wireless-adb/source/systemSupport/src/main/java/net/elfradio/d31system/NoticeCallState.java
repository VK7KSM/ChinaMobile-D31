package net.elfradio.d31system;

import android.content.Context;
import android.media.AudioManager;
import android.telephony.TelephonyManager;

final class NoticeCallState {
    static boolean allowed(Context context) {
        try {
            TelephonyManager phone=(TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
            AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            return phone!=null && audio!=null && phone.getCallState()==TelephonyManager.CALL_STATE_IDLE
                    && audio.getMode()==AudioManager.MODE_NORMAL;
        } catch(RuntimeException unavailable) { return false; }
    }
}
