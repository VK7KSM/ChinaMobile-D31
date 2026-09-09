package net.elfradio.d31system;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SystemSupportService extends Service {
    private final ScheduledExecutorService storage = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private CellularDisplay cellular;

    @Override public void onCreate() {
        super.onCreate();
        startForeground(3390, new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sdcard)
                .setContentTitle("D31 系统支持").setContentText("存储与网络服务运行中")
                .setOngoing(true).build());
        SystemLog.append(this, "系统支持服务创建，运行毫秒=" + SystemClock.elapsedRealtime());
        storage.execute(() -> refresh(0));
        cellular = new CellularDisplay(this, maintenance);
        long elapsed = SystemClock.elapsedRealtime();
        maintenance.execute(() -> record(DeferredAppStartup.deferAtBoot()));
        if (elapsed < 240000) {
            maintenance.schedule(() -> record(DeferredAppStartup.resume(DeferredAppStartup.FIREFOX)),
                    Math.max(0, 180000 - elapsed), TimeUnit.MILLISECONDS);
            maintenance.schedule(() -> record(DeferredAppStartup.disableReceiver(DeferredAppStartup.FIREFOX)),
                    240000 - elapsed, TimeUnit.MILLISECONDS);
        }
        if (elapsed < 360000) {
            maintenance.schedule(() -> record(DeferredAppStartup.resume(DeferredAppStartup.THUNDERBIRD)),
                    Math.max(0, 300000 - elapsed), TimeUnit.MILLISECONDS);
            maintenance.schedule(() -> record(DeferredAppStartup.disableReceiver(DeferredAppStartup.THUNDERBIRD)),
                    360000 - elapsed, TimeUnit.MILLISECONDS);
        }
        maintenance.schedule(() -> record(FirewallControl.refreshIfEnabled(this)), 120, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (CellularDisplay.REQUEST.equals(action)
                || "android.intent.action.SIM_STATE_CHANGED".equals(action)) {
            cellular.ensureListening();
            cellular.refresh(CellularDisplay.REQUEST.equals(action));
        } else if ("android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {
            SipNetworkMonitor.onConnectivityBroadcast(this, intent);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            storage.execute(() -> refresh(0));
            SipNetworkMonitor.schedule(this, 3, 0);
        } else if (isMediaAction(action)) {
            Intent copy = new Intent(intent);
            storage.execute(() -> media(copy, 0));
        }
        return START_STICKY;
    }

    static boolean isMediaAction(String action) {
        return Intent.ACTION_MEDIA_MOUNTED.equals(action) || Intent.ACTION_MEDIA_EJECT.equals(action)
                || Intent.ACTION_MEDIA_UNMOUNTED.equals(action) || Intent.ACTION_MEDIA_REMOVED.equals(action)
                || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action);
    }

    private void refresh(int retry) {
        SystemActions.ActionResult result = UsbStorageControl.refreshReadyMappings();
        record(result);
        if (!result.succeeded && retry < 2)
            storage.schedule(() -> refresh(retry + 1), retry == 0 ? 3 : 10, TimeUnit.SECONDS);
    }

    private void media(Intent intent, int retry) {
        boolean mount = Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction());
        String uuid = UsbStorageControl.uuidFromIntent(intent);
        if (mount && !UsbStorageControl.isMountedUuid(uuid)) { refresh(0); return; }
        SystemActions.ActionResult result = mount ? UsbStorageControl.ensureMapping(uuid) : UsbStorageControl.removeMapping(uuid);
        record(result);
        if (mount && !result.succeeded && retry < 2 && UsbStorageControl.isMountedUuid(uuid)) {
            storage.schedule(() -> media(intent, retry + 1), retry == 0 ? 3 : 10, TimeUnit.SECONDS);
        } else if (mount && result.succeeded && UsbStorageControl.isMountedUuid(uuid)) {
            Intent prompt = new Intent(this, UsbInsertPromptActivity.class).setData(intent.getData())
                    .putExtra(UsbInsertPromptActivity.EXTRA_VOLUME_UUID, uuid)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { startActivity(prompt); } catch (Exception error) { SystemLog.append(this, "显示存储提示失败：" + error); }
        }
    }

    private void record(SystemActions.ActionResult result) {
        SystemLog.append(this, "运行毫秒=" + SystemClock.elapsedRealtime() + "，成功=" + result.succeeded + "\n" + result.log);
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (cellular != null) cellular.close();
        storage.shutdownNow(); maintenance.shutdownNow(); super.onDestroy();
    }
}
