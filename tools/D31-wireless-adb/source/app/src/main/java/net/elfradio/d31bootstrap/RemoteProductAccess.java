package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅投影固定产品访问状态，不推断权限生效或执行结果。 */
public final class RemoteProductAccess {
    public static final String CATALOG = "d31-product-access-1";
    private static final String REMOTE = "net.elfradio.d31bootstrap";
    private static final String QUIK = "net.elfradio.d31phone.debug";
    private static final String GUARD_COMPONENT = "net.elfradio.d31zelloguard/"
            + "net.elfradio.d31zelloguard.GuardAccessibilityService";
    private static final String[][] APP_OPS = {
            {QUIK, "READ_SMS"}, {QUIK, "SEND_SMS"}, {QUIK, "WRITE_SMS"},
            {"com.starnet.getnumber", "SEND_SMS"},
            {REMOTE, "CAMERA"}, {REMOTE, "RECORD_AUDIO"},
            {REMOTE, "FINE_LOCATION"}, {REMOTE, "COARSE_LOCATION"}};
    private static final String[] MODE_NAMES = {"allowed", "ignored", "errored", "default"};

    public interface Access {
        int appOp(String pkg, String op) throws Exception;
        String secureSetting(String key) throws Exception;
        boolean accessibilityBound(String component) throws Exception;
    }

    private interface Read { Object get() throws Exception; }

    private static void add(JSONArray facts, String id, String source, Read read) throws Exception {
        JSONObject fact = new JSONObject().put("id", id).put("source", source);
        try {
            Object value = read.get();
            if (value == null) throw new IOException("VALUE_UNAVAILABLE");
            fact.put("state", "OBSERVED").put("value", value);
        } catch (Exception failure) {
            fact.put("state", "READ_FAILED").put("reason", failure.getClass().getSimpleName());
        }
        facts.put(fact);
    }

    public static JSONObject collect(Access access, long now) throws Exception {
        if (access == null || now < 0) throw new IllegalArgumentException("INVALID_COLLECTION_INPUT");
        JSONArray facts = new JSONArray();
        for (final String[] item : APP_OPS) {
            final String pkg = item[0], op = item[1];
            add(facts, "appop:" + pkg + ":" + op, "app_ops", () -> {
                int mode = access.appOp(pkg, op);
                if (mode < 0 || mode >= MODE_NAMES.length) throw new IOException("APP_OP_MODE_INVALID");
                return new JSONObject().put("mode", mode).put("modeName", MODE_NAMES[mode]);
            });
        }
        add(facts, "setting:accessibility_enabled", "secure.accessibility_enabled", () -> {
            String value = access.secureSetting("accessibility_enabled");
            if ("0".equals(value)) return false;
            if ("1".equals(value)) return true;
            throw new IOException("SETTING_VALUE_UNAVAILABLE_OR_INVALID");
        });
        add(facts, "accessibility_service:" + GUARD_COMPONENT, "secure.enabled_accessibility_services",
                () -> RemoteProductConfiguration.containsComponent(
                        access.secureSetting("enabled_accessibility_services"), GUARD_COMPONENT));
        add(facts, "accessibility_bound:" + GUARD_COMPONENT, "accessibility_manager",
                () -> access.accessibilityBound(GUARD_COMPONENT));
        int observed = 0;
        for (int i = 0; i < facts.length(); i++)
            if ("OBSERVED".equals(facts.getJSONObject(i).getString("state"))) observed++;
        return new JSONObject().put("schemaVersion", 1).put("catalog", CATALOG).put("capturedAtMs", now)
                .put("userId", 0).put("facts", facts).put("observed", observed).put("total", facts.length())
                .put("status", observed == facts.length() ? "COMPLETE" : "PARTIAL")
                .put("atomicSnapshot", false).put("executionState", "NOT_CHECKED")
                .put("systemConsistency", "NOT_ASSESSED").put("repairPlanGenerated", false);
    }

    private RemoteProductAccess() { }
}
