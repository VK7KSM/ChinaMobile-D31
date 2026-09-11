package net.elfradio.d31bootstrap.management;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Arrays;
import java.util.TimeZone;

/** 复用 system_config；调用者负责维护锁、任务持久化、去重及最终回执。 */
public final class SystemManagement {
    public interface Control {
        void check() throws Exception;
        /** 返回前须完成原值落盘；失败即不执行修改。原值可能含私有应用信息。 */
        void before(JSONObject record) throws Exception;
    }

    interface Access {
        JSONObject snapshot(JSONObject params) throws Exception;
        Object capture(JSONObject params) throws Exception;
        void apply(JSONObject params, Object value) throws Exception;
        void restore(JSONObject params, Object original) throws Exception;
        boolean matches(JSONObject params, JSONObject snapshot) throws Exception;
        boolean restored(JSONObject params, Object original) throws Exception;
    }

    public static final class Failure extends IOException {
        public final JSONObject result;
        Failure(String message, Exception cause, JSONObject result) {
            super(message, cause);
            this.result = result;
        }
    }

    private static final Control MEMORY_ONLY = new Control() {
        public void check() throws Exception {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("任务已取消");
        }
        public void before(JSONObject record) { }
    };

    public static boolean supports(String type) { return "system_config".equals(type); }

    /** 代码范围说明，不是设备写入验收结果；可随readiness结果一同保存。 */
    public static JSONObject capabilities() throws Exception {
        return new JSONObject().put("task", "system_config")
                .put("read_groups", new org.json.JSONArray(Arrays.asList("sound", "time", "apps", "network", "wifi")))
                .put("write_keys", new JSONObject()
                        .put("sound", new org.json.JSONArray(Arrays.asList("media", "ring", "alarm", "call", "brightness", "brightness_auto")))
                        .put("time", new org.json.JSONArray(Arrays.asList("auto_time", "auto_time_zone", "timezone")))
                        .put("apps", new org.json.JSONArray().put("enabled")))
                .put("components", "read_only").put("network_write", false).put("contacts", false)
                .put("write_verified", false).put("recovery_scope", "caught_failure_only");
    }

    /** 仅查询基础服务；ready表示当前root上下文通过读取门，不能证明写权限或业务效果。 */
    public static JSONObject readiness(Context context) throws Exception {
        JSONObject result = capabilities().put("ready", false).put("read_only", true);
        try {
            if (android.os.Build.VERSION.SDK_INT != 23 || android.os.Process.myUid() != 0)
                throw new IOException("需要API23独立root执行上下文");
            AndroidManagementAccess access = new AndroidManagementAccess(context);
            access.snapshot(validate("system_config", new JSONObject().put("group", "sound")));
            access.snapshot(validate("system_config", new JSONObject().put("group", "time")));
            context.getPackageManager().getApplicationInfo("net.elfradio.d31bootstrap", 0);
            result.put("ready", true);
        } catch (Exception error) {
            result.put("error_type", root(error).getClass().getSimpleName());
        }
        return result;
    }

    public static JSONObject validate(String type, JSONObject p) throws Exception {
        if (!supports(type)) throw new IOException("此模块不支持该任务类型");
        if (p == null || p.toString().length() > 6000) throw new IOException("系统配置参数无效或过大");
        String group = string(p, "group", null), action = string(p, "action", "read");
        String pkg = string(p, "package", "");
        if (!Arrays.asList("sound", "time", "apps", "network", "wifi").contains(group)
                || !Arrays.asList("read", "set").contains(action)) throw new IOException("系统配置分类或操作无效");
        if (!pkg.isEmpty() && !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IOException("应用包名无效");
        Object offset = p.has("offset") ? p.get("offset") : Integer.valueOf(0);
        int page = integer(offset, 0, 10000);
        JSONObject n = new JSONObject().put("group", group).put("action", action).put("package", pkg).put("offset", page);
        if ("read".equals(action)) return n;
        String key = string(p, "key", null);
        Object value = p.get("value");
        if ("network".equals(group) || "wifi".equals(group))
            throw new IOException("D31网络写入尚未具备独立失联恢复验收，未修改网络");
        boolean allowed = "sound".equals(group) && Arrays.asList("media", "ring", "alarm", "call", "brightness", "brightness_auto").contains(key)
                || "time".equals(group) && Arrays.asList("auto_time", "auto_time_zone", "timezone").contains(key)
                || "apps".equals(group) && "enabled".equals(key);
        if (!allowed) throw new IOException("此设置尚未适配D31，未执行修改");
        if (Arrays.asList("brightness_auto", "auto_time", "auto_time_zone", "enabled").contains(key)) {
            if (!(value instanceof Boolean)) throw new IOException("开关值必须为布尔值");
        } else if ("timezone".equals(key)) {
            if (!(value instanceof String) || !Arrays.asList(TimeZone.getAvailableIDs()).contains(value))
                throw new IOException("时区无效");
        } else integer(value, 0, 255);
        if ("apps".equals(group) && pkg.isEmpty()) throw new IOException("应用启用设置必须指定包名");
        return n.put("key", key).put("value", value);
    }

    public static JSONObject execute(Context context, String type, JSONObject params) throws Exception {
        return execute(context, type, params, MEMORY_ONLY);
    }

    /** 同一维护锁必须覆盖此方法；不得仅在任务入队时短暂持锁。 */
    public static JSONObject execute(Context context, String type, JSONObject params, Control control) throws Exception {
        return run(type, params, new AndroidManagementAccess(context), control);
    }

    static JSONObject run(String type, JSONObject input, Access access, Control control) throws Exception {
        JSONObject p = validate(type, input);
        if (control == null) throw new IOException("缺少执行控制");
        Object original = null;
        boolean changed = false;
        try {
            control.check();
            JSONObject before = bounded(access.snapshot(p));
            if ("read".equals(p.getString("action"))) {
                control.check();
                return bounded(before.put("ok", true));
            }
            original = access.capture(p);
            control.before(new JSONObject().put("params", p).put("original", original == null ? JSONObject.NULL : original)
                    .put("snapshot", before));
            control.check();
            // 服务可能先写入再抛异常；进入调用前即按可能已改变处理。
            changed = true;
            access.apply(p, p.get("value"));
            control.check();
            JSONObject after = access.snapshot(p);
            if (!access.matches(p, after)) throw new IOException("设备回读与请求不一致");
            JSONObject result = bounded(after.put("ok", true).put("applied", true));
            control.check();
            return result;
        } catch (Exception error) {
            boolean restored = false;
            Exception recovery = null;
            if (changed) {
                // 取消只阻止继续修改；已开始的修改仍须尝试恢复并回读。
                boolean interrupted = Thread.interrupted();
                try {
                    access.restore(p, original);
                    restored = access.restored(p, original);
                    if (!restored) throw new IOException("原值恢复回读不符");
                } catch (Exception failure) { recovery = failure; restored = false; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            JSONObject failure = new JSONObject().put("group", p.getString("group")).put("sampled_at", System.currentTimeMillis())
                    .put("ok", false).put("applied", false).put("change_attempted", changed)
                    .put("restored", restored).put("recovery_required", changed && !restored)
                    .put("error_type", root(error).getClass().getSimpleName());
            if (recovery != null) failure.put("recovery_error_type", root(recovery).getClass().getSimpleName());
            String message = !changed ? "系统配置未执行" : restored ? "系统配置失败，已恢复原值并回读" : "系统配置失败，原值恢复未确认";
            Failure result = new Failure(message, error, failure);
            if (recovery != null) result.addSuppressed(recovery);
            throw result;
        }
    }

    static JSONObject bounded(JSONObject value) throws Exception {
        if (value.toString().length() > 15000) throw new IOException("回读结果超出回执上限，请缩小范围");
        return value;
    }

    static Throwable root(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error;
    }

    private static String string(JSONObject p, String key, String fallback) throws Exception {
        if (!p.has(key) && fallback != null) return fallback;
        Object value = p.get(key);
        if (!(value instanceof String)) throw new IOException("参数必须为字符串：" + key);
        return (String) value;
    }

    private static int integer(Object value, int min, int max) throws IOException {
        if (!(value instanceof Number)) throw new IOException("参数必须为整数");
        double n = ((Number) value).doubleValue();
        if (Double.isNaN(n) || Double.isInfinite(n) || n != Math.floor(n) || n < min || n > max)
            throw new IOException("整数参数超出范围");
        return (int) n;
    }

    private SystemManagement() { }
}
