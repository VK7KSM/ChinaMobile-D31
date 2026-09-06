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
    private static final int[] USB_RETRY_DELAYS_SECONDS = {60, 180};
    private static final long USB_BOOT_SETTLE_MILLIS = 180_000L;
    private static final String TAG = "D31WirelessAdb";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        context.startService(new Intent(context, ProbeService.class).setAction(action));
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
        if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
            int delaySeconds = earlyUsbDelaySeconds(SystemClock.elapsedRealtime());
            String uuid = UsbStorageControl.uuidFromIntent(intent);
            if (delaySeconds > 0 && UsbStorageControl.isMountedUuid(uuid)) {
                scheduleUsbRetry(context, intent.getData(), uuid, 0, delaySeconds);
                ProbeLog.append(context, "开机稳定期内检测到外置存储，延后"
                        + delaySeconds + "秒建立入口，卷=" + uuid);
                return;
            }
            runUsbAction(context, true, intent);
            return;
        }
        if (RETRY_USB_ACTION.equals(action)) {
            runUsbAction(context, true, intent);
            return;
        }
        if (REFRESH_USB_ACTION.equals(action)) {
            refreshUsbMappings(context);
            return;
        }
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
        if (Intent.ACTION_MEDIA_EJECT.equals(action)
                || Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                || Intent.ACTION_MEDIA_REMOVED.equals(action)
                || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
            runUsbAction(context, false, intent);
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

    private void runUsbAction(Context context, boolean mount, Intent intent) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        String action = intent.getAction();
        new Thread(() -> {
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
            pending.finish();
        }, "d31-usb-storage").start();
    }

    private void refreshUsbMappings(Context context) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            AdbControl.ActionResult vendorPrompt = UsbStorageControl.disableVendorUsbPrompt();
            AdbControl.ActionResult result = UsbStorageControl.refreshAllMappings();
            ProbeLog.append(appContext, "原厂U盘重复弹窗入口禁用="
                    + vendorPrompt.succeeded + "\n" + vendorPrompt.log
                    + "\nUSB存储全量刷新完成，成功="
                    + result.succeeded + "\n" + result.log);
            pending.finish();
        }, "d31-usb-storage-refresh").start();
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

    static int earlyUsbDelaySeconds(long elapsedRealtimeMillis) {
        if (elapsedRealtimeMillis >= USB_BOOT_SETTLE_MILLIS) return 0;
        long remaining = USB_BOOT_SETTLE_MILLIS - elapsedRealtimeMillis;
        return (int) ((remaining + 999L) / 1000L);
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
