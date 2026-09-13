package net.elfradio.d31bootstrap;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class ProbeService extends Service {
    @Override public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG || RemoteDeployment.systemManaged()) { stopSelf(); return; }
        startForeground(8765, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("elfRemote 基础探针")
                .setContentText("本地救援初始化中").build());
        new Thread(() -> {
            try { ProbeLog.append(this, RescueInstaller.ensure(this)); }
            catch (Exception error) { ProbeLog.append(this, "本地救援初始化失败：" + error); }
            finally { stopSelf(); }
        }, "d31-basic-rescue").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
