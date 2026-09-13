package net.elfradio.d31bootstrap.telemetry;

import java.lang.reflect.InvocationTargetException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 模拟权限和缓存边界，不连接服务、不替换真实UID或包名。 */
public class LocationCacheDiagnosticsTest {
    public static final class Request { }
    public interface Service {
        Object getLastLocation(Request request, String packageName) throws Exception;
        Object getLastLocation(String provider, String packageName);
    }
    private static final class Binder implements Service {
        Request request;
        String packageName;
        Object result;
        Exception failure;
        int calls;
        public Object getLastLocation(Request request, String packageName) throws Exception {
            calls++; this.request = request; this.packageName = packageName;
            if (failure != null) throw failure;
            return result;
        }
        public Object getLastLocation(String provider, String packageName) {
            throw new AssertionError("不得猜测Provider字符串签名");
        }
    }
    private static final long WALL = 1800000000000L, ELAPSED = 1000000000000L;
    private static final TelemetryCollector.Clock CLOCK = new TelemetryCollector.Clock() {
        public long wallTimeMillis() { return WALL; }
        public long elapsedRealtimeNanos() { return ELAPSED; }
    };
    private static JSONObject cache(TelemetryCollector.Fix fix) throws Exception {
        return LocationCacheDiagnostics.cache(fix, new TelemetryCollector.Limits(0, 300000), CLOCK);
    }
    private static TelemetryCollector.Fix fix(long wall, long elapsed, String provider, Float accuracy, boolean mock) {
        return new TelemetryCollector.Fix(0, 0, accuracy, provider, wall, elapsed, mock);
    }

    @Test public void packageOwnershipRequiresExactObservedMatch() {
        assertEquals("UID_HAS_NO_PACKAGES", LocationCacheDiagnostics.packageMatch("android", null));
        assertEquals("UID_HAS_NO_PACKAGES", LocationCacheDiagnostics.packageMatch("android", new String[0]));
        assertEquals("UID_PACKAGE_MISMATCH", LocationCacheDiagnostics.packageMatch("android", new String[]{"synthetic.android", "android.synthetic"}));
        assertEquals("UID_PACKAGE_MATCH", LocationCacheDiagnostics.packageMatch("synthetic.app", new String[]{"other.synthetic", "synthetic.app"}));
        assertEquals("INVALID_CALLER_PACKAGE", LocationCacheDiagnostics.packageMatch(null, new String[]{"android"}));
    }

    @Test public void exactApi23CallPreservesRequestAndActualPackage() throws Exception {
        Binder service = new Binder(); Request request = new Request(); service.result = new Object();
        Object value = LocationCacheDiagnostics.lastLocation(Service.class, service, Request.class, request, "synthetic.actual");
        assertSame(service.result, value); assertSame(request, service.request);
        assertEquals("synthetic.actual", service.packageName); assertEquals(1, service.calls);
    }

    @Test public void unavailableArgumentsCannotBecomeGuessedIdentityOrFusedRequest() throws Exception {
        Binder service = new Binder();
        for (String pkg : new String[]{null, ""}) {
            try { LocationCacheDiagnostics.lastLocation(Service.class, service, Request.class, new Request(), pkg); fail("需要真实包名"); }
            catch (IllegalArgumentException expected) { }
        }
        try { LocationCacheDiagnostics.lastLocation(Service.class, service, Request.class, null, "synthetic.actual"); fail("不能改成默认融合请求"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(0, service.calls);
    }

    @Test public void binderDenialIsNotConvertedToEmptyCache() throws Exception {
        Binder service = new Binder(); SecurityException cause = new SecurityException("invalid UID 0"); service.failure = cause;
        JSONObject result = LocationCacheDiagnostics.step("gps.binder.getLastLocation", () ->
                LocationCacheDiagnostics.lastLocation(Service.class, service, Request.class, new Request(), "synthetic.actual"));
        assertEquals("FAILED", result.getString("state")); assertFalse(result.has("value"));
        JSONObject first = result.getJSONObject("failure").getJSONArray("chain").getJSONObject(0);
        assertEquals(SecurityException.class.getName(), first.getString("type")); assertEquals("invalid UID 0", first.getString("message"));
        assertEquals(1, service.calls);
    }

    @Test public void failedStageDoesNotPreventIndependentReadEvidence() throws Exception {
        JSONObject denied = LocationCacheDiagnostics.step("gps.framework.getProvider", () -> { throw new SecurityException("synthetic-denial"); });
        JSONObject enabled = LocationCacheDiagnostics.step("gps.framework.isProviderEnabled", () -> true);
        JSONObject absent = LocationCacheDiagnostics.step("gps.framework.getLastKnownLocation", () -> cache(null));
        assertEquals("FAILED", denied.getString("state")); assertTrue(enabled.getBoolean("value"));
        assertEquals("RETURNED", absent.getString("state"));
        assertFalse(absent.getJSONObject("value").getBoolean("cache_returned"));
        assertFalse(absent.getJSONObject("value").getBoolean("usable"));
    }

    @Test public void errorChainAndTraceLimitsAreExplicit() throws Exception {
        SecurityException cause = new SecurityException("synthetic-denial");
        JSONObject simple = LocationCacheDiagnostics.failure(new InvocationTargetException(cause));
        assertEquals(2, simple.getJSONArray("chain").length()); assertFalse(simple.getBoolean("chain_truncated"));
        Exception longMessage = new Exception(new String(new char[3000]).replace('\0', 'x'));
        StackTraceElement[] trace = new StackTraceElement[20];
        for (int i = 0; i < trace.length; i++) trace[i] = new StackTraceElement("Synthetic", "read", "Synthetic.java", i);
        longMessage.setStackTrace(trace);
        JSONObject entry = LocationCacheDiagnostics.failure(longMessage).getJSONArray("chain").getJSONObject(0);
        assertEquals(2048, entry.getString("message").length()); assertTrue(entry.getBoolean("message_truncated"));
        assertEquals(12, entry.getJSONArray("stack").length()); assertTrue(entry.getBoolean("stack_truncated"));
        Exception nested = longMessage;
        for (int i = 0; i < 5; i++) nested = new Exception("synthetic-wrapper", nested);
        JSONObject bounded = LocationCacheDiagnostics.failure(nested);
        assertEquals(4, bounded.getJSONArray("chain").length()); assertTrue(bounded.getBoolean("chain_truncated"));
    }

    @Test public void staleCacheIsRecordedAsReturnedButNotUsable() throws Exception {
        JSONObject result = cache(fix(WALL - 300001, ELAPSED - 300001000000L, "gps", 25.5f, false));
        assertTrue(result.getBoolean("cache_returned")); assertFalse(result.getBoolean("usable"));
        assertEquals("stale_or_invalid_location_time", result.getString("reason"));
        assertEquals(WALL - 300001, result.getLong("sampled_at_ms"));
        assertEquals(25.5, result.getDouble("accuracy_m"), 0);
        assertFalse(result.has("lat")); assertFalse(result.has("lng"));
    }

    @Test public void exactAgeBoundaryAndUnknownAccuracyRemainVisible() throws Exception {
        JSONObject result = cache(fix(WALL - 300000, ELAPSED - 300000000000L, "network", null, false));
        assertTrue(result.getBoolean("usable")); assertEquals("network", result.getString("provider"));
        assertTrue(result.isNull("accuracy_m")); assertFalse(result.getBoolean("accuracy_reported"));
        assertTrue(result.getBoolean("coordinates_omitted"));
    }

    @Test public void mockUnknownProviderFutureAndInvalidPrecisionCannotPass() throws Exception {
        for (TelemetryCollector.Fix fix : new TelemetryCollector.Fix[]{
                fix(WALL, ELAPSED, "gps", 1f, true), fix(WALL, ELAPSED, "fused", 1f, false),
                fix(WALL + 1, ELAPSED, "gps", 1f, false), fix(WALL, ELAPSED + 1, "gps", 1f, false),
                fix(WALL, ELAPSED, "gps", Float.NaN, false), fix(WALL, ELAPSED, "gps", 0f, false)}) {
            JSONObject result = cache(fix);
            assertTrue(result.getBoolean("cache_returned")); assertFalse(result.getBoolean("usable"));
            assertNotEquals("recent_cache", result.getString("reason"));
        }
    }
}
