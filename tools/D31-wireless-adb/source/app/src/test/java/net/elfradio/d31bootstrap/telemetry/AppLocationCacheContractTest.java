package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 只测应用缓存桥合同；真实AMS启动、Binder发送UID及Service生命周期由主任务上机验证。 */
public class AppLocationCacheContractTest {
    private static final String ID = "10000000-0000-4000-8000-000000000001";
    private static final String BOOT = "20000000-0000-4000-8000-000000000002";
    private static final long WALL = 1800000000000L, START = 1000000000000L;
    private static final int APP_UID = 10123;
    private static final class Clock implements TelemetryCollector.Clock {
        long wall = WALL, elapsed = START;
        public long wallTimeMillis() { return wall; }
        public long elapsedRealtimeNanos() { return elapsed; }
    }
    public interface AppThread { }
    public static final class Intent { }
    public interface Manager {
        Object startService(AppThread caller, Intent intent, String type, String packageName, int user) throws Exception;
    }
    private static final class Binder implements Manager {
        Object caller, intent, type, packageName;
        int user = -1, calls;
        final Object component = new Object();
        public Object startService(AppThread caller, Intent intent, String type, String packageName, int user) {
            this.caller = caller; this.intent = intent; this.type = type; this.packageName = packageName; this.user = user; calls++;
            return component;
        }
    }
    private static TelemetryCollector.LocationReading reading(long at, long elapsed, String provider, Float accuracy, boolean mock) {
        return new TelemetryCollector.LocationReading(new TelemetryCollector.Fix(0, 0, accuracy, provider, at, elapsed, mock), "recent_cache", true);
    }
    private static String response(Clock clock) throws Exception {
        return AppLocationCacheContract.encode(ID, BOOT, APP_UID, START,
                reading(WALL, START, "gps", 100.1f, false), clock);
    }
    private static TelemetryCollector.LocationReading decode(String payload, Clock clock) throws Exception {
        return AppLocationCacheContract.decode(payload, ID, BOOT, APP_UID, APP_UID, START,
                new TelemetryCollector.Limits(0, 300000), clock);
    }
    private static void rejected(String payload, Clock clock) throws Exception {
        try { decode(payload, clock); fail("应拒绝不符回包"); }
        catch (IllegalArgumentException | SecurityException expected) { }
    }

    @Test public void rootServiceStartUsesRealBinderUidAndNoBorrowedPackageName() throws Exception {
        Binder binder = new Binder(); Intent intent = new Intent();
        assertSame(binder.component, AppLocationCacheContract.start(Manager.class, binder, AppThread.class, Intent.class, intent));
        assertNull(binder.caller); assertNull(binder.type); assertSame(intent, binder.intent);
        assertEquals("", binder.packageName); assertEquals(0, binder.user); assertEquals(1, binder.calls);
    }

    @Test public void replyPreservesOriginalSourcePrecisionAndSamplingTimes() throws Exception {
        Clock clock = new Clock();
        TelemetryCollector.LocationReading decoded = decode(response(clock), clock);
        assertEquals("recent_cache", decoded.reason); assertEquals("gps", decoded.fix.provider);
        assertEquals(100.1f, decoded.fix.accuracyMetres, 0); assertEquals(WALL, decoded.fix.sampledAtMs);
        assertEquals(START, decoded.fix.elapsedNanos); assertFalse(decoded.fix.mock); assertTrue(decoded.listenerReleased);
    }

    @Test public void differentBinderUidCannotSpoofCorrectAppUidInJson() throws Exception {
        Clock clock = new Clock();
        for (int sender : new int[]{0, 1000, APP_UID + 1}) {
            try {
                AppLocationCacheContract.decode(response(clock), ID, BOOT, APP_UID, sender, START,
                        new TelemetryCollector.Limits(0, 300000), clock);
                fail("必须核验实际Binder发送UID");
            } catch (SecurityException expected) { }
        }
    }

    @Test public void wrongRequestBootClaimedUidOrProtocolIsRejected() throws Exception {
        Clock clock = new Clock(); String payload = response(clock);
        rejected(new JSONObject(payload).put("request_id", BOOT).toString(), clock);
        rejected(new JSONObject(payload).put("boot_id", ID).toString(), clock);
        rejected(new JSONObject(payload).put("app_uid", APP_UID + 1).toString(), clock);
        rejected(new JSONObject(payload).put("schemaVersion", 2).toString(), clock);
        rejected(new JSONObject(payload).put("started_elapsed_nanos", START - 1).toString(), clock);
        rejected(new JSONObject(payload).put("listener_released", false).toString(), clock);
    }

    @Test public void preparationAgeAndCompletedTimeAreIndependentGates() throws Exception {
        Clock clock = new Clock(); String payload = response(clock);
        clock.elapsed = START + 4000000000L;
        assertNotNull(decode(payload, clock).fix);
        clock.elapsed++;
        rejected(payload, clock);
        clock.elapsed = START - 1;
        rejected(payload, clock);
        clock.elapsed = START;
        rejected(new JSONObject(payload).put("completed_elapsed_nanos", START + 1).toString(), clock);
        rejected(new JSONObject(payload).put("completed_elapsed_nanos", START - 1).toString(), clock);
    }

    @Test public void missingCacheAndPermissionDenialRemainDistinct() throws Exception {
        Clock clock = new Clock();
        for (String reason : new String[]{"no_cached_location", "permission_denied", "app_cache_timeout", "app_cache_busy"}) {
            String payload = AppLocationCacheContract.encode(ID, BOOT, APP_UID, START, AppLocationCacheContract.missing(reason), clock);
            TelemetryCollector.LocationReading reading = decode(payload, clock);
            assertNull(reading.fix); assertEquals(reason, reading.reason);
        }
        assertEquals("app_cache_unavailable", AppLocationCacheContract.missing("synthetic-private-detail").reason);
    }

    @Test public void mockAndOldOrUnknownFixCannotBecomeReportCoordinates() throws Exception {
        final Clock clock = new Clock();
        for (TelemetryCollector.LocationReading input : new TelemetryCollector.LocationReading[]{
                reading(WALL - 300001, START - 300001000000L, "gps", 1f, false),
                reading(WALL, START, "gps", 1f, true), reading(WALL, START, "fused", 1f, false),
                reading(WALL + 1, START, "gps", 1f, false), reading(WALL, START, "gps", 0f, false)}) {
            final TelemetryCollector.LocationReading reply = decode(AppLocationCacheContract.encode(ID, BOOT, APP_UID, START, input, clock), clock);
            TelemetryCollector.Sample sample = new TelemetryCollector(new TelemetryCollector.Access() {
                public TelemetryCollector.LocationReading location(TelemetryCollector.Limits limits, TelemetryCollector.Clock ignored) { return reply; }
                public TelemetryCollector.BatteryReading battery() { return null; }
            }, clock).collect(new TelemetryCollector.Limits(0, 300000));
            assertTrue(sample.reportFields().isNull("gps"));
            assertNotEquals("recent_cache", sample.reportFields().getString("location_reason"));
        }
    }

    @Test public void absentAccuracyStaysAbsentAndActiveSampleClaimIsRejected() throws Exception {
        Clock clock = new Clock();
        String payload = AppLocationCacheContract.encode(ID, BOOT, APP_UID, START, reading(WALL, START, "network", null, false), clock);
        assertNull(decode(payload, clock).fix.accuracyMetres);
        rejected(new JSONObject(payload).put("reason", "sampled").toString(), clock);
    }

    @Test public void oversizedReplyAndCoercedIdentityTypesAreRejected() throws Exception {
        Clock clock = new Clock();
        rejected(new JSONObject(response(clock)).put("app_uid", String.valueOf(APP_UID)).toString(), clock);
        rejected(new JSONObject(response(clock)).put("ignored", new String(new char[17000]).replace('\0', 'x')).toString(), clock);
    }

    @Test public void newBridgeFailureReasonsSurviveCollectorWithoutInventingPosition() throws Exception {
        for (final String reason : new String[]{"app_cache_timeout", "app_cache_unavailable", "app_cache_identity_mismatch", "app_cache_invalid_reply", "app_cache_busy"}) {
            JSONObject fields = new TelemetryCollector(new TelemetryCollector.Access() {
                public TelemetryCollector.LocationReading location(TelemetryCollector.Limits limits, TelemetryCollector.Clock clock) {
                    return AppLocationCacheContract.missing(reason);
                }
                public TelemetryCollector.BatteryReading battery() { return new TelemetryCollector.BatteryReading(null, null, 1, null, false); }
            }, new Clock()).collect(new TelemetryCollector.Limits(0, 300000)).reportFields();
            assertTrue(fields.isNull("gps")); assertEquals(reason, fields.getString("location_reason"));
            assertTrue(fields.getBoolean("charging")); assertTrue(fields.isNull("battery"));
        }
    }
}
