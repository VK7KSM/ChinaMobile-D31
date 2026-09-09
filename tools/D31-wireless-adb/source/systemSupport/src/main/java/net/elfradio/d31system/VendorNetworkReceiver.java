package net.elfradio.d31system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class VendorNetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, SystemSupportService.class));
        SipNetworkMonitor.onVendorNetworkBroadcast(context, intent);
    }
}
