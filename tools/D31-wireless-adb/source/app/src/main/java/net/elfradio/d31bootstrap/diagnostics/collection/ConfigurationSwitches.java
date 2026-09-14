package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONException;
import org.json.JSONObject;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionSupport.*;

/** 冻结消费者的启动开关条件；不表示进程、恢复入口或服务已运行。 */
final class ConfigurationSwitches {
    static final String SUPPORT = SystemSupportConfiguration.PATH;
    static final String RECOVERY = "/data/local/d31-recovery-entry/persistent.sh";
    static final String RESCUE = "/data/local/d31-rescue/start.sh";
    static final String STARTUP = "/data/local/d31-startup-handover/start.sh";
    static final int MAX_BYTES = 4096;

    static String field(String path) {
        return SUPPORT.equals(path) ? "semantic.system_support.disabled"
                : RECOVERY.equals(path) ? "semantic.recovery.enabled"
                : RESCUE.equals(path) ? "semantic.rescue.enabled"
                : STARTUP.equals(path) ? "semantic.startup.cellular_enabled" : null;
    }

    static String consumer(String path) {
        return STARTUP.equals(path) ? "/data/local/d31-startup-handover/handover.jar" : null;
    }

    static String marker(String path, byte[] bytes) throws JSONException {
        if (bytes == null || bytes.length > MAX_BYTES) return null;
        if (SUPPORT.equals(path)) {
            JSONObject root = SystemSupportConfiguration.evidence(bytes, "template");
            return "OBSERVED".equals(root.getString("state")) ? root.getString("value") + "/disabled" : null;
        }
        String hash = hex(digest().digest(bytes));
        if (RECOVERY.equals(path) && hash.equals("2d494df0f49eda10ef4ea9cc821c05dd72401a1a3cafe824958cf96fd61eab57"))
            return "/data/local/d31-recovery-entry/enabled";
        if (RESCUE.equals(path) && hash.equals("e7981120d1c44563cfa4914de952b7f3c415c91604a4ee6b81865793bf2641b1"))
            return "/data/local/d31-rescue/enabled";
        if (STARTUP.equals(path) && hash.equals("35a9963961dc7e30c9d9be2b0e5ebf37783582c8db78d5ca2f799f577da6e615"))
            return "/data/local/d31-startup-handover/cellular-enabled";
        return null;
    }

    static JSONObject evidence(String path, byte[] bytes, String kind, String source) throws JSONException {
        return evidence(path, bytes, kind, source, null);
    }

    static JSONObject evidence(String path, byte[] bytes, String kind, String source, String consumerHash) throws JSONException {
        if (marker(path, bytes) == null)
            return missing("NOT_CHECKED", "SWITCH_CONSUMER_TEMPLATE_NOT_RECOGNIZED", source);
        // 旧两份JAR及1.4.4完整DEX均有同源复编证据；仅绑定正常--apply消费者。
        if (STARTUP.equals(path)
                && !"f56cb9586d08046848e02c321cb00916f595ee039f2b0e8e3e92028474c2da92".equals(consumerHash)
                && !"3e12fe7cdf595483d66b72a9a3ab371a17ee58b2c801155c0b24f4f5481d85ec".equals(consumerHash)
                && !"1dc959ec6e6513d48b8894042c347b749057690dd2674eea69ff639b84794978".equals(consumerHash))
            return missing("NOT_CHECKED", "SWITCH_CONSUMER_JAR_NOT_RECOGNIZED", source);
        if ("ABSENT".equals(kind)) return observed(false, source);
        if ("symlink".equals(kind)) {
            // Recovery明确拒绝叶链接；其它项需要跟随目标才能判断，保持只读边界不跟随。
            return RECOVERY.equals(path) ? observed(false, source)
                    : missing("NOT_CHECKED", "SWITCH_LINK_TARGET_NOT_CHECKED", source);
        }
        if ("file".equals(kind) || "directory".equals(kind) || "block".equals(kind) || "unsupported".equals(kind))
            return observed(SUPPORT.equals(path) || "file".equals(kind), source);
        return missing("NOT_CHECKED", "SWITCH_MARKER_NOT_CHECKED", source);
    }

    private ConfigurationSwitches() { }
}
