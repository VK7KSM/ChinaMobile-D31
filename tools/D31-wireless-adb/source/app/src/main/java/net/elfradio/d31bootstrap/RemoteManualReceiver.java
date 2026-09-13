package net.elfradio.d31bootstrap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 独立接收器不继承被系统迁移停用的旧广播组件状态。 */
public final class RemoteManualReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!RemoteDeployment.systemManaged() || intent==null)return;
        String action=intent.getAction();
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action))return;
        PendingResult pending=goAsync();
        RemoteManualBootstrap.request(context,pending::finish);
    }
}
