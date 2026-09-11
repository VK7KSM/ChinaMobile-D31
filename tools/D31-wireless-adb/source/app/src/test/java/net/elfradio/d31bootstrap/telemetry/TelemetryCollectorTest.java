package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 合成证据验证缺失语义、时间门及冻结报告；不调用设备或网络。 */
public class TelemetryCollectorTest {
    private static final long NOW = 1800000000000L;
    private static final long ELAPSED = 2000000000000L;
    private static final TelemetryCollector.Limits LIMITS = new TelemetryCollector.Limits(0, 300000);
    private static final class Time implements TelemetryCollector.Clock {
        long wall = NOW, elapsed = ELAPSED;
        public long wallTimeMillis() { return wall; }
        public long elapsedRealtimeNanos() { return elapsed; }
    }
    private static class Access implements TelemetryCollector.Access {
        TelemetryCollector.Fix fix = fix(0, 0, 25.5f, "gps", NOW, ELAPSED, false);
        TelemetryCollector.BatteryReading power = TelemetryCollectorTest.battery(50, 100, 1, 2, true);
        public TelemetryCollector.LocationReading location(TelemetryCollector.Limits limits, TelemetryCollector.Clock clock) throws Exception {
            return new TelemetryCollector.LocationReading(fix, "recent_cache", true);
        }
        public TelemetryCollector.BatteryReading battery() throws Exception { return power; }
    }
    private static TelemetryCollector.Fix fix(double lat, double lng, Float acc, String provider, long at, long elapsed, boolean mock) {
        return new TelemetryCollector.Fix(lat, lng, acc, provider, at, elapsed, mock);
    }
    private static TelemetryCollector.BatteryReading battery(Integer level, Integer scale, Integer plugged, Integer status, Boolean present) {
        return new TelemetryCollector.BatteryReading(level, scale, plugged, status, present);
    }
    private static TelemetryCollector.Sample sample(Access access) throws Exception {
        return new TelemetryCollector(access, new Time()).collect(LIMITS);
    }
    private static JSONObject report() throws Exception {
        return new JSONObject().put("report_id", "synthetic-report-1").put("reported_at", TelemetryJson.utc(NOW))
                .put("status_only", true).put("network", "ethernet");
    }
    @Test public void exactWireFieldsKeepSourcePrecisionAndSamplingTime() throws Exception {
        Access access = new Access();
        access.fix = fix(0, 0, 100.1f, "network", NOW - 1234, ELAPSED - 1234000000L, false);
        JSONObject fields = sample(access).reportFields(), gps = fields.getJSONObject("gps");
        assertEquals(5, fields.length()); assertEquals(5, gps.length());
        assertEquals(100.1, gps.getDouble("acc_m"), 0.00001);
        assertEquals("network", gps.getString("provider"));
        assertEquals(TelemetryJson.utc(NOW - 1234), gps.getString("at"));
        assertEquals(50, fields.getInt("battery")); assertTrue(fields.getBoolean("charging"));
    }
    @Test public void coordinateAndAccuracyBoundariesDoNotInventEvidence() throws Exception {
        Access access = new Access();
        access.fix = fix(90, -180, null, "gps", NOW, ELAPSED, false);
        TelemetryCollector.Sample valid = sample(access);
        assertTrue(valid.reportFields().getJSONObject("gps").isNull("acc_m"));
        assertEquals("NOT_REPORTED", valid.toJson().getJSONObject("evidence").getJSONObject("location").getString("accuracy_state"));
        for (double[] point : new double[][]{{90.001, 0}, {0, -180.001}, {Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}}) {
            access.fix = fix(point[0], point[1], 1f, "gps", NOW, ELAPSED, false);
            assertEquals("invalid_coordinates", sample(access).reportFields().getString("location_reason"));
        }
        for (float accuracy : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            access.fix = fix(0, 0, accuracy, "gps", NOW, ELAPSED, false);
            assertTrue(sample(access).reportFields().isNull("gps"));
        }
    }
    @Test public void unknownProviderAndMockCannotBecomeGps() throws Exception {
        Access access = new Access();
        for (String provider : new String[]{"passive", "fused", "ip", null}) {
            access.fix = fix(0, 0, 1f, provider, NOW, ELAPSED, false);
            assertEquals("unsupported_provider", sample(access).reportFields().getString("location_reason"));
        }
        access.fix = fix(0, 0, 1f, "gps", NOW, ELAPSED, true);
        assertEquals("mock_location_rejected", sample(access).reportFields().getString("location_reason"));
    }
    @Test public void monotonicAgeBoundaryAndRebootOrFutureAreRejected() throws Exception {
        Access access = new Access();
        access.fix = fix(0, 0, 1f, "gps", NOW - 300000, ELAPSED - 300000000000L, false);
        assertFalse(sample(access).reportFields().isNull("gps"));
        for (long time : new long[]{ELAPSED - 300000000001L, 0, ELAPSED + 1}) {
            access.fix = fix(0, 0, 1f, "gps", NOW, time, false);
            assertEquals("stale_or_invalid_location_time", sample(access).reportFields().getString("location_reason"));
        }
    }
    @Test public void wallTimeMustBeRealAndFreshIndependentlyOfElapsedTime() throws Exception {
        Access access = new Access();
        for (long at : new long[]{0, NOW + 1, NOW - 300001}) {
            access.fix = fix(0, 0, 1f, "gps", at, ELAPSED, false);
            assertEquals("invalid_location_wall_time", sample(access).reportFields().getString("location_reason"));
        }
    }
    @Test public void noBatteryCannotBeInventedAsFullBattery() throws Exception {
        Access access = new Access(); access.power = battery(100, 100, 1, 5, false);
        TelemetryCollector.Sample sample = sample(access); JSONObject fields = sample.reportFields();
        assertTrue(fields.isNull("battery")); assertFalse(fields.getBoolean("battery_present"));
        assertTrue(fields.getBoolean("charging"));
        assertEquals("ac", sample.toJson().getJSONObject("evidence").getJSONObject("power").getString("supply"));
    }
    @Test public void missingPresenceScaleAndPluggedStayUnknown() throws Exception {
        Access access = new Access(); access.power = battery(100, 100, null, 5, null);
        JSONObject fields = sample(access).reportFields();
        assertTrue(fields.isNull("battery")); assertTrue(fields.isNull("battery_present")); assertTrue(fields.isNull("charging"));
        for (Integer scale : new Integer[]{null, 0, -1}) {
            access.power = battery(50, scale, 8, 2, true); fields = sample(access).reportFields();
            assertTrue(fields.isNull("battery")); assertTrue(fields.isNull("charging"));
        }
        for (int level : new int[]{-1, 101}) {
            access.power = battery(level, 100, 0, 3, true);
            assertTrue(sample(access).reportFields().isNull("battery"));
        }
    }
    @Test public void pluggedIsSupplyEvenWhenBatteryIsFullOrStatusUnknown() throws Exception {
        Access access = new Access();
        for (int plugged : new int[]{1, 2, 4, 3}) {
            access.power = battery(100, 100, plugged, null, true);
            assertTrue(sample(access).reportFields().getBoolean("charging"));
        }
        access.power = battery(1, 4, 0, 2, true);
        assertFalse(sample(access).reportFields().getBoolean("charging"));
        assertEquals(25, sample(access).reportFields().getInt("battery"));
    }
    @Test public void readFailuresRemainMissingAndDoNotExposeExceptionDetails() throws Exception {
        Access access = new Access() {
            public TelemetryCollector.LocationReading location(TelemetryCollector.Limits limits, TelemetryCollector.Clock clock) {
                throw new SecurityException("synthetic-private-detail");
            }
            public TelemetryCollector.BatteryReading battery() { throw new IllegalStateException("synthetic-private-detail"); }
        };
        TelemetryCollector.Sample sample = sample(access); JSONObject fields = sample.reportFields();
        assertTrue(fields.isNull("gps")); assertTrue(fields.isNull("battery")); assertTrue(fields.isNull("charging"));
        assertEquals("permission_denied", fields.getString("location_reason"));
        assertEquals("READ_FAILED", sample.toJson().getJSONObject("evidence").getJSONObject("power").getString("state"));
        assertFalse(sample.toJson().toString().contains("synthetic-private-detail"));
    }
    @Test public void interruptedBatteryDoesNotBecomeOrdinaryMissingData() throws Exception {
        try {
            sample(new Access() { public TelemetryCollector.BatteryReading battery() throws Exception { throw new InterruptedException(); } });
            fail("应传播取消");
        } catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
    @Test public void mergeCopiesInputAndLeavesPendingIdentityAndTimestampUntouched() throws Exception {
        JSONObject original = report(); String frozen = original.toString();
        TelemetryCollector.Sample sample = sample(new Access());
        JSONObject merged = sample.mergeReport(original, NOW, 1000);
        assertEquals(frozen, original.toString()); assertEquals(original.getString("report_id"), merged.getString("report_id"));
        assertEquals(original.getString("reported_at"), merged.getString("reported_at")); assertTrue(merged.getBoolean("status_only"));
        assertFalse(merged.has("evidence"));
        merged.getJSONObject("gps").put("lat", 1);
        assertEquals(0, sample.reportFields().getJSONObject("gps").getDouble("lat"), 0);
        try { sample.mergeReport(merged, NOW, 1000); fail("不能重采样改写pending"); }
        catch (IllegalArgumentException expected) { assertEquals("TELEMETRY_ALREADY_PRESENT", expected.getMessage()); }
    }
    @Test public void snapshotExpiryAndBackwardsClockClearAllFields() throws Exception {
        TelemetryCollector.Sample sample = sample(new Access());
        assertFalse(sample.mergeReport(report(), NOW + 1000, 1000).isNull("gps"));
        for (long now : new long[]{NOW + 1001, NOW - 1}) {
            JSONObject merged = sample.mergeReport(report(), now, 1000);
            assertTrue(merged.isNull("gps")); assertTrue(merged.isNull("battery")); assertTrue(merged.isNull("charging"));
            assertEquals("snapshot_stale", merged.getString("location_reason"));
        }
    }
    @Test public void fixCanExpireWhileSnapshotIsStillFresh() throws Exception {
        Access access = new Access(); access.fix = fix(0, 0, 1f, "gps", NOW - 300000, ELAPSED - 300000000000L, false);
        JSONObject merged = sample(access).mergeReport(report(), NOW + 1, 1000);
        assertTrue(merged.isNull("gps")); assertEquals(50, merged.getInt("battery"));
    }
    @Test public void clockJumpDuringCollectionCannotProduceFreshReport() throws Exception {
        final Time time = new Time();
        Access access = new Access() {
            public TelemetryCollector.BatteryReading battery() { time.wall += 10000; return power; }
        };
        TelemetryCollector.Sample sample = new TelemetryCollector(access, time).collect(LIMITS);
        assertFalse(sample.toJson().getBoolean("clock_stable"));
        assertTrue(sample.mergeReport(report(), time.wall, 1000).isNull("battery"));
    }
    @Test public void budgetsAndMergeRequirementsAreEnforced() throws Exception {
        for (long[] values : new long[][]{{-1, 1}, {10001, 1}, {0, 0}, {0, 900001}}) {
            try { new TelemetryCollector.Limits(values[0], values[1]); fail("应拒绝预算"); }
            catch (IllegalArgumentException expected) { }
        }
        TelemetryCollector.Sample sample = sample(new Access());
        for (long age : new long[]{-1, 60001}) {
            try { sample.mergeReport(report(), NOW, age); fail("应拒绝快照预算"); }
            catch (IllegalArgumentException expected) { }
        }
        JSONObject invalid = report(); invalid.remove("reported_at");
        try { sample.mergeReport(invalid, NOW, 1000); fail("应拒绝无时间报告"); }
        catch (IllegalArgumentException expected) { }
    }
}
