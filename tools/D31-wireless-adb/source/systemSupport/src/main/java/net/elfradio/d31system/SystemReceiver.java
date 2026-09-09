package net.elfradio.d31system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SystemReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null) context.startService(new Intent(intent).setClass(context, SystemSupportService.class));
    }
}
