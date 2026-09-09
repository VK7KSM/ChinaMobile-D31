package net.elfradio.d31bootstrap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String REPAIR_ACTION = "net.elfradio.d31bootstrap.REPAIR_ADB";
    private static final String REFRESH_USB_ACTION = "net.elfradio.d31bootstrap.REFRESH_USB";
    private static final String READY_USB_ACTION = "net.elfradio.d31bootstrap.READY_USB";
    private static final String REFRESH_FIREWALL_ACTION =
            "net.elfradio.d31bootstrap.REFRESH_FIREWALL";
    private static final String RETRY_USB_ACTION =
            "net.elfradio.d31bootstrap.RETRY_USB_MOUNT";
    private static final String START_THUNDERBIRD_BACKGROUND_ACTION =
            "net.elfradio.d31bootstrap.START_THUNDERBIRD_BACKGROUND";
    private static final String CLEANUP_THUNDERBIRD_RECEIVER_ACTION =
            "net.elfradio.d31bootstrap.CLEANUP_THUNDERBIRD_RECEIVER";
    private static final String EXTRA_USB_RETRY = "usb_retry";
    private static final int MAX_USB_RETRIES = 2;
    private static final int[] USB_RETRY_DELAYS_SECONDS = {3, 10};
    private static final String TAG = "D31WirelessAdb";
    private static final java.util.concurrent.ExecutorService STORAGE_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r ->
                    new Thread(r, "d31-storage-worker"));

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        context.startService(new Intent(intent).setClass(context, ProbeService.class));
        ProbeLog.append(context, "收到系统广播：" + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            scheduleAction(context, REPAIR_ACTION, 60, 201);
            scheduleAction(context, REFRESH_FIREWALL_ACTION, 120, 207);
            scheduleAction(context, REFRESH_USB_ACTION, 180, 202);
            scheduleAction(context, START_THUNDERBIRD_BACKGROUND_ACTION, 300, 205);
            scheduleAction(context, CLEANUP_THUNDERBIRD_RECEIVER_ACTION, 360, 206);
            SipNetworkMonitor.schedule(context, 20, 0);
            Log.i(TAG, "广播已接收，安排ADB、网络、存储和邮件串行错峰恢复：" + action);
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            scheduleAction(context, REPAIR_ACTION, 15, 201);
            scheduleAction(context, REFRESH_FIREWALL_ACTION, 60, 207);
            scheduleAction(context, REFRESH_USB_ACTION, 120, 202);
            scheduleAction(context, START_THUNDERBIRD_BACKGROUND_ACTION, 300, 205);
            scheduleAction(context, CLEANUP_THUNDERBIRD_RECEIVER_ACTION, 360, 206);
            SipNetworkMonitor.schedule(context, 10, 0);
            Log.i(TAG, "应用更新完成，优先安排SIP网络状态复核：" + action);
            return;
        }
        if (isStorageAction(action)) return;
        if (REFRESH_FIREWALL_ACTION.equals(action)) {
            refreshFirewall(context);
            return;
        }
        if (START_THUNDERBIRD_BACKGROUND_ACTION.equals(action)) {
            resumeDeferredApp(context, DeferredAppStartup.THUNDERBIRD);
            return;
        }
        if (CLEANUP_THUNDERBIRD_RECEIVER_ACTION.equals(action)) {
            disableDeferredReceiver(context, DeferredAppStartup.THUNDERBIRD);
            return;
        }
        if ("android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {
            SipNetworkMonitor.onConnectivityBroadcast(context, intent);
            Log.i(TAG, "网络广播已接收，仅安排SIP网络通知去重，不触发root操作");
            return;
        }
        if (!REPAIR_ACTION.equals(action)) {
            Log.i(TAG, "广播仅启动探针，不修改ADB：" + action);
            return;
        }

        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            AdbControl.ActionResult result = AdbControl.ensureEnabled(appContext);
            ProbeLog.append(appContext, "完整网络复核完成，ADB成功="
                    + result.succeeded + "\n" + result.log);
            Log.i(TAG, "ADB独立恢复完成，结果=" + result.succeeded);
            pending.finish();
        }, "d31-adb-auto-repair").start();
    }

    static boolean isStorageAction(String action) {
        return Intent.ACTION_MEDIA_MOUNTED.equals(action) || RETRY_USB_ACTION.equals(action)
                || REFRESH_USB_ACTION.equals(action) || READY_USB_ACTION.equals(action)
                || Intent.ACTION_MEDIA_EJECT.equals(action)
                || Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                || Intent.ACTION_MEDIA_REMOVED.equals(action)
                || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action);
    }

    static void onProbeServiceCreated(Context context) {
        dispatchStorageEvent(context, new Intent(READY_USB_ACTION));
    }

    static void dispatchStorageEvent(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!isStorageAction(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Context appContext = context.getApplicationContext();
        Intent request = new Intent(intent);
        STORAGE_WORKER.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                        || READY_USB_ACTION.equals(action)) {
                    refreshReadyUsbMappings(appContext, request);
                } else if (REFRESH_USB_ACTION.equals(action)) {
                    refreshUsbMappings(appContext);
                } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                        && !UsbStorageControl.isMountedUuid(UsbStorageControl.uuidFromIntent(request))) {
                    // Internal emulated storage becoming ready also exposes boot-mounted media.
                    refreshReadyUsbMappings(appContext, request);
                } else {
                    runUsbAction(appContext, Intent.ACTION_MEDIA_MOUNTED.equals(action)
                            || RETRY_USB_ACTION.equals(action), request);
                }
            } catch (Throwable error) {
                ProbeLog.append(appContext, "存储任务异常：" + error);
            } finally {
                ProbeLog.append(appContext, "存储任务完成，动作=" + action
                        + "，运行毫秒=" + SystemClock.elapsedRealtime()
                        + "，耗时=" + (SystemClock.elapsedRealtime() - started));
            }
        });
    }

    private static void runUsbAction(Context context, boolean mount, Intent intent) {
        Context appContext = context.getApplicationContext();
        String action = intent.getAction();
            String uuid = UsbStorageControl.uuidFromIntent(intent);
            AdbControl.ActionResult result = mount
                    ? UsbStorageControl.ensureMapping(uuid)
                    : UsbStorageControl.removeMapping(uuid);
            ProbeLog.append(appContext, "USB存储处理完成，动作=" + action
                    + "，卷=" + uuid + "，成功=" + result.succeeded + "\n" + result.log);
            int retry = intent.getIntExtra(EXTRA_USB_RETRY, 0);
            if (mount && !result.succeeded && retry < MAX_USB_RETRIES
                    && UsbStorageControl.isMountedUuid(uuid)) {
                int nextRetry = retry + 1;
                int delaySeconds = usbRetryDelaySeconds(nextRetry);
                scheduleUsbRetry(appContext, intent.getData(), uuid, nextRetry, delaySeconds);
                ProbeLog.append(appContext, "USB存储映射未通过，已安排第"
                        + nextRetry + "次重试，延迟=" + delaySeconds + "秒");
            }
            if (shouldPromptForUsbAction(action, result.succeeded)) {
                Intent prompt = new Intent(appContext, UsbInsertPromptActivity.class)
                        .setData(intent.getData())
                        .putExtra(UsbInsertPromptActivity.EXTRA_VOLUME_UUID, uuid)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try {
                    appContext.startActivity(prompt);
                    ProbeLog.append(appContext, "已显示U盘打开确认对话框");
                } catch (Throwable error) {
                    ProbeLog.append(appContext, "显示U盘确认对话框失败：" + error);
                }
            }
    }

    private static void refreshReadyUsbMappings(Context context, Intent intent) {
        Context appContext = context.getApplicationContext();
                AdbControl.ActionResult result = UsbStorageControl.refreshReadyMappings();
                ProbeLog.append(appContext, "开机优先建立外置存储入口，成功="
                        + result.succeeded + "\n" + result.log);
                int retry = intent.getIntExtra(EXTRA_USB_RETRY, 0);
                if (!result.succeeded && retry < MAX_USB_RETRIES) {
                    scheduleReadyUsbRetry(appContext, retry + 1);
                }
    }

    private static void refreshUsbMappings(Context context) {
        Context appContext = context.getApplicationContext();
            AdbControl.ActionResult vendorPrompt = UsbStorageControl.disableVendorUsbPrompt();
            AdbControl.ActionResult result = UsbStorageControl.refreshAllMappings();
            ProbeLog.append(appContext, "原厂U盘重复弹窗入口禁用="
                    + vendorPrompt.succeeded + "\n" + vendorPrompt.log
                    + "\nUSB存储全量刷新完成，成功="
                    + result.succeeded + "\n" + result.log);
    }

    private static void scheduleReadyUsbRetry(Context context, int retry) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent request = new Intent(context, BootReceiver.class).setAction(READY_USB_ACTION)
                .putExtra(EXTRA_USB_RETRY, retry);
        PendingIntent operation = PendingIntent.getBroadcast(context, 208, request,
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + usbRetryDelaySeconds(retry) * 1000L, operation);
    }

    private void refreshFirewall(Context context) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            AdbControl.ActionResult result = FirewallControl.refreshIfEnabled(appContext);
            ProbeLog.append(appContext, "错峰刷新本机防火墙，成功="
                    + result.succeeded + "\n" + result.log);
            pending.finish();
        }, "d31-firewall-refresh").start();
    }

    private void resumeDeferredApp(Context context, DeferredAppStartup.App app) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            AdbControl.ActionResult result = DeferredAppStartup.resume(app);
            ProbeLog.append(appContext, app.displayName + "后台任务恢复，成功="
                    + result.succeeded + "\n" + result.log);
            pending.finish();
        }, "d31-resume-" + app.packageName).start();
    }

    private void disableDeferredReceiver(Context context, DeferredAppStartup.App app) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            AdbControl.ActionResult result = DeferredAppStartup.disableReceiver(app);
            ProbeLog.append(appContext, app.displayName + "开机接收器收尾，成功="
                    + result.succeeded + "\n" + result.log);
            pending.finish();
        }, "d31-cleanup-" + app.packageName).start();
    }

    static boolean shouldPromptForUsbAction(String action, boolean mappingSucceeded) {
        return mappingSucceeded && (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                || RETRY_USB_ACTION.equals(action));
    }

    static int usbRetryDelaySeconds(int retry) {
        if (retry < 1 || retry > USB_RETRY_DELAYS_SECONDS.length) {
            throw new IllegalArgumentException("无效的USB重试次数");
        }
        return USB_RETRY_DELAYS_SECONDS[retry - 1];
    }

    private static void scheduleUsbRetry(Context context, android.net.Uri data,
            String uuid, int retry, int delaySeconds) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null || !UsbStorageControl.isSafeUuid(uuid)) return;
        Intent retryIntent = new Intent(context, BootReceiver.class)
                .setAction(RETRY_USB_ACTION)
                .setData(data)
                .putExtra(EXTRA_USB_RETRY, retry);
        int requestCode = 3000 + (uuid.hashCode() & 0x3fff);
        PendingIntent operation = PendingIntent.getBroadcast(
                context, requestCode, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delaySeconds * 1000L, operation);
    }

    private static void scheduleAction(
            Context context, String action, int delaySeconds, int requestCode) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent repair = new Intent(context, BootReceiver.class).setAction(action);
        PendingIntent operation = PendingIntent.getBroadcast(
                context, requestCode, repair, PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delaySeconds * 1000L,
                operation);
    }
}
