package net.elfradio.d31bootstrap;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;

final class UsbFileOpener {
    private static final ComponentName MATERIAL_FILES = new ComponentName(
            "me.zhanghai.android.files",
            "me.zhanghai.android.files.filelist.FileListActivity");

    private UsbFileOpener() {
    }

    static boolean open(Context context, String uuid) {
        if (!UsbStorageControl.isSafeUuid(uuid)) {
            Toast.makeText(context, "无法识别U盘。", Toast.LENGTH_LONG).show();
            return false;
        }
        String path = UsbStorageControl.publicPath(uuid);
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setComponent(MATERIAL_FILES)
                .setDataAndType(Uri.fromFile(new File(path)), "inode/directory")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            ProbeLog.append(context, "按卷启动文件管理器，卷=" + uuid
                    + "，路径=" + path + "，URI=" + view.getData());
            context.startActivity(view);
            return true;
        } catch (Throwable error) {
            ProbeLog.append(context, "启动文件管理器失败：" + error);
            Toast.makeText(context, "无法启动文件管理器。", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
