package net.elfradio.d31bootstrap;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.view.accessibility.AccessibilityManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import net.elfradio.d31bootstrap.management.SettingsCommand;

/** 只查询指定包的操作模式及Guard实际绑定，不返回其它辅助服务。 */
final class AndroidProductAccess implements RemoteProductAccess.Access {
    private static final String GUARD_PACKAGE = "net.elfradio.d31zelloguard";
    private static final String GUARD_SERVICE = GUARD_PACKAGE + ".GuardAccessibilityService";

    @Override public int appOp(String pkg, String op) throws Exception {
        if (Build.VERSION.SDK_INT < 23) throw new IOException("操作查询要求Android 6或以上版本");
        String operation;
        switch (op) {
            case "WRITE_SMS": operation = null; break;
            case "SEND_SMS": operation = AppOpsManager.OPSTR_SEND_SMS; break;
            case "READ_SMS": operation = AppOpsManager.OPSTR_READ_SMS; break;
            case "CAMERA": operation = AppOpsManager.OPSTR_CAMERA; break;
            case "RECORD_AUDIO": operation = AppOpsManager.OPSTR_RECORD_AUDIO; break;
            case "FINE_LOCATION": operation = AppOpsManager.OPSTR_FINE_LOCATION; break;
            case "COARSE_LOCATION": operation = AppOpsManager.OPSTR_COARSE_LOCATION; break;
            default: throw new IllegalArgumentException("不允许查询该操作");
        }
        Context context = AndroidProductConfiguration.context();
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(pkg, 0);
        if (application == null || application.uid < 0) throw new IOException("目标软件包UID不可读");
        Object value = context.getSystemService(Context.APP_OPS_SERVICE);
        if (!(value instanceof AppOpsManager)) throw new IOException("操作查询服务不可用");
        AppOpsManager manager = (AppOpsManager) value;
        Object remote = service(manager, AppOpsManager.class);
        int code, mode;
        if (operation == null) {
            // Android 6没有OPSTR_WRITE_SMS，且字符串映射的该位置为null。
            code = AppOpsManager.class.getField("OP_WRITE_SMS").getInt(null);
            mode = (Integer) AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class)
                    .invoke(manager, code, application.uid, pkg);
        } else {
            code = (Integer) AppOpsManager.class.getMethod("strOpToOp", String.class).invoke(null, operation);
            mode = manager.checkOpNoThrow(operation, application.uid, pkg);
        }
        // Android 6客户端会吞RemoteException并返回MODE_ERRORED；用同一只读IPC确认。
        Object confirmed = Class.forName("com.android.internal.app.IAppOpsService")
                .getMethod("checkOperation", int.class, int.class, String.class)
                .invoke(remote, code, application.uid, pkg);
        alive(remote);
        if (!(confirmed instanceof Integer) || mode != (Integer) confirmed)
            throw new IOException("操作模式读取结果无法一致确认");
        return mode;
    }

    @Override public String secureSetting(String key) throws Exception {
        if (!"accessibility_enabled".equals(key) && !"enabled_accessibility_services".equals(key))
            throw new IllegalArgumentException("不允许读取该辅助设置");
        return SettingsCommand.readProductSecure(key);
    }

    @Override public boolean accessibilityBound(String component) throws Exception {
        if (Build.VERSION.SDK_INT != 23) throw new IOException("仅确认Android 6辅助绑定查询语义");
        ComponentName target = ComponentName.unflattenFromString(component);
        if (target == null || !GUARD_PACKAGE.equals(target.getPackageName())
                || !GUARD_SERVICE.equals(target.getClassName()))
            throw new IllegalArgumentException("仅允许查询Guard辅助服务绑定");
        Object value = AndroidProductConfiguration.context().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (!(value instanceof AccessibilityManager)) throw new IOException("辅助服务查询不可用");
        AccessibilityManager manager = (AccessibilityManager) value;
        Object remote = service(manager, AccessibilityManager.class);
        // D31服务端从mBoundServices按反馈类型筛选；直接IPC避免客户端吞异常。
        // 自动化服务存在时也返回空列表，false只表示未在当前反馈绑定列表中出现。
        Object confirmed = Class.forName("android.view.accessibility.IAccessibilityManager")
                .getMethod("getEnabledAccessibilityServiceList", int.class, int.class)
                .invoke(remote, AccessibilityServiceInfo.FEEDBACK_ALL_MASK, 0);
        alive(remote);
        if (!(confirmed instanceof List)) throw new IOException("辅助服务查询未返回有效列表");
        return contains((List<?>) confirmed, target);
    }

    private static boolean contains(List<?> services, ComponentName target) throws IOException {
        if (services == null) throw new IOException("辅助服务查询未返回列表");
        boolean bound = false;
        for (Object value : services) {
            if (!(value instanceof AccessibilityServiceInfo)) throw new IOException("辅助服务查询条目无效");
            String id = ((AccessibilityServiceInfo) value).getId();
            ComponentName name = id == null ? null : ComponentName.unflattenFromString(id);
            if (name == null) throw new IOException("辅助服务查询标识无效");
            if (target.equals(name)) bound = true;
        }
        return bound;
    }

    private static Object service(Object manager, Class<?> owner) throws Exception {
        Field field = owner.getDeclaredField("mService");
        field.setAccessible(true);
        Object remote = field.get(manager);
        alive(remote);
        return remote;
    }

    private static void alive(Object remote) throws IOException {
        if (!(remote instanceof IInterface)) throw new IOException("系统只读查询接口不可用");
        IBinder binder = ((IInterface) remote).asBinder();
        if (binder == null || !binder.isBinderAlive() || !binder.pingBinder())
            throw new IOException("系统只读查询Binder不可用");
    }
}
