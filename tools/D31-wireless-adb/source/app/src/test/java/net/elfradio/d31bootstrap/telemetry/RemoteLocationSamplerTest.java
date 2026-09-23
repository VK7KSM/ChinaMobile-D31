package net.elfradio.d31bootstrap.telemetry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteLocationSamplerTest {
    private static final class Clock implements TelemetryCollector.Clock {
        volatile long wall = 1800000000000L, elapsed = 1000000000000L;
        public long wallTimeMillis() { return wall; }
        public long elapsedRealtimeNanos() { return elapsed; }
        void advance(long ms) { wall += ms; elapsed += ms * 1000000L; }
    }
    private static void finish(RemoteLocationSampler sampler) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sampler.isActive() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse("本轮应有界结束", sampler.isActive());
    }
    private static String radio(Clock clock) throws Exception {
        return new JSONObject().put("sampled_at_ms", clock.wall - 1000).put("wifiAccessPoints", new JSONArray()
                .put(new JSONObject().put("macAddress", "00:11:22:33:44:55").put("signalStrength", -45))
                .put(new JSONObject().put("macAddress", "00:11:22:33:44:66").put("signalStrength", -55)))
                .put("cellTowers", new JSONArray()).toString();
    }

    /** 先采后报：报告问一次就发起一轮，采完才放行；一分钟内的后续报告复用，不再扫。 */
    @Test(timeout = 5000) public void reportStartsOneRoundWaitsForItAndReusesItForAMinute() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            // 一轮只做无线观测：不开 GPS 监听窗口。
            assertEquals(0, window); assertTrue(includeRadio);
            if (reads.incrementAndGet() == 1) { entered.countDown(); release.await(); }
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true, radio(clock));
        }, clock)) {
            assertFalse("还没采过，报告要先等", sampler.readyForReport());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) assertFalse("本轮没完成前一直推迟，且不重复发起", sampler.readyForReport());
            release.countDown(); finish(sampler);
            assertTrue(sampler.readyForReport());
            assertEquals(1, reads.get());
            assertEquals(2, sampler.merge(new JSONObject().put("gps", JSONObject.NULL))
                    .getJSONObject("radio").getJSONArray("wifiAccessPoints").length());

            clock.advance(RemoteLocationSampler.REUSE_MS);
            assertTrue("一分钟内复用", sampler.readyForReport()); assertEquals(1, reads.get());

            clock.advance(1);
            assertFalse("过了复用窗口，下一份报告重新采", sampler.readyForReport());
            finish(sampler);
            assertTrue(sampler.readyForReport()); assertEquals(2, reads.get());
        } finally { release.countDown(); }
    }

    /** 采失败的一轮也算完成：报告照发，位置按原因如实上报，不因此反复重扫。 */
    @Test(timeout = 5000) public void failedRoundStillCompletesSoReportsAreNotHeld() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            reads.incrementAndGet(); throw new IllegalStateException("模拟定位服务不可用");
        }, clock)) {
            assertFalse(sampler.readyForReport()); finish(sampler);
            for (int i = 0; i < 20; i++) assertTrue(sampler.readyForReport());
            assertEquals(1, reads.get());
            JSONObject report = sampler.merge(new JSONObject().put("gps", JSONObject.NULL));
            assertTrue(report.isNull("gps")); assertTrue(report.isNull("radio"));
            assertEquals("cleanup_failed", report.getString("location_reason"));
        }
    }

    @Test(timeout = 5000) public void laterRoundWithoutFixKeepsFreshFixAndClockJumpInvalidatesRadio() throws Exception {
        Clock clock = new Clock(); AtomicInteger n = new AtomicInteger();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            if (n.incrementAndGet() == 1) return new TelemetryCollector.LocationReading(
                    new TelemetryCollector.Fix(1, 2, 10f, "gps", clock.wall, clock.elapsed, false), "recent_cache", true, radio(clock));
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true, radio(clock));
        }, clock)) {
            sampler.readyForReport(); finish(sampler);
            clock.advance(RemoteLocationSampler.REUSE_MS + 1);
            sampler.readyForReport(); finish(sampler); assertEquals(2, n.get());
            JSONObject report = new JSONObject().put("gps", JSONObject.NULL);
            assertEquals("没拿到新定位时不抹掉仍有效的旧定位", 1, sampler.merge(report).getJSONObject("gps").getDouble("lat"), 0);
            clock.wall += 5000;
            assertTrue(sampler.merge(report).isNull("radio"));
        }
    }

    @Test(timeout = 5000) public void closeInterruptsWorkerAndReleasesReports() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        RemoteLocationSampler sampler = new RemoteLocationSampler((window, radio) -> {
            reads.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); return null; }
            finally { exited.countDown(); }
        }, clock);
        assertFalse(sampler.readyForReport()); assertTrue(entered.await(2, TimeUnit.SECONDS)); sampler.close();
        assertTrue(exited.await(2, TimeUnit.SECONDS));
        assertTrue("关闭后不能再拖住报告", sampler.readyForReport());
        assertFalse(sampler.start()); assertEquals(1, reads.get());
    }

    /** 报告与照片报告取同一份已采观测；合并只读缓存，从不自己发起采样。 */
    @Test(timeout = 5000) public void cellOnlyObservationIsReusedByReportsUntilItExpires() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger();
        final long sampledAt = clock.wall - 1000;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            reads.incrementAndGet();
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true,
                    new JSONObject().put("sampled_at_ms", sampledAt).put("wifiAccessPoints", new JSONArray())
                            .put("radioType", "lte").put("cellTowers", new JSONArray()
                                    .put(RemoteLocationRadio.tower("lte", 123, 10, 505, 2, -70))).toString());
        }, clock)) {
            assertFalse(sampler.readyForReport()); finish(sampler);
            for (long age : new long[]{120001, 180000, 299999, 300000, 900000}) {
                clock.advance(age - (clock.wall - sampledAt));
                JSONObject report = new JSONObject().put("report_id", "media-" + age).put("gps", JSONObject.NULL);
                JSONObject result = sampler.merge(report);
                assertTrue(result.isNull("gps")); assertFalse(report.has("radio"));
                assertEquals(sampledAt, result.getJSONObject("radio").getLong("sampled_at_ms"));
                assertEquals("lte", result.getJSONObject("radio").getString("radioType"));
                assertEquals(1, result.getJSONObject("radio").getJSONArray("cellTowers").length());
            }
            assertEquals("合并不发起采样", 1, reads.get());
            clock.advance(1);
            assertTrue(sampler.merge(new JSONObject().put("gps", JSONObject.NULL)).isNull("radio"));
        }
    }

    @Test(timeout = 8000) public void singleApEvidenceSurvivesWithoutCoordinates() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger();
        long sampled = clock.wall - 1000;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            reads.incrementAndGet();
            JSONObject observation = new JSONObject(radio(clock)); observation.getJSONArray("wifiAccessPoints").remove(1);
            observation.put("wifi_raw_count", 8).put("wifi_valid_count", 1).put("wifi_scan_result", "results_updated")
                    .put("wifi_scan_wait_ms", 2000).put("wifi_newest_age_ms", 1000);
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true, observation.toString());
        }, clock)) {
            sampler.readyForReport(); finish(sampler);
            JSONObject status = sampler.snapshot().getJSONObject("radio");
            assertEquals(0, status.getInt("wifi_count")); assertEquals(1, status.getInt("wifi_valid_count"));
            assertEquals(8, status.getInt("wifi_raw_count")); assertFalse(status.getBoolean("usable"));
            assertFalse(status.toString().contains("00:11")); assertEquals(sampled, status.getLong("sampled_at_ms"));
            String frozen = status.toString();
            for (int i = 0; i < 100; i++) assertEquals(frozen, sampler.snapshot().getJSONObject("radio").toString());
            clock.advance(180000); assertEquals(frozen, sampler.snapshot().getJSONObject("radio").toString());
            clock.advance(720000);
            assertEquals(1, sampler.snapshot().getJSONObject("radio").getInt("wifi_valid_count"));
            assertFalse(sampler.snapshot().getJSONObject("radio").getBoolean("usable"));
            assertEquals(1, reads.get());
        }
    }

    @Test(timeout = 5000) public void snapshotIsPrivateStableDetachedAndNeverStartsSampling() throws Exception {
        Clock clock = new Clock(); AtomicInteger calls = new AtomicInteger();
        long sampledAt = clock.wall - 1000;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            calls.incrementAndGet();
            return new TelemetryCollector.LocationReading(new TelemetryCollector.Fix(12.345, 67.89, 10f, "gps",
                    clock.wall, clock.elapsed, false), "sampled", true,
                    new JSONObject(radio(clock)).put("wifi_reason", "observed").put("cell_reason", "no_fresh_cell_observation").toString());
        }, clock)) {
            for (int i = 0; i < 10; i++) assertFalse(sampler.snapshot().getBoolean("gpsPresent"));
            assertEquals(0, calls.get());
            sampler.readyForReport(); finish(sampler);
            JSONObject snapshot = sampler.snapshot();
            assertEquals(3, snapshot.length()); assertEquals(13, snapshot.getJSONObject("radio").length());
            assertTrue(snapshot.getBoolean("gpsPresent")); assertEquals("sampled", snapshot.getString("location_reason"));
            JSONObject detail = snapshot.getJSONObject("radio");
            assertEquals(sampledAt, detail.getLong("sampled_at_ms")); assertEquals(2, detail.getInt("wifi_count"));
            assertEquals(0, detail.getInt("cell_count")); assertTrue(detail.getBoolean("usable"));
            assertEquals("observed", detail.getString("wifi_reason"));
            assertEquals("no_fresh_cell_observation", detail.getString("cell_reason"));
            String frozen = snapshot.toString();
            for (String forbidden : new String[]{"12.345", "67.89", "00:11", "cellId", "macAddress", "lat", "lng"})
                assertFalse(frozen.contains(forbidden));
            for (int i = 0; i < 100; i++) assertEquals(frozen, sampler.snapshot().toString());
            detail.put("wifi_count", 99); assertEquals(frozen, sampler.snapshot().toString());
            clock.advance(180000); assertTrue(sampler.snapshot().getJSONObject("radio").getBoolean("usable"));
            clock.advance(720000);
            JSONObject expired = sampler.snapshot();
            assertFalse(expired.getBoolean("gpsPresent"));
            assertFalse(expired.getJSONObject("radio").getBoolean("usable"));
            assertEquals(0, expired.getJSONObject("radio").getInt("wifi_count"));
            assertEquals(sampledAt, expired.getJSONObject("radio").getLong("sampled_at_ms"));
            assertEquals(1, calls.get());
        }
    }
}
