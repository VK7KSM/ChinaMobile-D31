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
    @Test(timeout = 5000) public void radioArrivesBeforeGpsAndWakeDoesNotStartNewScan() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger(), wakes = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), release = new CountDownLatch(1);
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            int call = reads.incrementAndGet();
            if (call == 1) {
                assertEquals(0, window); assertTrue(includeRadio);
                return new TelemetryCollector.LocationReading(null, "no_cached_location", true, radio(clock));
            }
            assertEquals(45000, window); assertFalse(includeRadio);
            release.await();
            return new TelemetryCollector.LocationReading(new TelemetryCollector.Fix(0, 0, 12f, "gps", clock.wall, clock.elapsed, false), "sampled", true);
        }, clock, () -> { if (wakes.incrementAndGet() == 1) first.countDown(); else second.countDown(); })) {
            assertTrue(sampler.tick()); assertTrue(first.await(2, TimeUnit.SECONDS));
            JSONObject before = new JSONObject().put("report_id", "one").put("gps", JSONObject.NULL);
            JSONObject partial = sampler.merge(before);
            assertEquals(2, partial.getJSONObject("radio").getJSONArray("wifiAccessPoints").length());
            assertTrue(partial.isNull("gps")); assertFalse(before.has("radio"));
            for (int i = 0; i < 100; i++) assertFalse(sampler.tick());
            release.countDown(); assertTrue(second.await(2, TimeUnit.SECONDS));
            assertEquals("gps", sampler.merge(before).getJSONObject("gps").getString("provider"));
            assertFalse(sampler.tick()); assertEquals(2, reads.get()); assertEquals(2, wakes.get());
            long at = partial.getJSONObject("radio").getLong("sampled_at_ms");
            assertEquals(at, sampler.merge(before).getJSONObject("radio").getLong("sampled_at_ms"));
            clock.advance(120001);
            assertEquals(at, sampler.merge(before).getJSONObject("radio").getLong("sampled_at_ms")); assertFalse(sampler.tick());
            clock.advance(180001);
            assertTrue(sampler.merge(before).isNull("gps"));
        } finally { release.countDown(); }
    }
    @Test(timeout = 5000) public void failedActiveSampleCannotEraseFreshGpsAndClockJumpInvalidatesRadio() throws Exception {
        Clock clock = new Clock(); CountDownLatch done = new CountDownLatch(1); AtomicInteger n = new AtomicInteger();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            if (n.incrementAndGet() == 1) return new TelemetryCollector.LocationReading(
                    new TelemetryCollector.Fix(1, 2, 10f, "gps", clock.wall, clock.elapsed, false), "recent_cache", true, radio(clock));
            done.countDown(); return new TelemetryCollector.LocationReading(null, "timeout", true);
        }, clock, () -> { })) {
            sampler.tick(); assertTrue(done.await(2, TimeUnit.SECONDS));
            JSONObject report = new JSONObject().put("gps", JSONObject.NULL);
            assertEquals(1, sampler.merge(report).getJSONObject("gps").getDouble("lat"), 0);
            clock.wall += 5000;
            assertTrue(sampler.merge(report).isNull("radio"));
        }
    }
    @Test(timeout = 5000) public void closeInterruptsWorkerAndPreventsNewTicksAndLateNotification() throws Exception {
        Clock clock = new Clock(); AtomicInteger wakes = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1);
        RemoteLocationSampler sampler = new RemoteLocationSampler((window, radio) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return null; }
            finally { exited.countDown(); }
        }, clock, wakes::incrementAndGet);
        sampler.tick(); assertTrue(entered.await(2, TimeUnit.SECONDS)); sampler.close();
        assertTrue(exited.await(2, TimeUnit.SECONDS)); assertFalse(sampler.tick()); assertEquals(0, wakes.get());
    }

    @Test(timeout = 5000) public void cellOnlyMediaReportsKeepFrozenObservationBetweenSamplingRounds() throws Exception {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger(), wakes = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        final long sampledAt = clock.wall - 1000;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            reads.incrementAndGet();
            if (includeRadio) return new TelemetryCollector.LocationReading(null, "no_cached_location", true,
                    new JSONObject().put("sampled_at_ms", sampledAt).put("wifiAccessPoints", new JSONArray())
                            .put("radioType", "lte").put("cellTowers", new JSONArray()
                                    .put(RemoteLocationRadio.tower("lte", 123, 10, 505, 2, -70))).toString());
            return new TelemetryCollector.LocationReading(null, "timeout", true);
        }, clock, () -> { if (wakes.incrementAndGet() == 1) completed.countDown(); })) {
            assertTrue(sampler.tick()); assertTrue(completed.await(2, TimeUnit.SECONDS)); finish(sampler);
            for (long age : new long[]{120001, 180000, 299999, 300000, 900000}) {
                clock.advance(age - (clock.wall - sampledAt));
                JSONObject report = new JSONObject().put("report_id", "media-" + age).put("gps", JSONObject.NULL);
                JSONObject result = sampler.merge(report);
                assertTrue(result.isNull("gps")); assertFalse(report.has("radio"));
                assertEquals(sampledAt, result.getJSONObject("radio").getLong("sampled_at_ms"));
                assertEquals("lte", result.getJSONObject("radio").getString("radioType"));
                assertEquals(1, result.getJSONObject("radio").getJSONArray("cellTowers").length());
                if (age < 300000) assertFalse(sampler.tick());
            }
            assertEquals(2, reads.get());
            assertEquals("没有定位结果的一轮只唤醒一次", 1, wakes.get());
            clock.advance(1);
            assertTrue(sampler.merge(new JSONObject().put("gps", JSONObject.NULL)).isNull("radio"));
        }
    }

    @Test(timeout = 5000) public void singleApEvidenceSurvivesWithoutCoordinatesOrExtraWakes() throws Exception {
        Clock clock = new Clock(); CountDownLatch done = new CountDownLatch(1); AtomicInteger wakes = new AtomicInteger();
        long sampled = clock.wall - 1000;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            if (!includeRadio) return new TelemetryCollector.LocationReading(null, "timeout", true);
            JSONObject observation = new JSONObject(radio(clock)); observation.getJSONArray("wifiAccessPoints").remove(1);
            observation.put("wifi_raw_count", 8).put("wifi_valid_count", 1).put("wifi_scan_result", "results_updated")
                    .put("wifi_scan_wait_ms", 2000).put("wifi_newest_age_ms", 1000);
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true, observation.toString());
        }, clock, () -> { if (wakes.incrementAndGet() == 1) done.countDown(); })) {
            sampler.tick(); assertTrue(done.await(2, TimeUnit.SECONDS));
            JSONObject status = sampler.snapshot().getJSONObject("radio");
            assertEquals(0, status.getInt("wifi_count")); assertEquals(1, status.getInt("wifi_valid_count"));
            assertEquals(8, status.getInt("wifi_raw_count")); assertFalse(status.getBoolean("usable"));
            assertFalse(status.toString().contains("00:11")); assertEquals(sampled, status.getLong("sampled_at_ms"));
            String frozen = status.toString();
            for (int i = 0; i < 100; i++) assertEquals(frozen, sampler.snapshot().getJSONObject("radio").toString());
            clock.advance(180000); assertFalse(sampler.tick()); assertEquals(frozen, sampler.snapshot().getJSONObject("radio").toString());
            assertEquals(1, wakes.get()); clock.advance(720000);
            assertEquals(1, sampler.snapshot().getJSONObject("radio").getInt("wifi_valid_count"));
            assertFalse(sampler.snapshot().getJSONObject("radio").getBoolean("usable"));
        }
    }
    @Test(timeout = 5000) public void snapshotIsPrivateStableDetachedAndNeverStartsSampling() throws Exception {
        Clock clock = new Clock(); AtomicInteger calls = new AtomicInteger(), wakes = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        long sampledAt = clock.wall - 1000;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            calls.incrementAndGet();
            if (includeRadio) return new TelemetryCollector.LocationReading(null, "no_cached_location", true,
                    new JSONObject(radio(clock)).put("wifi_reason", "observed").put("cell_reason", "no_fresh_cell_observation").toString());
            return new TelemetryCollector.LocationReading(new TelemetryCollector.Fix(12.345, 67.89, 10f, "gps",
                    clock.wall, clock.elapsed, false), "sampled", true);
        }, clock, () -> { if (wakes.incrementAndGet() == 2) done.countDown(); })) {
            for (int i = 0; i < 10; i++) assertFalse(sampler.snapshot().getBoolean("gpsPresent"));
            assertEquals(0, calls.get());
            sampler.tick(); assertTrue(done.await(2, TimeUnit.SECONDS));
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
            assertEquals(2, calls.get()); assertEquals(2, wakes.get());
        }
    }

    private static JSONObject ap(String mac, int level) throws Exception {
        return new JSONObject().put("macAddress", mac).put("signalStrength", level);
    }

    /** 无线快照按身份比较：时钟和信号在动，地方没动。 */
    @Test public void samePlaceIgnoresTimestampAndSignalButNotTheAccessPointSetOrCell() throws Exception {
        JSONObject base = new JSONObject().put("sampled_at_ms", 1800000000000L)
                .put("wifiAccessPoints", new JSONArray().put(ap("00:11:22:33:44:01", -40))
                        .put(ap("00:11:22:33:44:02", -50)).put(ap("00:11:22:33:44:03", -60)))
                .put("cellTowers", new JSONArray());
        JSONObject later = new JSONObject(base.toString()).put("sampled_at_ms", 1800000600000L);
        later.getJSONArray("wifiAccessPoints").getJSONObject(0).put("signalStrength", -71);
        assertTrue("时钟走动与信号抖动不是位移", RemoteLocationSampler.samePlace(base.toString(), later.toString()));

        // validated() 按信号强度取前六个，边缘AP在第六第七名之间来回不代表设备动了。
        JSONObject edge = new JSONObject(base.toString());
        edge.getJSONArray("wifiAccessPoints").put(2, ap("00:11:22:33:44:09", -60));
        assertTrue(RemoteLocationSampler.samePlace(base.toString(), edge.toString()));

        // 整组BSSID换掉，或只剩一个重合，都算换了地方。
        assertFalse(RemoteLocationSampler.samePlace(base.toString(), new JSONObject(base.toString())
                .put("wifiAccessPoints", new JSONArray().put(ap("aa:bb:cc:dd:ee:01", -40))
                        .put(ap("aa:bb:cc:dd:ee:02", -50))).toString()));
        assertFalse(RemoteLocationSampler.samePlace(base.toString(), new JSONObject(base.toString())
                .put("wifiAccessPoints", new JSONArray().put(ap("00:11:22:33:44:01", -40))
                        .put(ap("aa:bb:cc:dd:ee:02", -50))).toString()));

        // Wi-Fi不变但基站变了，同样算换了地方。
        JSONObject cellA = new JSONObject(base.toString()).put("radioType", "lte")
                .put("cellTowers", new JSONArray().put(RemoteLocationRadio.tower("lte", 123, 10, 505, 2, -70)));
        JSONObject cellB = new JSONObject(base.toString()).put("radioType", "lte")
                .put("cellTowers", new JSONArray().put(RemoteLocationRadio.tower("lte", 456, 10, 505, 2, -70)));
        assertFalse(RemoteLocationSampler.samePlace(cellA.toString(), cellB.toString()));
        assertFalse(RemoteLocationSampler.samePlace(cellA.toString(), base.toString()));
        assertTrue(RemoteLocationSampler.samePlace(cellA.toString(),
                new JSONObject(cellA.toString()).put("sampled_at_ms", 1800000600000L).toString()));

        // 集合完全相同就是同一地点，不依赖 validated() 的「少于两个AP整组丢弃」那条规则。
        // 若哪天它改成允许单个AP，「重合两个以上」会把相同的单AP判成换了地方，退回每轮上报。
        JSONObject single = new JSONObject(base.toString())
                .put("wifiAccessPoints", new JSONArray().put(ap("00:11:22:33:44:01", -40)));
        assertTrue("同一个AP不算换地方", RemoteLocationSampler.samePlace(single.toString(),
                new JSONObject(single.toString()).put("sampled_at_ms", 1800000600000L).toString()));
        assertFalse(RemoteLocationSampler.samePlace(single.toString(), new JSONObject(base.toString())
                .put("wifiAccessPoints", new JSONArray().put(ap("aa:bb:cc:dd:ee:01", -40))).toString()));

        assertFalse("第一份观测必须上报", RemoteLocationSampler.samePlace(null, base.toString()));
        assertFalse(RemoteLocationSampler.samePlace(base.toString(), null));
        assertTrue(RemoteLocationSampler.samePlace(null, null));
        assertFalse("读不出来的快照按变化处理", RemoteLocationSampler.samePlace(base.toString(), "not json"));
    }

    /** 桌机一直摆在原处：反复采样不得把十五分钟的报告节奏压成每轮一次。 */
    @Test(timeout = 10000) public void stationaryRoundsWakeOnlyOnceNoMatterHowOftenTheyResample() throws Exception {
        Clock clock = new Clock(); AtomicInteger wakes = new AtomicInteger(), rounds = new AtomicInteger();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            if (!includeRadio) return new TelemetryCollector.LocationReading(null, "timeout", true);
            JSONObject observation = new JSONObject(radio(clock));
            int round = rounds.incrementAndGet();
            JSONArray points = observation.getJSONArray("wifiAccessPoints");
            for (int i = 0; i < points.length(); i++) points.getJSONObject(i).put("signalStrength", -45 - i - round % 5);
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true, observation.toString());
        }, clock, wakes::incrementAndGet)) {
            for (int round = 0; round < 12; round++) {
                assertTrue(sampler.tick()); finish(sampler);
                clock.advance(RemoteLocationSampler.INTERVAL_MS);
            }
            assertEquals(12, rounds.get());
            assertEquals("静止设备只在第一份观测时唤醒一次", 1, wakes.get());
        }
    }

    /** 真换了地方仍要立刻上报，不能等下一次常规报告。 */
    @Test(timeout = 10000) public void movingToANewAccessPointSetWakesWithoutWaitingForTheNextReport() throws Exception {
        Clock clock = new Clock(); AtomicInteger wakes = new AtomicInteger(), rounds = new AtomicInteger();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler((window, includeRadio) -> {
            if (!includeRadio) return new TelemetryCollector.LocationReading(null, "timeout", true);
            JSONObject observation = new JSONObject(radio(clock));
            if (rounds.incrementAndGet() >= 3) observation.put("wifiAccessPoints", new JSONArray()
                    .put(ap("aa:bb:cc:dd:ee:01", -42)).put(ap("aa:bb:cc:dd:ee:02", -52)));
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true, observation.toString());
        }, clock, wakes::incrementAndGet)) {
            for (int round = 0; round < 4; round++) {
                assertTrue(sampler.tick()); finish(sampler);
                assertEquals("第三轮换了整组AP才该再唤醒一次", round < 2 ? 1 : 2, wakes.get());
                clock.advance(RemoteLocationSampler.INTERVAL_MS);
            }
        }
    }
}
