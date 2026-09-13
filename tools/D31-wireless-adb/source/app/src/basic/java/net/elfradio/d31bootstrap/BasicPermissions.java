package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.pm.PackageManager;

final class BasicPermissions {
    static final String[] REQUIRED = {
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_LOGS", "android.permission.DUMP"
    };

    static String initialize(Context context) {
        StringBuilder result = new StringBuilder();
        for (String permission : REQUIRED) {
            if (context.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                AdbControl.ActionResult grant = AdbControl.executeRoot("初始化本地维护权限",
                        "pm grant " + RescueFiles.quote(context.getPackageName()) + " "
                                + RescueFiles.quote(permission));
                result.append(grant.log).append('\n');
            }
            result.append(permission).append(": ").append(
                    context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                            ? "已授予" : "未授予，不能据此声明系统特权").append('\n');
        }
        return result.toString();
    }

    private BasicPermissions() { }
}
