package net.elfradio.d31system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CellularDisplayReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && CellularDisplay.REQUEST.equals(intent.getAction())) {
            context.startService(new Intent(context, SystemSupportService.class).setAction(CellularDisplay.REQUEST));
        }
    }
}
