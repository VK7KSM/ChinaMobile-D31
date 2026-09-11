package net.elfradio.d31bootstrap;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 按需分别读取安装副本、系统原件和活动声明，不用版本登记代替实际原件。 */
public final class RemoteRuntimeInventory {
    public static final int MAPS_LIMIT = 2 * 1024 * 1024;

    /** 沿用迁移检查的六列maps及路径编码合同；只证明映射对应，摘要和进程身份须另验。 */
    public static boolean mapsArchive(String maps, String apkPath) {
        if (maps == null || apkPath == null || !apkPath.matches("/[A-Za-z0-9._/-]+\\.apk")) return false;
        for (String part : apkPath.substring(1).split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        String encoded = apkPath.substring(1).replace('/', '@') + "@classes.dex";
        String cache = "/data/dalvik-cache/";
        for (String line : maps.split("\n")) {
            String[] columns = line.trim().split("\\s+", 6);
            if (columns.length != 6) continue;
            String file = columns[5];
            if (file.equals(apkPath)) return true;
            if (!file.startsWith(cache)) continue;
            String relative = file.substring(cache.length());
            int slash = relative.indexOf('/');
            if (slash > 0 && relative.substring(0, slash).matches("[A-Za-z0-9_]+")
                    && relative.substring(slash + 1).equals(encoded)) return true;
        }
        return false;
    }

    interface Access {
        JSONObject installed() throws Exception;
        JSONObject systemArchive() throws Exception;
        JSONObject active() throws Exception;
        JSONObject installation() throws Exception;
        JSONObject health() throws Exception;
    }

    private interface Read { JSONObject run() throws Exception; }
    private static final String[] ARCHIVE = {"package", "certSha256", "versionCode", "versionName", "sha256", "size", "path"};
    private static final String[] HEALTH = {"version_code", "apk_sha256", "uid", "time_ms", "local_ready", "report_acknowledged"};

    private static JSONObject evidence(Read read, String source, String[] fields) throws Exception {
        try {
            JSONObject raw = read.run(), value = new JSONObject();
            for (String field : fields) {
                if (!raw.has(field) || raw.isNull(field)) throw new IOException("缺少状态字段");
                value.put(field, raw.get(field));
            }
            return new JSONObject().put("state", "OBSERVED").put("source", source).put("metadata", value);
        } catch (Exception unavailable) {
            return new JSONObject().put("state", "READ_FAILED").put("source", source)
                    .put("reason", unavailable.getClass().getSimpleName());
        }
    }

    static JSONObject collect(Access access, long now) throws Exception {
        if (now < 0) throw new IllegalArgumentException("采样时刻无效");
        JSONObject installed = evidence(access::installed, "package_manager_and_apk", ARCHIVE);
        JSONObject baseline = evidence(access::systemArchive, "system_archive", ARCHIVE);
        JSONObject active = evidence(access::active, "active_pointer_and_archive", ARCHIVE);
        JSONObject health = evidence(access::health, "core_health_file", HEALTH);
        JSONObject installation = evidence(access::installation, "package_manager", new String[]{
                "system", "updatedSystem", "privileged", "uid", "grantedPermissions"});
        JSONObject status = new JSONObject().put("installedVsActive", alignment(installed, active))
                .put("installedVsSystem", alignment(installed, baseline))
                .put("loadedCode", "NOT_CHECKED").put("coreHealth", "NOT_CONFIRMED");
        if (observed(health) && observed(active)) {
            JSONObject h = health.getJSONObject("metadata"), a = active.getJSONObject("metadata");
            long timestamp = h.optLong("time_ms", -1);
            if (timestamp >= 0 && timestamp <= now && now - timestamp < 20000
                    && h.optInt("version_code", -1) == a.getInt("versionCode")
                    && h.optString("apk_sha256").equals(a.getString("sha256"))
                    && h.optInt("uid", -1) == 0 && h.optBoolean("local_ready")) {
                status.put("coreHealth", "FRESH_SELF_REPORTED_MATCH");
            }
        }
        return new JSONObject().put("schemaVersion", 1).put("operation", "runtime_inventory")
                .put("capturedAtMs", now).put("installed", installed).put("systemArchive", baseline)
                .put("active", active).put("installation", installation).put("health", health)
                .put("assessment", status).put("systemConsistency", "NOT_ASSESSED");
    }

    private static boolean observed(JSONObject value) { return "OBSERVED".equals(value.optString("state")); }
    private static String alignment(JSONObject left, JSONObject right) throws Exception {
        if (!observed(left) || !observed(right)) return "UNKNOWN";
        JSONObject a = left.getJSONObject("metadata"), b = right.getJSONObject("metadata");
        return a.getString("sha256").equals(b.getString("sha256"))
                && a.getInt("versionCode") == b.getInt("versionCode")
                && a.getString("certSha256").equals(b.getString("certSha256")) ? "MATCH" : "DIFFERENT";
    }

    static final class AndroidAccess implements Access {
        private final RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
        public JSONObject installed() throws Exception { return platform.current(); }
        public JSONObject systemArchive() throws Exception { return archive(RemoteUpdatePlatform.BASELINE); }
        private JSONObject archive(File file) throws Exception {
            if (!file.isFile() || file.length() > 64L * 1024 * 1024) throw new IOException("制品不存在或过大");
            return platform.inspect(file);
        }
        public JSONObject active() throws Exception {
            JSONObject declared = new JSONObject(RescueFiles.read(RemoteUpdatePlatform.ACTIVE, 64000));
            String path = declared.getString("path");
            if (!path.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk")
                    && !path.equals(RemoteUpdatePlatform.BASELINE.getPath())) throw new IOException("活动路径无法确认");
            JSONObject actual = archive(new File(path));
            if (!RemoteUpdatePolicy.matches(declared, actual)) throw new IOException("活动原件与声明不符");
            return actual;
        }
        public JSONObject health() throws Exception {
            return new JSONObject(RescueFiles.read(new File(RemoteUpdatePlatform.CORE, "health.json"), 64000));
        }
        public JSONObject installation() throws Exception {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("currentActivityThread").invoke(null);
            if (thread == null) throw new IOException("系统查询上下文尚未就绪");
            android.content.Context context = (android.content.Context) type.getMethod("getSystemContext").invoke(thread);
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(RemoteUpdatePolicy.PACKAGE, 0);
            JSONArray granted = new JSONArray();
            for (String name : new String[]{"READ_LOGS", "DUMP", "WRITE_SECURE_SETTINGS", "INSTALL_PACKAGES", "DELETE_PACKAGES", "REBOOT"}) {
                if (manager.checkPermission("android.permission." + name, RemoteUpdatePolicy.PACKAGE) == PackageManager.PERMISSION_GRANTED)
                    granted.put(name);
            }
            // API23隐藏字段无法读取时保留失败，不以false冒充缺失证据。
            int privateFlags = info.getClass().getField("privateFlags").getInt(info);
            return new JSONObject().put("system", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    .put("updatedSystem", (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    .put("privileged", (privateFlags & 8) != 0).put("uid", info.uid).put("grantedPermissions", granted);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new SecurityException("仅限已确认D31只读采集");
        System.out.println(collect(new AndroidAccess(), System.currentTimeMillis()).toString());
        System.exit(0);
    }
    private RemoteRuntimeInventory() { }
}
