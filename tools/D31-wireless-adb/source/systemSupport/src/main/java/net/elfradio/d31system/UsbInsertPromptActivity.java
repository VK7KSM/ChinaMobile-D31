package net.elfradio.d31system;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Toast;

public final class UsbInsertPromptActivity extends Activity {
    static final String EXTRA_VOLUME_UUID = "net.elfradio.d31system.extra.VOLUME_UUID";

    private AlertDialog dialog;
    private String uuid;
    private int requestGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        wakeAndDismissScreenSaver();
        preparePrompt(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        wakeAndDismissScreenSaver();
        preparePrompt(intent);
    }

    private void wakeAndDismissScreenSaver() {
        try {
            Intent wake = new Intent("com.starnet.phone.action.CALL_STATE")
                    .setPackage("com.starnet.screensaver");
            sendBroadcast(wake);
            SystemLog.append(this, "已调用原厂屏保退出入口");
        } catch (Throwable error) {
            SystemLog.append(this, "退出原厂屏保失败：" + error);
        }

        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) {
                PowerManager.WakeLock wakeLock = power.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "D31WirelessAdb:usb-insert");
                wakeLock.acquire(5000L);
            }
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (keyguard != null) {
                keyguard.newKeyguardLock("D31WirelessAdbUsb").disableKeyguard();
            }
        } catch (Throwable error) {
            SystemLog.append(this, "点亮U盘确认界面失败：" + error);
        }
    }

    private void preparePrompt(Intent intent) {
        final int generation = ++requestGeneration;
        new Thread(() -> {
            String mountedUuid = intent == null
                    ? null : intent.getStringExtra(EXTRA_VOLUME_UUID);
            if (!UsbStorageControl.isSafeUuid(mountedUuid)) {
                mountedUuid = UsbStorageControl.uuidFromIntent(intent);
            }
            SystemActions.ActionResult result = UsbStorageControl.ensureMapping(mountedUuid);
            SystemLog.append(this, "U盘确认对话框映射复核，卷=" + mountedUuid
                    + "，成功=" + result.succeeded
                    + "\n" + result.log);
            final String verifiedUuid = mountedUuid;
            runOnUiThread(() -> {
                if (isFinishing() || generation != requestGeneration) return;
                if (!result.succeeded) {
                    Toast.makeText(this, "存储设备尚未挂载，请重新插入后再试。",
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                uuid = verifiedUuid;
                if (dialog != null) dialog.dismiss();
                showPrompt();
            });
        }, "d31-usb-prompt").start();
    }

    private void showPrompt() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new AlertDialog.Builder(this)
                .setTitle("检测到存储设备")
                .setMessage("是否使用文件管理器打开？")
                .setPositiveButton("打开", (ignored, which) -> {
                    String selectedUuid = uuid;
                    new Thread(() -> {
                        SystemActions.ActionResult result = UsbStorageControl.ensureMapping(selectedUuid);
                        SystemLog.append(this, "打开前再次复核存储卷，卷=" + selectedUuid
                                + "，成功=" + result.succeeded + "\n" + result.log);
                        runOnUiThread(() -> {
                            if (result.succeeded) {
                                UsbFileOpener.open(this, selectedUuid);
                            } else {
                                Toast.makeText(this, "存储设备已断开。", Toast.LENGTH_LONG).show();
                            }
                            finish();
                        });
                    }, "d31-usb-open").start();
                })
                .setNegativeButton("取消", (ignored, which) -> finish())
                .setOnCancelListener(ignored -> finish())
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
