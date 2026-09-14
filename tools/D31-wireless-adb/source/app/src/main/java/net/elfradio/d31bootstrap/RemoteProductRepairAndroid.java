package net.elfradio.d31bootstrap;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.pm.*;
import android.os.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;

/** 仅固定目录可达的系统Binder读写，不接受命令、路径或软件包作为独立远程参数。 */
final class RemoteProductRepairAndroid implements RemoteProductRepair.Platform {
    private final AndroidProductConfiguration configuration = new AndroidProductConfiguration();
    private PackageManager packages() throws Exception { return AndroidProductConfiguration.context().getPackageManager(); }
    @Override public String build() { return Build.FINGERPRINT; }

    @Override public JSONObject identity(RemoteProductRepair.Item item) throws Exception {
        PackageManager pm = packages();
        PackageInfo info = pm.getPackageInfo(item.pkg, PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES);
        ApplicationInfo app = info.applicationInfo;
        if (app == null || app.uid < 10000 || app.uid >= 100000 || app.targetSdkVersion < 23)
            throw new IOException("仅支持用户零的现代产品应用");
        String[] siblings = pm.getPackagesForUid(app.uid);
        if (siblings == null || siblings.length != 1 || !item.pkg.equals(siblings[0]))
            throw new IOException("共享UID可能影响其它应用，拒绝修复");
        if (info.signatures == null || info.signatures.length != 1) throw new IOException("产品签名不可确认");
        String signature = hash(info.signatures[0].toByteArray());
        JSONObject result = new JSONObject().put("uid", app.uid).put("version", info.versionCode)
                .put("updated", info.lastUpdateTime).put("signature_sha256", signature);
        if (item.kind.equals("appop")) {
            int code = code(item);
            int switched = (Integer) AppOpsManager.class.getMethod("opToSwitch", int.class).invoke(null, code);
            if (switched != code) throw new IOException("操作映射到其它配置项，禁止跨项修改");
        }
        if (item.kind.equals("permission")) {
            PermissionInfo permission = pm.getPermissionInfo(item.name, 0);
            if ((permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) != PermissionInfo.PROTECTION_DANGEROUS
                    || info.requestedPermissions == null || !java.util.Arrays.asList(info.requestedPermissions).contains(item.name))
                throw new IOException("不是产品声明的运行时权限");
            Object flags = packageCall("getPermissionFlags", new Class<?>[]{String.class, String.class, int.class},
                    item.name, item.pkg, 0);
            if (!(flags instanceof Integer) || (((Integer) flags) & (4 | 16)) != 0)
                throw new IOException("策略或系统固定权限禁止修改");
            result.put("permission_flags", flags);
        }
        if (item.kind.equals("component")) {
            JSONObject parent = configuration.packageState(item.pkg);
            if (!parent.getBoolean("enabled")) throw new IOException("所属应用未启用，不能单项修复组件");
            result.put("application_enabled_setting", parent.getInt("enabledSetting"));
        }
        return result;
    }
    @Override public Object read(RemoteProductRepair.Item item) throws Exception {
        if (item.kind.equals("permission")) return configuration.permission(item.pkg, item.name);
        if (item.kind.equals("component")) {
            JSONObject raw = configuration.componentState(item.pkg + "/" + item.name);
            if (!raw.getBoolean("installed")) throw new IOException("固定组件不存在");
            return new JSONObject().put("setting", raw.getInt("enabledSetting")).put("enabled", raw.getBoolean("enabled"));
        }
        return appOp(item);
    }
    private int code(RemoteProductRepair.Item item) throws Exception {
        return AppOpsManager.class.getField("OP_" + item.name).getInt(null);
    }
    private Object ops() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "appops");
        if (!(binder instanceof IBinder) || !((IBinder) binder).isBinderAlive()) throw new IOException("操作服务不可用");
        return Class.forName("com.android.internal.app.IAppOpsService$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    private int appOp(RemoteProductRepair.Item item) throws Exception {
        Object service = ops(); int code = code(item), uid = packages().getApplicationInfo(item.pkg, 0).uid;
        Class<?> type = Class.forName("com.android.internal.app.IAppOpsService");
        // 有UID级覆盖时，包级setMode不一定生效；不改变UID级配置。
        Object uidRows = type.getMethod("getUidOps", int.class, int[].class).invoke(service, uid, new int[]{code});
        if (uidRows != null && (!(uidRows instanceof List) || !((List<?>) uidRows).isEmpty()))
            throw new IOException("存在UID级操作覆盖或返回值无效");
        Object rows = type.getMethod("getOpsForPackage", int.class, String.class, int[].class)
                .invoke(service, uid, item.pkg, new int[]{code});
        // 未创建条目时使用平台定义的默认模式；不能把有效allow误当成原始default。
        int raw = (Integer) AppOpsManager.class.getMethod("opToDefaultMode", int.class).invoke(null, code);
        if (rows != null) {
            if (!(rows instanceof List)) throw new IOException("操作条目返回值无效");
            int found = 0;
            for (Object row : (List<?>) rows) {
                Class<?> rowType = Class.forName("android.app.AppOpsManager$PackageOps");
                if (!item.pkg.equals(rowType.getMethod("getPackageName").invoke(row))
                        || uid != (Integer) rowType.getMethod("getUid").invoke(row)) throw new IOException("操作条目身份不符");
                Object entries = rowType.getMethod("getOps").invoke(row);
                if (!(entries instanceof List)) throw new IOException("操作条目缺失");
                Class<?> entryType = Class.forName("android.app.AppOpsManager$OpEntry");
                for (Object entry : (List<?>) entries) {
                    if (code != (Integer) entryType.getMethod("getOp").invoke(entry) || ++found > 1)
                        throw new IOException("操作条目不唯一");
                    raw = (Integer) entryType.getMethod("getMode").invoke(entry);
                }
            }
        }
        int effective = (Integer) type.getMethod("checkOperation", int.class, int.class, String.class)
                .invoke(service, code, uid, item.pkg);
        if (raw != effective || raw < 0 || raw > 3) throw new IOException("原始操作模式与有效模式不一致");
        return raw;
    }
    @Override public void write(RemoteProductRepair.Item item, Object value) throws Exception {
        RemoteProductRepair.value(item, value, false);
        if (item.kind.equals("permission")) {
            packageCall((Boolean) value ? "grantRuntimePermission" : "revokeRuntimePermission",
                    new Class<?>[]{String.class, String.class, int.class}, item.pkg, item.name, 0);
        } else if (item.kind.equals("component")) {
            packageCall("setComponentEnabledSetting", new Class<?>[]{ComponentName.class, int.class, int.class, int.class},
                    new ComponentName(item.pkg, item.name), ((JSONObject) value).getInt("setting"), PackageManager.DONT_KILL_APP, 0);
        } else {
            Object service = ops();
            Class.forName("com.android.internal.app.IAppOpsService")
                    .getMethod("setMode", int.class, int.class, String.class, int.class)
                    .invoke(service, code(item), packages().getApplicationInfo(item.pkg, 0).uid, item.pkg, (Integer) value);
        }
    }
    private Object packageCall(String name, Class<?>[] types, Object... args) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
        if (!(binder instanceof IBinder) || !((IBinder) binder).isBinderAlive()) throw new IOException("软件包服务不可用");
        Object service = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method method = Class.forName("android.content.pm.IPackageManager").getMethod(name, types);
        return method.invoke(service, args);
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return result.toString();
    }
}
