package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import net.elfradio.d31bootstrap.telemetry.TelemetryCollector;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RemoteTelemetryTest {
    @Test public void uninitializedLocationSnapshotIsStableDetachedAndDoesNotStartCollector() throws Exception {
        try (RemoteTelemetry telemetry = new RemoteTelemetry()) {
            JSONObject snapshot = telemetry.locationSnapshot();
            String original = snapshot.toString();
            assertFalse(snapshot.getBoolean("gpsPresent"));
            assertEquals("not_sampled", snapshot.getString("location_reason"));
            snapshot.getJSONObject("radio").put("wifi_count", 99);
            assertEquals(original, telemetry.locationSnapshot().toString());
        }
    }
    private JSONObject report() throws Exception {
        return new JSONObject().put("report_id", "test-report").put("reported_at", "2026-09-11T00:00:00Z");
    }
    @Test public void stalledFrameworkDoesNotCreateRepeatedCollectorsOrMutateReport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (RemoteTelemetry telemetry = new RemoteTelemetry(0)) {
            JSONObject original = report();
            JSONObject first = telemetry.enrich(original, () -> {
                calls.incrementAndGet(); entered.countDown(); release.await(); return null;
            }, 0);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 4; i++) telemetry.enrich(report(), () -> {
                throw new AssertionError("阻塞期间不得派生第二个采集");
            }, 0);
            assertEquals(1, calls.get()); assertFalse(original.has("battery"));
            assertTrue(first.isNull("battery")); assertTrue(first.isNull("gps"));
            assertEquals("sampling_pending", first.getString("location_reason"));
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000) public void lateSuccessRemainsInSamePreparationAndUsesOriginalFuture() throws Exception {
        AtomicInteger calls = new AtomicInteger(), unexpected = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Callable<TelemetryCollector.Sample> replacement = () -> {
            unexpected.incrementAndGet(); throw new IllegalStateException("不应启动替代采集");
        };
        try (RemoteTelemetry telemetry = new RemoteTelemetry(5000)) {
            JSONObject original = report(); String before = original.toString();
            // 采样受闩锁控制，首次250毫秒等待期间绝不完成，不靠固定sleep制造竞态。
            try {
                telemetry.enrich(original, () -> {
                    calls.incrementAndGet(); entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放采样");
                    return freshSample();
                }, 250);
                fail("准备期限内不应冻结缺失报告");
            } catch (RemoteTelemetry.PreparationPending expected) { }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                telemetry.enrich(original, replacement, 0);
                fail("同一采集未完成时应继续准备");
            } catch (RemoteTelemetry.PreparationPending expected) { }
            assertEquals(before, original.toString()); assertEquals(1, calls.get());
            release.countDown();
            JSONObject result = awaitPrepared(telemetry, original, replacement);
            assertEquals(37, result.getInt("battery"));
            assertEquals("recent_cache", result.getString("location_reason"));
            assertEquals(0, result.getJSONObject("gps").getDouble("lat"), 0);
            assertEquals(original.getString("report_id"), result.getString("report_id"));
            assertEquals(original.getString("reported_at"), result.getString("reported_at"));
            assertEquals(before, original.toString());
            assertEquals(1, calls.get()); assertEquals(0, unexpected.get());
        } finally { release.countDown(); }
    }

    @Test(timeout = 10000) public void freshSuccessIsReusedWithoutStartingFailingReplacement() throws Exception {
        AtomicInteger calls = new AtomicInteger(), failures = new AtomicInteger();
        Callable<TelemetryCollector.Sample> collect = () -> { calls.incrementAndGet(); return freshSample(); };
        try (RemoteTelemetry telemetry = new RemoteTelemetry(5000)) {
            JSONObject first = awaitPrepared(telemetry, report(), collect);
            assertEquals(37, first.getInt("battery"));
            for (int i = 0; i < 3; i++) {
                JSONObject next = report().put("report_id", "test-report-next-" + i);
                String before = next.toString();
                JSONObject result = telemetry.enrich(next, () -> {
                    failures.incrementAndGet(); throw new SecurityException("不应替换新鲜成功结果");
                }, 250);
                assertEquals(37, result.getInt("battery"));
                assertTrue(result.getBoolean("charging"));
                assertEquals(first.getJSONObject("gps").getString("at"), result.getJSONObject("gps").getString("at"));
                assertEquals(next.getString("report_id"), result.getString("report_id"));
                assertEquals(before, next.toString());
            }
            assertEquals(1, calls.get()); assertEquals(0, failures.get());
        }
    }

    private static JSONObject awaitPrepared(RemoteTelemetry telemetry, JSONObject report,
                                            Callable<TelemetryCollector.Sample> collect) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            try { return telemetry.enrich(report, collect, 250); }
            catch (RemoteTelemetry.PreparationPending expected) { }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("受控采样释放后未在准备预算内返回");
    }

    private static TelemetryCollector.Sample freshSample() throws Exception {
        final long wall = System.currentTimeMillis(), elapsed = TimeUnit.SECONDS.toNanos(1);
        TelemetryCollector.Clock clock = new TelemetryCollector.Clock() {
            public long wallTimeMillis() { return wall; }
            public long elapsedRealtimeNanos() { return elapsed; }
        };
        TelemetryCollector.Access access = new TelemetryCollector.Access() {
            public TelemetryCollector.LocationReading location(TelemetryCollector.Limits limits, TelemetryCollector.Clock ignored) {
                TelemetryCollector.Fix fix = new TelemetryCollector.Fix(0, 0, 25f, "gps", wall, elapsed, false);
                return new TelemetryCollector.LocationReading(fix, "recent_cache", true);
            }
            public TelemetryCollector.BatteryReading battery() {
                return new TelemetryCollector.BatteryReading(37, 100, 2, 2, true);
            }
        };
        return new TelemetryCollector(access, clock).collect(new TelemetryCollector.Limits(0, 300000));
    }
    @Test public void failedCollectorReportsMissingDataAndCanRetry() throws Exception {
        try (RemoteTelemetry telemetry = new RemoteTelemetry()) {
            JSONObject result = telemetry.enrich(report(), () -> { throw new SecurityException("private"); }, 250);
            assertEquals("sampling_unavailable", result.getString("location_reason"));
            assertTrue(result.isNull("battery_present")); assertFalse(result.toString().contains("private"));
            AtomicInteger retried = new AtomicInteger();
            telemetry.enrich(report(), () -> { retried.incrementAndGet(); throw new IllegalStateException(); }, 250);
            assertEquals(1, retried.get());
        }
    }
}
