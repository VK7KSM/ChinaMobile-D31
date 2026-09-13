package net.elfradio.d31bootstrap.management;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Android 6真实系统服务适配；拒绝服务或读不到值时不制造默认成功。 */
@TargetApi(23)
final class AndroidManagementAccess implements SystemManagement.Access {
    private static final String[] VOLUMES = {"media", "ring", "alarm", "call"};
    private static final int[] STREAMS = {3, 2, 4, 0};
    private final Context context;

    AndroidManagementAccess(Context context) {
        if (context == null) throw new IllegalArgumentException("缺少系统上下文");
        this.context = context;
    }

    public JSONObject snapshot(JSONObject p) throws Exception {
        String group = p.getString("group");
        JSONObject out = new JSONObject().put("group", group).put("sampled_at", System.currentTimeMillis());
        if ("sound".equals(group)) {
            AudioManager audio = audio();
            JSONObject max = new JSONObject();
            for (int i = 0; i < VOLUMES.length; i++) {
                out.put(VOLUMES[i], audio.getStreamVolume(STREAMS[i]));
                max.put(VOLUMES[i], audio.getStreamMaxVolume(STREAMS[i]));
            }
            out.put("maximum", max).put("brightness", number(readSetting("sound", "brightness")))
                    .put("brightness_auto", flag(readSetting("sound", "brightness_auto")))
                    .put("font_scale", configuration().fontScale)
                    .put("unavailable", new JSONArray().put("font_scale_write"));
        } else if ("time".equals(group)) {
            // Configuration.locale及Locale.toLanguageTag在API23可用；不调用API24 getLocales。
            java.util.Locale locale = configuration().locale;
            out.put("locale", locale == null ? JSONObject.NULL : locale.toLanguageTag())
                    .put("timezone", timezone()).put("auto_time", flag(readSetting("time", "auto_time")))
                    .put("auto_time_zone", flag(readSetting("time", "auto_time_zone")))
                    .put("timezones", new JSONArray(java.util.Arrays.asList(java.util.TimeZone.getAvailableIDs())))
                    .put("unavailable", new JSONArray().put("locale_write"));
            JSONArray locales = new JSONArray();
            for (String name : context.getAssets().getLocales())
                if (!"en-XA".equals(name) && !"ar-XB".equals(name)) locales.put(name.replace('_', '-'));
            out.put("locales", locales);
        } else if ("apps".equals(group)) {
            apps(p, out);
        } else {
            NetworkStatus.read(context, p, out);
        }
        return out;
    }

    public Object capture(JSONObject p) throws Exception {
        String group = p.getString("group"), key = p.getString("key");
        int stream = stream(key);
        if ("sound".equals(group) && stream >= 0) {
            AudioManager audio = audio();
            if (p.getInt("value") > audio.getStreamMaxVolume(stream)) throw new IOException("音量超出设备实际范围");
            return audio.getStreamVolume(stream);
        }
        if ("apps".equals(group)) {
            String pkg = p.getString("package");
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (!p.getBoolean("value") && (pkg.equals("net.elfradio.d31bootstrap") || app.uid % 100000 < 10000))
                throw new IOException("不能经此入口停用管理自身或系统共享身份应用");
            return context.getPackageManager().getApplicationEnabledSetting(pkg);
        }
        if ("timezone".equals(key)) return timezone();
        String raw = readSetting(group, key);
        return raw == null ? JSONObject.NULL : raw;
    }

    public void apply(JSONObject p, Object value) throws Exception {
        String group = p.getString("group"), key = p.getString("key");
        if ("apps".equals(group)) {
            context.getPackageManager().setApplicationEnabledSetting(p.getString("package"), (Boolean) value
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
        } else if ("sound".equals(group) && stream(key) >= 0) {
            audio().setStreamVolume(stream(key), ((Number) value).intValue(), 0);
        } else if ("timezone".equals(key)) {
            alarm().setTimeZone((String) value);
        } else {
            putSetting(group, key, value instanceof Boolean ? (Boolean) value ? "1" : "0" : value.toString());
        }
    }

    public void restore(JSONObject p, Object original) throws Exception {
        String group = p.getString("group"), key = p.getString("key");
        if ("apps".equals(group)) {
            context.getPackageManager().setApplicationEnabledSetting(p.getString("package"), ((Number) original).intValue(), 0);
        } else if ("sound".equals(group) && stream(key) >= 0 || "timezone".equals(key)) {
            apply(p, original);
        } else {
            // 用null恢复“原本未设置”的读取语义，不写入猜测的默认数值。
            putSetting(group, key, original == JSONObject.NULL ? null : (String) original);
        }
    }

    public boolean matches(JSONObject p, JSONObject after) throws Exception {
        String key = p.getString("key");
        Object wanted = p.get("value"), got = after.opt(key);
        if ("apps".equals(p.getString("group"))) {
            int state = (Boolean) wanted ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            return after.getInt("enabled_state") == state && wanted.equals(got);
        }
        if (wanted instanceof Number && got instanceof Number)
            return ((Number) wanted).doubleValue() == ((Number) got).doubleValue();
        return wanted.equals(got);
    }

    public boolean restored(JSONObject p, Object original) throws Exception {
        String group = p.getString("group"), key = p.getString("key");
        Object actual;
        if ("apps".equals(group)) actual = context.getPackageManager().getApplicationEnabledSetting(p.getString("package"));
        else if ("sound".equals(group) && stream(key) >= 0) actual = audio().getStreamVolume(stream(key));
        else if ("timezone".equals(key)) actual = timezone();
        else { actual = readSetting(group, key); if (actual == null) actual = JSONObject.NULL; }
        return original.equals(actual);
    }

    private void apps(JSONObject p, JSONObject out) throws Exception {
        PackageManager pm = context.getPackageManager();
        String pkg = p.getString("package");
        int offset = p.getInt("offset");
        if (pkg.isEmpty()) {
            List<ApplicationInfo> list = pm.getInstalledApplications(PackageManager.GET_DISABLED_COMPONENTS);
            Collections.sort(list, new Comparator<ApplicationInfo>() {
                public int compare(ApplicationInfo a, ApplicationInfo b) { return a.packageName.compareTo(b.packageName); }
            });
            JSONArray rows = new JSONArray();
            for (int i = offset; i < Math.min(list.size(), offset + 15); i++) {
                ApplicationInfo app = list.get(i);
                rows.put(new JSONObject().put("package", app.packageName).put("name", app.loadLabel(pm).toString())
                        .put("enabled", app.enabled).put("enabled_state", pm.getApplicationEnabledSetting(app.packageName))
                        .put("system", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
            }
            out.put("apps", rows).put("offset", offset).put("total", list.size()).put("next", next(offset, list.size()));
            return;
        }
        PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_DISABLED_COMPONENTS);
        out.put("package", pkg).put("name", info.applicationInfo.loadLabel(pm).toString())
                .put("version", info.versionName == null ? JSONObject.NULL : info.versionName)
                .put("enabled", info.applicationInfo.enabled).put("enabled_state", pm.getApplicationEnabledSetting(pkg));
        List<ComponentInfo> components = new ArrayList<>();
        add(components, info.activities); add(components, info.services); add(components, info.receivers); add(components, info.providers);
        Collections.sort(components, new Comparator<ComponentInfo>() {
            public int compare(ComponentInfo a, ComponentInfo b) { return (a.name + a.getClass().getSimpleName()).compareTo(b.name + b.getClass().getSimpleName()); }
        });
        JSONArray rows = new JSONArray();
        for (int i = offset; i < Math.min(components.size(), offset + 15); i++) {
            ComponentInfo c = components.get(i);
            int state = pm.getComponentEnabledSetting(new ComponentName(pkg, c.name));
            boolean enabled = state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? c.enabled : state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            rows.put(new JSONObject().put("name", c.name).put("kind", c.getClass().getSimpleName())
                    .put("enabled_state", state).put("enabled", enabled && info.applicationInfo.enabled)
                    .put("exported", c.exported));
        }
        List<String> permissions = new ArrayList<>();
        if (info.requestedPermissions != null) for (String name : info.requestedPermissions) {
            try {
                PermissionInfo permission = pm.getPermissionInfo(name, 0);
                if ((permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS) permissions.add(name);
            } catch (PackageManager.NameNotFoundException missing) { /* 不把未知权限冒充已授予。 */ }
        }
        Collections.sort(permissions);
        JSONArray granted = new JSONArray();
        for (int i = offset; i < Math.min(permissions.size(), offset + 15); i++) {
            String name = permissions.get(i);
            granted.put(new JSONObject().put("name", name).put("label", name).put("granted", pm.checkPermission(name, pkg) == PackageManager.PERMISSION_GRANTED));
        }
        out.put("components", rows).put("components_total", components.size()).put("components_next", next(offset, components.size()))
                .put("offset", offset).put("permissions", granted).put("permissions_total", permissions.size()).put("permissions_next", next(offset, permissions.size()))
                .put("unavailable", new JSONArray().put("notifications").put("background").put("permission_write").put("component_write"));
    }

    private static void add(List<ComponentInfo> list, ComponentInfo[] values) { if (values != null) Collections.addAll(list, values); }
    static int next(int offset, int total) { return offset + 15 < total ? offset + 15 : -1; }
    private static int stream(String key) { for (int i = 0; i < VOLUMES.length; i++) if (VOLUMES[i].equals(key)) return STREAMS[i]; return -1; }
    private AudioManager audio() throws IOException {
        AudioManager service = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (service == null) throw new IOException("音频服务不可用"); return service;
    }
    private AlarmManager alarm() throws IOException {
        AlarmManager service = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (service == null) throw new IOException("时区服务不可用"); return service;
    }
    private static String settingKey(String key) {
        if ("brightness".equals(key)) return "screen_brightness";
        if ("brightness_auto".equals(key)) return "screen_brightness_mode";
        return key;
    }
    private String readSetting(String group, String key) throws Exception {
        String value = SettingsCommand.invoke("get", "sound".equals(group) ? "system" : "global", settingKey(key));
        if ("null".equals(value)) return null;
        // 本批存储键均为整数；禁止把旧命令exit0但输出异常当作读取成功。
        if (!value.matches("-?[0-9]{1,10}")) throw new IOException("系统设置返回值无效");
        return value;
    }
    private void putSetting(String group, String key, String value) throws Exception {
        String space = "sound".equals(group) ? "system" : "global";
        String output = value == null ? SettingsCommand.invoke("delete", space, settingKey(key))
                : SettingsCommand.invoke("put", space, settingKey(key), value);
        if (value == null ? !output.matches("Deleted [01] rows") : !output.isEmpty())
            throw new IOException("系统设置服务拒绝写入或返回异常");
    }
    private static Object number(String raw) throws IOException {
        if (raw == null) return JSONObject.NULL;
        try { return Integer.valueOf(raw); } catch (NumberFormatException error) { throw new IOException("系统设置数值格式异常", error); }
    }
    private static Object flag(String raw) { return "1".equals(raw) ? Boolean.TRUE : "0".equals(raw) ? Boolean.FALSE : JSONObject.NULL; }
    private static String timezone() throws Exception {
        String zone = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "persist.sys.timezone");
        if (zone == null || zone.isEmpty()) throw new IOException("系统时区属性未设置");
        return zone;
    }
    private static Configuration configuration() throws Exception {
        Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        Object value = Class.forName("android.app.IActivityManager").getMethod("getConfiguration").invoke(manager);
        if (!(value instanceof Configuration)) throw new IOException("系统配置回读类型不符");
        return (Configuration) value;
    }
}
