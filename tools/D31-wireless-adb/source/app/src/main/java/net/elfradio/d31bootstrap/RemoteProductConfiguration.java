package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 按需读取有限产品状态；不复制账号、其它应用列表或私人设置。 */
public final class RemoteProductConfiguration {
    public static final String CATALOG = "d31-product-runtime-1";
    static final String REMOTE = "net.elfradio.d31bootstrap";
    static final String SUPPORT = "net.elfradio.d31system";
    static final String SMS = "net.elfradio.d31phone.debug";
    static final String GUARD = "net.elfradio.d31zelloguard";
    static final String[] PACKAGES = {REMOTE, SUPPORT, SMS, GUARD, "com.starnet.nexui",
            "com.starnet.getnumber", "com.loudtalks"};
    static final String[] COMPONENTS = {
            SUPPORT + "/" + SUPPORT + ".SystemReceiver",
            SUPPORT + "/" + SUPPORT + ".MessageNotificationListener",
            GUARD + "/" + GUARD + ".GuardAccessibilityService",
            GUARD + "/" + GUARD + ".GuardNotificationListener",
            "com.starnet.getnumber/com.starnet.getnumber.BootReceiver",
            "com.starnet.getnumber/com.starnet.getnumber.GetNumberService",
            "com.starnet.getnumber/com.starnet.getnumber.SmsListener",
            "com.starnet.getnumber/com.starnet.getnumber.MainActivity",
            REMOTE + "/" + REMOTE + ".BootReceiver",
            REMOTE + "/" + REMOTE + ".VendorNetworkReceiver",
            REMOTE + "/" + REMOTE + ".RemoteManualReceiver"};

    public interface Access {
        JSONObject packageState(String pkg) throws Exception;
        JSONObject componentState(String component) throws Exception;
        boolean permission(String pkg, String permission) throws Exception;
        String secureSetting(String key) throws Exception;
        boolean batteryExempt(String pkg) throws Exception;
    }
    private interface Read { Object get() throws Exception; }

    private static JSONObject fields(JSONObject raw, String... names) throws Exception {
        if (raw == null || !(raw.opt("installed") instanceof Boolean)) throw new IOException("PACKAGE_STATE_INVALID");
        JSONObject result = new JSONObject().put("installed", raw.getBoolean("installed"));
        if (!raw.getBoolean("installed")) return result;
        for (String name : names) {
            Object value = raw.get(name);
            boolean integer = name.equals("versionCode") || name.equals("enabledSetting");
            if (integer ? !(value instanceof Integer || value instanceof Long) : !(value instanceof Boolean))
                throw new IOException("PACKAGE_FIELD_INVALID");
            long number = integer ? ((Number) value).longValue() : 0;
            if (integer && (number < 0 || number > Integer.MAX_VALUE || name.equals("enabledSetting") && number > 4))
                throw new IOException("PACKAGE_FIELD_RANGE");
            result.put(name, value);
        }
        return result;
    }

    private static void add(JSONArray rows, String id, String source, Read read) throws Exception {
        JSONObject row = new JSONObject().put("id", id).put("source", source);
        try {
            Object value = read.get();
            if (value == null) throw new IOException("VALUE_UNAVAILABLE");
            row.put("state", "OBSERVED").put("value", value);
        } catch (Exception failure) {
            row.put("state", "READ_FAILED").put("reason", failure.getClass().getSimpleName());
        }
        rows.put(row);
    }

    static String canonicalComponent(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))
            throw new IOException("COMPONENT_FORMAT_INVALID");
        String[] parts = value.split("/", -1);
        String name = parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1];
        if (!validName(parts[0]) || !validName(name)) throw new IOException("COMPONENT_FORMAT_INVALID");
        return parts[0] + "/" + name;
    }

    private static boolean validName(String value) {
        for (String part : value.split("\\.", -1))
            if (!part.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return false;
        return true;
    }

    static boolean containsComponent(String value, String component) throws Exception {
        if (value == null || value.isEmpty()) return false;
        if (value.length() > 16384) throw new IOException("SETTING_TOO_LARGE");
        boolean found = false;
        for (String entry : value.split(":", -1)) {
            if (entry.isEmpty()) throw new IOException("LIST_ENTRY_INVALID");
            if (canonicalComponent(entry).equals(canonicalComponent(component))) found = true;
        }
        return found;
    }

    public static JSONObject collect(Access access, long now) throws Exception {
        if (access == null || now < 0) throw new IllegalArgumentException("INVALID_COLLECTION_INPUT");
        JSONArray rows = new JSONArray();
        for (final String pkg : PACKAGES)
            add(rows, "package:" + pkg, "package_manager", () -> fields(access.packageState(pkg),
                    "versionCode", "system", "updatedSystem", "privileged", "enabled", "enabledSetting"));
        for (final String component : COMPONENTS)
            add(rows, "component:" + component, "package_manager", () -> fields(access.componentState(component),
                    "enabled", "enabledSetting", "manifestEnabled"));
        for (final String permission : new String[]{"READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "READ_EXTERNAL_STORAGE"})
            permission(rows, access, SMS, permission);
        permission(rows, access, SUPPORT, "READ_PHONE_STATE");
        permission(rows, access, "com.loudtalks", "RECORD_AUDIO");
        for (final String permission : new String[]{"READ_LOGS", "DUMP", "WRITE_SECURE_SETTINGS", "CAMERA", "RECORD_AUDIO",
                "READ_PHONE_STATE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"}) permission(rows, access, REMOTE, permission);
        add(rows, "setting:default_sms_is_quik", "secure.sms_default_application", () -> {
            String value = access.secureSetting("sms_default_application");
            if (value != null && !value.isEmpty() && (value.length() > 1024 || !validName(value)))
                throw new IOException("SETTING_FORMAT_INVALID");
            return SMS.equals(value);
        });
        for (final String component : new String[]{COMPONENTS[1], COMPONENTS[3]})
            add(rows, "listener:" + component, "secure.enabled_notification_listeners",
                    () -> containsComponent(access.secureSetting("enabled_notification_listeners"), component));
        for (final String pkg : new String[]{"com.loudtalks", GUARD})
            add(rows, "battery_exempt:" + pkg, "power_manager", () -> access.batteryExempt(pkg));
        int observed = 0;
        for (int i = 0; i < rows.length(); i++) if ("OBSERVED".equals(rows.getJSONObject(i).getString("state"))) observed++;
        return new JSONObject().put("schemaVersion", 1).put("catalog", CATALOG).put("capturedAtMs", now)
                .put("userId", 0).put("facts", rows).put("observed", observed).put("total", rows.length())
                .put("status", observed == rows.length() ? "COMPLETE" : "PARTIAL")
                .put("atomicSnapshot", false).put("executionState", "NOT_CHECKED")
                .put("systemConsistency", "NOT_ASSESSED").put("repairPlanGenerated", false);
    }

    private static void permission(JSONArray rows, Access access, String pkg, String name) throws Exception {
        String permission = "android.permission." + name;
        add(rows, "permission:" + pkg + ":" + permission, "package_manager.checkPermission",
                () -> access.permission(pkg, permission));
    }

    private RemoteProductConfiguration() { }
}
