package dev.octoshrimpy.quik.feature.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SipBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SipService.ensureStarted(context);
    }
}
