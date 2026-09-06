package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class UsbBrowseActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent sourceIntent = getIntent();
        new Thread(() -> {
            String uuid = UsbStorageControl.uuidFromIntent(sourceIntent);
            AdbControl.ActionResult result = UsbStorageControl.ensureMapping(uuid);
            ProbeLog.append(this, "系统USB浏览入口，卷=" + uuid
                    + "，成功=" + result.succeeded + "\n" + result.log);
            runOnUiThread(() -> finishOpen(uuid, result.succeeded));
        }, "d31-usb-browse").start();
    }

    private void finishOpen(String uuid, boolean ready) {
        if (!ready) {
            Toast.makeText(this, "U盘尚未挂载，请重新插入后再试。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        UsbFileOpener.open(this, uuid);
        finish();
    }
}
