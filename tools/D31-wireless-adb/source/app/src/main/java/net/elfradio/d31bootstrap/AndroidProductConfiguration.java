package net.elfradio.d31bootstrap;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.IOException;
import org.json.JSONObject;

/** 产品配置的精确只读查询；不可读取的状态由调用方保留为未知。 */
final class AndroidProductConfiguration implements RemoteProductConfiguration.Access {
    // API23的名称；与后续MATCH_DISABLED_COMPONENTS的标志值相同。
    private static final int COMPONENT_FLAGS = PackageManager.GET_DISABLED_COMPONENTS;
    private static Context systemContext;

    private static synchronized Context context() throws Exception {
        if (systemContext == null) {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
                thread = type.getMethod("systemMain").invoke(null);
            }
            if (thread == null) throw new IOException("系统查询线程不可用");
            Context value = (Context) type.getMethod("getSystemContext").invoke(thread);
            if (value == null) throw new IOException("系统查询上下文不可用");
            systemContext = value;
        }
        return systemContext;
    }

    private static PackageManager packages() throws Exception {
        PackageManager manager = context().getPackageManager();
        if (manager == null) throw new IOException("软件包查询服务不可用");
        return manager;
    }

    private static boolean enabled(boolean declared, int setting) throws IOException {
        switch (setting) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT: return declared;
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: return true;
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED: return false;
            default: throw new IOException("无法识别启用状态");
        }
    }

    @Override public JSONObject packageState(String pkg) throws Exception {
        PackageManager manager = packages();
        try {
            PackageInfo info = manager.getPackageInfo(pkg, COMPONENT_FLAGS);
            ApplicationInfo application = manager.getApplicationInfo(pkg, COMPONENT_FLAGS);
            if (info == null || application == null) throw new IOException("软件包元数据不可用");
            int setting = manager.getApplicationEnabledSetting(pkg);
            // 隐藏字段读取失败必须传播，不能用false替代未知。
            int privateFlags = ApplicationInfo.class.getField("privateFlags").getInt(application);
            return new JSONObject().put("installed", true).put("versionCode", info.versionCode)
                    .put("system", (application.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("updatedSystem", (application.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    .put("privileged", (privateFlags & 8) != 0)
                    .put("enabled", enabled(application.enabled, setting)).put("enabledSetting", setting);
        } catch (PackageManager.NameNotFoundException missing) {
            return new JSONObject().put("installed", false);
        }
    }

    @Override public JSONObject componentState(String flattened) throws Exception {
        ComponentName name = ComponentName.unflattenFromString(flattened);
        if (name == null) throw new IllegalArgumentException("组件名称无效");
        PackageManager manager = packages();
        try {
            ComponentInfo component;
            try {
                component = manager.getReceiverInfo(name, COMPONENT_FLAGS);
            } catch (PackageManager.NameNotFoundException notReceiver) {
                try {
                    component = manager.getServiceInfo(name, COMPONENT_FLAGS);
                } catch (PackageManager.NameNotFoundException notService) {
                    component = manager.getActivityInfo(name, COMPONENT_FLAGS);
                }
            }
            if (component == null) throw new IOException("组件元数据不可用");
            ApplicationInfo application = manager.getApplicationInfo(name.getPackageName(), COMPONENT_FLAGS);
            if (application == null) throw new IOException("组件所属软件包元数据不可用");
            int applicationSetting = manager.getApplicationEnabledSetting(name.getPackageName());
            int componentSetting = manager.getComponentEnabledSetting(name);
            boolean effective = enabled(application.enabled, applicationSetting)
                    && enabled(component.enabled, componentSetting);
            return new JSONObject().put("installed", true).put("enabled", effective)
                    .put("enabledSetting", componentSetting).put("manifestEnabled", component.enabled);
        } catch (PackageManager.NameNotFoundException missing) {
            return new JSONObject().put("installed", false);
        }
    }

    @Override public boolean permission(String pkg, String fullPermission) throws Exception {
        PackageManager manager = packages();
        manager.getApplicationInfo(pkg, COMPONENT_FLAGS);
        return manager.checkPermission(fullPermission, pkg) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public String secureSetting(String key) throws Exception {
        // 独立ROOT进程没有应用归属，沿已有系统settings的外部Provider入口查询。
        return net.elfradio.d31bootstrap.management.SettingsCommand.readProductSecure(key);
    }

    @Override public boolean batteryExempt(String pkg) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 23) throw new IOException("当前系统不支持电池优化查询");
        packages().getApplicationInfo(pkg, COMPONENT_FLAGS);
        Object service = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "deviceidle");
        if (!(service instanceof IBinder) || !((IBinder) service).isBinderAlive())
            throw new IOException("电池优化查询服务不可用");
        Class<?> controller = Class.forName("android.os.IDeviceIdleController");
        Object reader = Class.forName("android.os.IDeviceIdleController$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, service);
        if (reader == null) throw new IOException("电池优化查询接口不可用");
        Object observed = controller.getMethod("isPowerSaveWhitelistApp", String.class).invoke(reader, pkg);
        if (!(observed instanceof Boolean)) throw new IOException("电池优化查询结果不可用");
        Object power = context().getSystemService(Context.POWER_SERVICE);
        if (!(power instanceof PowerManager)) throw new IOException("电源查询服务不可用");
        boolean exempt = ((PowerManager) power).isIgnoringBatteryOptimizations(pkg);
        if (!((IBinder) service).isBinderAlive() || exempt != (Boolean) observed)
            throw new IOException("电池优化查询状态无法一致确认");
        return exempt;
    }
}
