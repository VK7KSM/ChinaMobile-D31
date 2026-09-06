package net.elfradio.d31bootstrap;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

public final class ProbeService extends Service {
    private static final int NOTIFICATION_ID = 8765;
    private volatile boolean stopped;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("D31 系统探针")
                .setContentText("无线ADB与设备状态监控运行中")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        ProbeLog.append(this, "系统探针服务已创建");
        new Thread(() -> {
            ProbeLog.append(this, RescueInstaller.ensure(this));
            while (!stopped) {
                try {
                    RescueFiles.write(new java.io.File(RescueInstaller.directory(this), "status.txt"), buildReport());
                    Thread.sleep(30000);
                } catch (InterruptedException interrupted) { return; }
                catch (Throwable error) {
                    ProbeLog.append(this, "刷新探针缓存失败：" + error);
                    try { Thread.sleep(30000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "d31-probe-cache").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "系统重建服务" : String.valueOf(intent.getAction());
        ProbeLog.append(this, "系统探针收到启动请求：" + action);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ProbeLog.append(this, "系统探针服务被销毁");
        stopped = true;
        super.onDestroy();
    }

    private String buildReport() {
        return "D31 无线ADB与系统探针\n"
                + "缓存生成时间：" + new java.util.Date() + "\n"
                + "系统：Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n"
                + "型号：" + Build.MODEL + "\n"
                + "硬件：" + Build.HARDWARE + "\n"
                + "运行毫秒：" + SystemClock.elapsedRealtime() + "\n"
                + "地址：" + DeviceInfo.localAddresses() + "\n"
                + "状态报告：http://" + DeviceInfo.firstIpv4() + ":8765/\n\n"
                + AdbControl.status(this) + "\n"
                + FirewallControl.status(this) + "\n"
                + "== 持久日志 ==\n" + ProbeLog.read(this);
    }
}
