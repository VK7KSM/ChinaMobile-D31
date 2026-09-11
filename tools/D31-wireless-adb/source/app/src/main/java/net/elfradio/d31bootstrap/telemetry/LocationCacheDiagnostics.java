package net.elfradio.d31bootstrap.telemetry;

import java.lang.reflect.InvocationTargetException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 只读定位探针的有界证据；不改变身份、权限或定位状态，不输出坐标。 */
final class LocationCacheDiagnostics {
    interface Read { Object read() throws Exception; }

    static JSONObject step(String name, Read read) throws Exception {
        long start = System.nanoTime();
        JSONObject result = new JSONObject().put("stage", name);
        try {
            Object value = read.read();
            result.put("state", value == null ? "NO_VALUE" : "RETURNED")
                    .put("value", value == null ? JSONObject.NULL : value);
        } catch (Exception failure) {
            result.put("state", "FAILED").put("failure", failure(failure));
        }
        return result.put("elapsed_ms", Math.max(0, (System.nanoTime() - start) / 1000000));
    }

    static JSONObject failure(Throwable failure) throws Exception {
        JSONArray chain = new JSONArray();
        Throwable current = failure;
        for (int i = 0; current != null && i < 4; i++) {
            String message = current.getMessage();
            StackTraceElement[] trace = current.getStackTrace();
            JSONArray frames = new JSONArray();
            for (int j = 0; j < trace.length && j < 12; j++) frames.put(trace[j].toString());
            chain.put(new JSONObject().put("type", current.getClass().getName())
                    .put("message", message == null ? JSONObject.NULL : message.substring(0, Math.min(2048, message.length())))
                    .put("message_truncated", message != null && message.length() > 2048)
                    .put("stack", frames).put("stack_truncated", trace.length > 12));
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return new JSONObject().put("chain", chain).put("chain_truncated", current != null);
    }

    static String packageMatch(String callerPackage, String[] uidPackages) {
        if (callerPackage == null || callerPackage.isEmpty()) return "INVALID_CALLER_PACKAGE";
        if (uidPackages == null || uidPackages.length == 0) return "UID_HAS_NO_PACKAGES";
        for (String value : uidPackages) if (callerPackage.equals(value)) return "UID_PACKAGE_MATCH";
        return "UID_PACKAGE_MISMATCH";
    }

    static Object lastLocation(Class<?> serviceInterface, Object service, Class<?> requestType,
                               Object request, String actualPackage) throws Exception {
        if (service == null || request == null || actualPackage == null || actualPackage.isEmpty())
            throw new IllegalArgumentException("MISSING_REAL_LOCATION_CALL_ARGUMENT");
        try {
            return serviceInterface.getMethod("getLastLocation", requestType, String.class)
                    .invoke(service, request, actualPackage);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapped;
        }
    }

    static JSONObject cache(TelemetryCollector.Fix fix, TelemetryCollector.Limits limits,
                            TelemetryCollector.Clock clock) throws Exception {
        String invalid = TelemetryCollector.invalidFix(fix, limits, clock);
        JSONObject result = new JSONObject().put("cache_returned", fix != null).put("usable", invalid == null)
                .put("reason", invalid == null ? "recent_cache" : invalid).put("coordinates_omitted", true);
        if (fix == null) return result;
        return result.put("provider", fix.provider == null ? JSONObject.NULL : fix.provider)
                .put("accuracy_m", fix.accuracyMetres == null || Float.isNaN(fix.accuracyMetres)
                        || Float.isInfinite(fix.accuracyMetres) ? JSONObject.NULL : fix.accuracyMetres)
                .put("accuracy_reported", fix.accuracyMetres != null).put("mock", fix.mock)
                .put("sampled_at_ms", fix.sampledAtMs).put("sample_elapsed_nanos", fix.elapsedNanos)
                .put("observed_at_ms", clock.wallTimeMillis()).put("observed_elapsed_nanos", clock.elapsedRealtimeNanos());
    }

    private LocationCacheDiagnostics() { }
}
