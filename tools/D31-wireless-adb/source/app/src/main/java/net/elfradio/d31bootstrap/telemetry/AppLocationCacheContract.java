package net.elfradio.d31bootstrap.telemetry;

import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** 应用缓存桥的身份、启动调用和回包合同；不把服务启动成功当定位成功。 */
final class AppLocationCacheContract {
    static final int MAX_REPLY_BYTES = 16384;
    static final long WAIT_MS = 4000;

    static void request(String id, String boot, long started, long now) {
        if (id == null || !id.matches("[a-f0-9-]{36}") || boot == null || !boot.matches("[a-f0-9-]{36}")
                || started <= 0 || now < started || now - started > WAIT_MS * 1000000)
            throw new IllegalArgumentException("INVALID_OR_EXPIRED_CACHE_REQUEST");
    }

    static Object start(Class<?> serviceInterface, Object manager, Class<?> appThread,
                        Class<?> intentType, Object intent) throws Exception {
        if (manager == null || intent == null) throw new IllegalArgumentException("MISSING_CACHE_SERVICE");
        try {
            // AOSP6要求callingPackage非null；root无所属包，使用空值而非其它UID的包名。
            return serviceInterface.getMethod("startService", appThread, intentType, String.class, String.class, int.class)
                    .invoke(manager, null, intent, null, "", 0);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapped;
        }
    }

    static String encode(String id, String boot, int uid, long started, TelemetryCollector.LocationReading reading,
                         TelemetryCollector.Clock clock) throws Exception {
        JSONObject body = new JSONObject().put("schemaVersion", 1).put("request_id", id).put("boot_id", boot)
                .put("app_uid", uid).put("started_elapsed_nanos", started)
                .put("completed_elapsed_nanos", clock.elapsedRealtimeNanos()).put("reason", reading.reason)
                .put("listener_released", reading.listenerReleased).put("fix", JSONObject.NULL);
        TelemetryCollector.Fix fix = reading.fix;
        if (fix != null) body.put("fix", new JSONObject().put("lat", fix.latitude).put("lng", fix.longitude)
                .put("accuracy", fix.accuracyMetres == null ? JSONObject.NULL : fix.accuracyMetres)
                .put("provider", fix.provider).put("sampled_at_ms", fix.sampledAtMs)
                .put("elapsed_nanos", fix.elapsedNanos).put("mock", fix.mock));
        return body.toString();
    }

    static TelemetryCollector.LocationReading decode(String value, String id, String boot, int expectedUid,
            int senderUid, long started, TelemetryCollector.Limits limits, TelemetryCollector.Clock clock) throws Exception {
        request(id, boot, started, clock.elapsedRealtimeNanos());
        if (expectedUid <= 0 || senderUid != expectedUid || value == null || value.length() > MAX_REPLY_BYTES
                || value.getBytes("UTF-8").length > MAX_REPLY_BYTES) throw new SecurityException("CACHE_REPLY_IDENTITY_OR_SIZE");
        JSONObject body = new JSONObject(value);
        long completed = number(body, "completed_elapsed_nanos").longValue();
        if (number(body, "schemaVersion").doubleValue() != 1 || !id.equals(body.get("request_id"))
                || !boot.equals(body.get("boot_id")) || number(body, "app_uid").doubleValue() != expectedUid
                || number(body, "started_elapsed_nanos").longValue() != started
                || completed < started || completed > clock.elapsedRealtimeNanos()
                || !(body.get("listener_released") instanceof Boolean) || !body.getBoolean("listener_released"))
            throw new SecurityException("CACHE_REPLY_MISMATCH");
        String reason = body.getString("reason");
        if (body.isNull("fix")) return missing(reason);
        JSONObject item = body.getJSONObject("fix");
        if (!(item.get("mock") instanceof Boolean) || !(item.get("provider") instanceof String))
            throw new IllegalArgumentException("CACHE_FIX_TYPE");
        TelemetryCollector.Fix fix = new TelemetryCollector.Fix(number(item, "lat").doubleValue(), number(item, "lng").doubleValue(),
                item.isNull("accuracy") ? null : number(item, "accuracy").floatValue(), item.getString("provider"),
                number(item, "sampled_at_ms").longValue(), number(item, "elapsed_nanos").longValue(), item.getBoolean("mock"));
        String invalid = TelemetryCollector.invalidFix(fix, limits, clock);
        if (invalid != null) return new TelemetryCollector.LocationReading(fix, invalid, true);
        if (!"recent_cache".equals(reason)) throw new IllegalArgumentException("CACHE_REPLY_NOT_CACHED");
        return new TelemetryCollector.LocationReading(fix, "recent_cache", true);
    }

    static TelemetryCollector.LocationReading missing(String reason) {
        for (String allowed : new String[]{"permission_denied", "location_disabled", "provider_unavailable", "no_cached_location",
                "app_cache_timeout", "app_cache_unavailable", "app_cache_identity_mismatch", "app_cache_invalid_reply", "app_cache_busy"})
            if (allowed.equals(reason)) return new TelemetryCollector.LocationReading(null, reason, true);
        return new TelemetryCollector.LocationReading(null, "app_cache_unavailable", true);
    }

    private static Number number(JSONObject body, String name) throws Exception {
        Object value = body.get(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException("CACHE_NUMBER_TYPE");
        return (Number) value;
    }
    private AppLocationCacheContract() { }
}
