package net.elfradio.d31bootstrap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class VendorNetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SipNetworkMonitor.onVendorNetworkBroadcast(context, intent);
    }
}
