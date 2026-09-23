package net.elfradio.d31bootstrap.telemetry;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 从首次cell到GPS再到纯缓存Wi-Fi的真实调度回放，不连接设备。 */
public class RemoteLocationCacheRecheckTest {
    private static final long START = 1000000000000L, WALL = 1800000000000L;
    private static class Clock implements TelemetryCollector.Clock {
        volatile long elapsed = START, wall = WALL;
        public long elapsedRealtimeNanos() { return elapsed; }
        public long wallTimeMillis() { return wall; }
        void advance(long ms) { elapsed += ms * 1000000L; wall += ms; }
    }
    private static class Scenario implements RemoteLocationSampler.Source, RemoteLocationWifi.Access {
        final Clock clock = new Clock();
        final CountDownLatch gpsEntered = new CountDownLatch(1), gpsRelease = new CountDownLatch(1);
        int starts, sleeps, reads, cacheReads, aps = 6;
        String initialResult = "no_fresh_results_before_deadline";
        boolean gpsFix, missingCache, throwCache, busyOnce;
        long gpsMs = 45000;
        public long nowNanos() { return clock.elapsed; }
        public boolean enabled() { return true; }
        public boolean start() { starts++; return true; }
        public void sleep(long ms) { sleeps++; clock.advance(ms); }
        public List<RemoteLocationWifi.Scan> results() {
            List<RemoteLocationWifi.Scan> result = new ArrayList<RemoteLocationWifi.Scan>();
            long at = clock.elapsed - START < 3928000000L ? START - 213480000000L : START + 3928000000L;
            for (int i = 0; i < aps; i++) result.add(new RemoteLocationWifi.Scan(
                    String.format(Locale.US, "00:11:22:33:44:%02x", i), -45 - i, at / 1000));
            return result;
        }
        private String observation(RemoteLocationWifi.Result scan, boolean cell) throws Exception {
            JSONArray rows = new JSONArray();
            long oldest = cell ? clock.elapsed : Long.MAX_VALUE;
            for (RemoteLocationWifi.Scan row : scan.valid) {
                rows.put(new JSONObject().put("macAddress", row.mac).put("signalStrength", row.level));
                oldest = Math.min(oldest, row.timestampMicros * 1000L);
            }
            return new JSONObject().put("sampled_at_ms", oldest == Long.MAX_VALUE ? clock.wall : clock.wall - (clock.elapsed - oldest) / 1000000)
                    .put("wifiAccessPoints", rows).put("cellTowers", cell ? new JSONArray().put(RemoteLocationRadio.tower("lte", 123, 10, 505, 2, -70)) : new JSONArray())
                    .put("radioType", "lte").put("wifi_scan_result", scan.scanResult).put("wifi_raw_count", scan.rawCount)
                    .put("wifi_valid_count", scan.validCount).put("wifi_scan_wait_ms", scan.waitMs).toString();
        }
        public TelemetryCollector.LocationReading read(long window, boolean radio) throws Exception {
            reads++;
            if (radio) {
                assertEquals(0, window);
                JSONObject value = new JSONObject(observation(RemoteLocationWifi.capture(this), true));
                value.put("wifi_scan_result", initialResult);
                return new TelemetryCollector.LocationReading(null, "no_cached_location", true, value.toString());
            }
            assertEquals(45000, window); gpsEntered.countDown(); gpsRelease.await(); clock.advance(gpsMs);
            return new TelemetryCollector.LocationReading(gpsFix ? new TelemetryCollector.Fix(1, 2, 10f, "gps", clock.wall, clock.elapsed, false) : null,
                    gpsFix ? "sampled" : "timeout", true);
        }
        public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
            cacheReads++;
            if (busyOnce && cacheReads == 1) return new TelemetryCollector.LocationReading(null, "app_cache_busy", true);
            if (throwCache) throw new IllegalStateException("模拟缓存读取失败");
            if (missingCache) return null;
            return new TelemetryCollector.LocationReading(null, "no_cached_location", true,
                    observation(RemoteLocationWifi.capture(this, false), false));
        }
    }
    private static void finish(RemoteLocationSampler sampler) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sampler.isActive() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse("本轮应有界结束", sampler.isActive());
    }
    @Test(timeout = 8000) public void cellFirstThenGpsThenLateWifiWithOneScan() throws Exception {
        Scenario s = new Scenario(); s.gpsFix = true;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            assertTrue(sampler.tick()); assertTrue(s.gpsEntered.await(2, TimeUnit.SECONDS));
            JSONObject before = sampler.merge(new JSONObject().put("gps", JSONObject.NULL));
            assertEquals(1, before.getJSONObject("radio").getJSONArray("cellTowers").length());
            assertTrue(before.isNull("gps"));
            for (int i = 0; i < 50; i++) { assertFalse(sampler.tick()); sampler.snapshot(); sampler.merge(before); }
            s.gpsRelease.countDown(); finish(sampler);
            JSONObject after = sampler.merge(new JSONObject().put("gps", JSONObject.NULL));
            assertEquals("gps", after.getJSONObject("gps").getString("provider"));
            assertEquals(6, after.getJSONObject("radio").getJSONArray("wifiAccessPoints").length());
            assertEquals(WALL + 2000, after.getJSONObject("radio").getLong("sampled_at_ms"));
            assertEquals(1, after.getJSONObject("radio").getJSONArray("cellTowers").length());
            assertEquals(123, after.getJSONObject("radio").getJSONArray("cellTowers").getJSONObject(0).getInt("cellId"));
            assertEquals("lte", after.getJSONObject("radio").getString("radioType"));
            assertEquals("passive_cache", after.getJSONObject("radio").getString("wifi_scan_result"));
            assertEquals(1, s.starts); assertEquals(4, s.sleeps); assertEquals(1, s.cacheReads); assertEquals(2, s.reads);
            assertFalse(sampler.tick());
            String status = sampler.snapshot().getJSONObject("radio_cache_recheck").toString();
            assertFalse(status.contains("macAddress")); assertFalse(status.contains("00:11"));
            for (int i = 0; i < 100; i++) sampler.merge(after);
            assertEquals(1, s.cacheReads);
        } finally { s.gpsRelease.countDown(); }
    }
    private static TelemetryCollector.LocationReading passiveWifi(long at, String cellType, int cellId) throws Exception {
        JSONArray wifi = new JSONArray();
        for (int i = 0; i < 2; i++) wifi.put(new JSONObject().put("macAddress", "00:11:22:33:44:0" + i).put("signalStrength", -50 - i));
        JSONObject value = new JSONObject().put("sampled_at_ms", at).put("wifiAccessPoints", wifi)
                .put("cellTowers", cellType == null ? new JSONArray() : new JSONArray().put(RemoteLocationRadio.tower(cellType, cellId, 11, 505, 2, -65)))
                .put("wifi_scan_result", "passive_cache").put("wifi_valid_count", 2).put("wifi_raw_count", 2)
                .put("cell_reason", cellType == null ? "no_fresh_cell_observation" : "observed");
        if (cellType != null) value.put("radioType", cellType);
        return new TelemetryCollector.LocationReading(null, "no_cached_location", true, value.toString());
    }
    @Test(timeout = 8000) public void retainedCellsUse120SecondGateNotFifteenMinuteReportLifetime() throws Exception {
        for (long age : new long[]{120000, 120001}) {
            Scenario s = new Scenario() {
                @Override public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                    cacheReads++; return passiveWifi(clock.wall - 1000, null, 0);
                }
            };
            s.gpsMs = age; s.gpsRelease.countDown();
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                sampler.tick(); finish(sampler);
                JSONObject radio = sampler.merge(new JSONObject()).getJSONObject("radio");
                assertEquals(2, radio.getJSONArray("wifiAccessPoints").length());
                assertEquals(age == 120000 ? 1 : 0, radio.getJSONArray("cellTowers").length());
                assertEquals(age == 120000 ? WALL + 2000 : s.clock.wall - 1000, radio.getLong("sampled_at_ms"));
                assertEquals(age == 120000, radio.has("radioType"));
                if (age == 120000) {
                    s.clock.advance(900001 - age);
                    assertTrue("合并不能延长旧观测原报告期限", sampler.merge(new JSONObject()).isNull("radio"));
                }
                assertEquals(1, s.cacheReads); assertEquals(1, s.starts);
            }
        }
    }
    @Test(timeout = 8000) public void newCellAndRadioTypeAlwaysWinWithoutBorrowingOldTimestamp() throws Exception {
        final String[] incoming = new String[1];
        Scenario s = new Scenario() {
            @Override public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                cacheReads++; TelemetryCollector.LocationReading value = passiveWifi(clock.wall - 1000, "wcdma", 456);
                incoming[0] = value.radio; return value;
            }
        };
        s.gpsRelease.countDown();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            sampler.tick(); finish(sampler);
            JSONObject radio = sampler.merge(new JSONObject()).getJSONObject("radio");
            assertEquals("wcdma", radio.getString("radioType")); assertEquals(1, radio.getJSONArray("cellTowers").length());
            assertEquals(456, radio.getJSONArray("cellTowers").getJSONObject(0).getInt("cellId"));
            assertEquals(s.clock.wall - 1000, radio.getLong("sampled_at_ms"));
            assertEquals(456, new JSONObject(incoming[0]).getJSONArray("cellTowers").getJSONObject(0).getInt("cellId"));
        }
    }
    @Test(timeout = 8000) public void combinedTimestampKeepsOlderIncomingWifiToo() throws Exception {
        Scenario s = new Scenario() {
            @Override public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                cacheReads++; return passiveWifi(WALL + 1000, null, 0);
            }
        };
        s.gpsRelease.countDown();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            sampler.tick(); finish(sampler);
            JSONObject radio = sampler.merge(new JSONObject()).getJSONObject("radio");
            assertEquals(1, radio.getJSONArray("cellTowers").length());
            assertEquals(WALL + 1000, radio.getLong("sampled_at_ms"));
            assertEquals("observed", radio.getString("cell_reason"));
        }
    }
    @Test(timeout = 8000) public void wallJumpOrMonotonicRollbackCannotCarryOldCellIntoFreshWifi() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int fault = mode;
            Scenario s = new Scenario() {
                @Override public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                    cacheReads++;
                    if (fault == 0) clock.wall += 5000;
                    if (fault == 1) clock.elapsed = START;
                    if (fault == 2) clock.wall = WALL;
                    return passiveWifi(clock.wall - 1000, null, 0);
                }
            };
            s.gpsRelease.countDown();
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                sampler.tick(); finish(sampler);
                JSONObject radio = sampler.merge(new JSONObject()).getJSONObject("radio");
                assertEquals(2, radio.getJSONArray("wifiAccessPoints").length());
                assertEquals(0, radio.getJSONArray("cellTowers").length()); assertFalse(radio.has("radioType"));
                assertEquals(s.clock.wall - 1000, radio.getLong("sampled_at_ms"));
            }
        }
    }
    @Test(timeout = 8000) public void gpsTimeoutStillRecoversWifiInSameRound() throws Exception {
        Scenario s = new Scenario(); s.gpsRelease.countDown();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            sampler.tick(); finish(sampler);
            assertEquals("timeout", sampler.snapshot().getString("location_reason"));
            assertEquals(6, sampler.snapshot().getJSONObject("radio").getInt("wifi_count"));
            assertEquals(1, s.starts); assertEquals(1, s.cacheReads);
        }
    }
    @Test(timeout = 8000) public void unsuccessfulPassiveReadDoesNotEraseCellOrGpsOutcome() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Scenario s = new Scenario(); s.aps = mode == 0 ? 1 : 0; s.missingCache = mode == 2; s.throwCache = mode == 3;
            s.gpsRelease.countDown();
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                sampler.tick(); finish(sampler);
                JSONObject status = sampler.snapshot();
                assertEquals(1, status.getJSONObject("radio").getInt("cell_count"));
                assertEquals("timeout", status.getString("location_reason"));
                assertEquals(1, s.cacheReads); assertEquals(1, s.starts);
                if (mode == 0) assertEquals(1, status.getJSONObject("radio_cache_recheck").getInt("wifi_valid_count"));
            }
        }
    }
    @Test(timeout = 8000) public void otherScanReasonsNeverScheduleCacheRecheck() throws Exception {
        for (String reason : new String[]{"cached_results", "request_rejected", "permission_denied", "wifi_disabled"}) {
            Scenario s = new Scenario(); s.initialResult = reason; s.gpsRelease.countDown();
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                sampler.tick(); finish(sampler); assertEquals(0, s.cacheReads);
                assertFalse(sampler.snapshot().has("radio_cache_recheck"));
            }
        }
    }
    @Test(timeout = 8000) public void partialUpdatedScanGetsOnePassiveReadButCompleteScanDoesNot() throws Exception {
        for (int points : new int[]{0, 1, 2}) {
            Scenario s = new Scenario() {
                @Override public List<RemoteLocationWifi.Scan> results() {
                    int count = reads == 1 ? points : 6;
                    List<RemoteLocationWifi.Scan> result = new ArrayList<>();
                    for (int i = 0; i < count; i++) result.add(new RemoteLocationWifi.Scan(
                            String.format(Locale.US, "00:11:22:33:44:%02x", i), -50 - i, clock.elapsed / 1000));
                    return result;
                }
            };
            s.initialResult = "results_updated"; s.gpsRelease.countDown();
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                sampler.tick(); finish(sampler);
                assertEquals(points < 2 ? 1 : 0, s.cacheReads);
                assertEquals(points < 2 ? 6 : 2, sampler.snapshot().getJSONObject("radio").getInt("wifi_count"));
            }
        }
    }

    private static class TwoRounds implements RemoteLocationSampler.Source {
        final Clock clock = new Clock();
        final CountDownLatch gpsEntered = new CountDownLatch(1), gpsRelease = new CountDownLatch(1);
        final CountDownLatch cacheEntered = new CountDownLatch(1), cacheRelease = new CountDownLatch(1);
        int rounds, cacheReads;
        long initialAt;
        boolean gpsFix, gpsFailure, initialMissing, cacheFailure, clockJump;
        String scanResult = "no_fresh_results_before_deadline";
        public TelemetryCollector.LocationReading read(long window, boolean includeRadio) throws Exception {
            if (includeRadio) {
                rounds++;
                if (rounds == 1) return passiveWifi(clock.wall, "lte", 900);
                if (initialMissing) return new TelemetryCollector.LocationReading(null, "provider_unavailable", true);
                initialAt = clock.wall;
                JSONObject cell = new JSONObject(passiveWifi(initialAt, "lte", 123).radio)
                        .put("wifiAccessPoints", new JSONArray()).put("wifi_valid_count", 0)
                        .put("wifi_scan_result", scanResult).put("wifi_reason",
                                "wifi_disabled".equals(scanResult) ? "wifi_disabled" : "insufficient_fresh_access_points");
                return new TelemetryCollector.LocationReading(null, "no_cached_location", true, cell.toString());
            }
            if (rounds == 1) return new TelemetryCollector.LocationReading(null, "timeout", true);
            gpsEntered.countDown(); gpsRelease.await(); clock.advance(45000);
            if (clockJump) clock.wall += 5000;
            if (gpsFailure) throw new IllegalStateException("模拟GPS读取失败");
            return new TelemetryCollector.LocationReading(gpsFix ? new TelemetryCollector.Fix(1, 2, 10f,
                    "gps", clock.wall, clock.elapsed, false) : null, gpsFix ? "sampled" : "timeout", true);
        }
        public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
            cacheReads++; cacheEntered.countDown(); cacheRelease.await();
            if (cacheFailure) throw new IllegalStateException("模拟被动读取失败");
            return passiveWifi(clock.wall - 1000, null, 0);
        }
        void release() { gpsRelease.countDown(); cacheRelease.countDown(); }
    }

    private static String startSecondRound(TwoRounds s, RemoteLocationSampler sampler, long age) throws Exception {
        assertTrue(sampler.tick()); finish(sampler);
        String previous = sampler.merge(new JSONObject()).getJSONObject("radio").toString();
        s.clock.advance(age); assertTrue(sampler.tick()); assertTrue(s.gpsEntered.await(2, TimeUnit.SECONDS));
        return previous;
    }

    @Test(timeout = 8000) public void mediaReadsKeepPreviousWifiUntilLateWifiArrives() throws Exception {
        TwoRounds s = new TwoRounds(); s.gpsFix = true;
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            String previous = startSecondRound(s, sampler, 300000);
            for (int i = 0; i < 20; i++) {
                JSONObject report = new JSONObject().put("report_id", "media-" + i).put("gps", JSONObject.NULL);
                assertEquals(previous, sampler.merge(report).getJSONObject("radio").toString());
                assertFalse(report.has("radio")); assertFalse(sampler.tick());
            }
            // 被动读已经进入，说明 GPS 那一段早已发布完毕。
            s.gpsRelease.countDown(); assertTrue(s.cacheEntered.await(2, TimeUnit.SECONDS));
            JSONObject duringCache = sampler.merge(new JSONObject());
            assertEquals("gps", duringCache.getJSONObject("gps").getString("provider"));
            assertEquals(previous, duringCache.getJSONObject("radio").toString());
            s.cacheRelease.countDown(); finish(sampler);
            JSONObject after = sampler.merge(new JSONObject()).getJSONObject("radio");
            assertEquals(2, after.getJSONArray("wifiAccessPoints").length());
            assertEquals(123, after.getJSONArray("cellTowers").getJSONObject(0).getInt("cellId"));
            assertEquals(s.initialAt, after.getLong("sampled_at_ms"));
            assertEquals(1, s.cacheReads); assertEquals(2, s.rounds);
        } finally { s.release(); }
    }

    @Test(timeout = 8000) public void failedOrRejectedRoundDowngradesWithoutRenewingCellTime() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            TwoRounds s = new TwoRounds(); s.cacheFailure = mode == 0; s.gpsFailure = mode == 1;
            if (mode == 2) s.scanResult = "request_rejected";
            if (mode == 3) s.initialMissing = true;
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                String previous = startSecondRound(s, sampler, 300000);
                assertEquals(previous, sampler.merge(new JSONObject()).getJSONObject("radio").toString());
                s.release(); finish(sampler);
                JSONObject result = sampler.merge(new JSONObject());
                if (s.initialMissing) assertTrue(result.isNull("radio"));
                else {
                    JSONObject radio = result.getJSONObject("radio");
                    assertEquals(0, radio.getJSONArray("wifiAccessPoints").length());
                    assertEquals(s.initialAt, radio.getLong("sampled_at_ms"));
                    assertEquals(123, radio.getJSONArray("cellTowers").getJSONObject(0).getInt("cellId"));
                }
            } finally { s.release(); }
        }
    }

    @Test(timeout = 8000) public void disabledWifiDowngradesImmediatelyWithoutPassiveRead() throws Exception {
        TwoRounds s = new TwoRounds(); s.scanResult = "wifi_disabled";
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            startSecondRound(s, sampler, 300000);
            assertEquals(0, sampler.merge(new JSONObject()).getJSONObject("radio").getJSONArray("wifiAccessPoints").length());
            s.release(); finish(sampler); assertEquals(0, s.cacheReads);
        } finally { s.release(); }
    }

    @Test(timeout = 8000) public void heldWifiExpiresAndClockJumpCannotCommitPendingCell() throws Exception {
        for (boolean jump : new boolean[]{false, true}) {
            TwoRounds s = new TwoRounds(); s.clockJump = jump; s.cacheFailure = true;
            try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
                startSecondRound(s, sampler, 890000);
                assertEquals(WALL, sampler.merge(new JSONObject()).getJSONObject("radio").getLong("sampled_at_ms"));
                s.clock.advance(10001);
                assertTrue(sampler.merge(new JSONObject()).isNull("radio"));
                s.release(); finish(sampler);
                JSONObject result = sampler.merge(new JSONObject());
                if (jump) assertTrue(result.isNull("radio"));
                else assertEquals(s.initialAt, result.getJSONObject("radio").getLong("sampled_at_ms"));
            } finally { s.release(); }
        }
    }

    @Test(timeout = 8000) public void closeClearsHeldWifiAndPreventsLateFallback() throws Exception {
        TwoRounds s = new TwoRounds();
        RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock);
        try {
            startSecondRound(s, sampler, 300000);
            sampler.close(); finish(sampler);
            assertTrue(sampler.merge(new JSONObject()).isNull("radio"));
            assertEquals(0, s.cacheReads); assertFalse(sampler.tick());
        } finally { s.release(); sampler.close(); }
    }
    @Test(timeout = 8000) public void busyServiceRetryStillHasOnlyOnePassiveObservationAndNoScan() throws Exception {
        Scenario s = new Scenario(); s.busyOnce = true; s.gpsRelease.countDown();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            sampler.tick(); finish(sampler); assertEquals(2, s.cacheReads); assertEquals(1, s.starts);
            assertEquals(6, sampler.snapshot().getJSONObject("radio").getInt("wifi_count"));
        }
    }
    @Test(timeout = 10000) public void earlyGpsDoesNotRaceTheLateScanOrBlockTickAndReport() throws Exception {
        final long[] gpsReturned = new long[1];
        Scenario s = new Scenario() {
            @Override public TelemetryCollector.LocationReading read(long window, boolean radio) throws Exception {
                TelemetryCollector.LocationReading value = super.read(window, radio);
                if (!radio) gpsReturned[0] = System.nanoTime();
                return value;
            }
            @Override public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - gpsReturned[0]) >= 2900);
                clock.advance(3000);
                return super.readCachedRadio();
            }
        };
        s.gpsMs = 0; s.gpsRelease.countDown();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            sampler.tick();
            // 等 GPS 那一段真的返回，之后才是被动读的三秒宽限期。
            for (int i = 0; i < 200 && gpsReturned[0] == 0; i++) Thread.sleep(10);
            assertTrue("GPS 读取应已返回", gpsReturned[0] != 0);
            long before = System.nanoTime();
            for (int i = 0; i < 20; i++) { assertFalse(sampler.tick()); sampler.snapshot(); sampler.merge(new JSONObject()); }
            assertTrue("业务读取不得等待定位宽限期", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000);
            assertEquals(0, s.cacheReads);
            finish(sampler); assertEquals(1, s.cacheReads); assertEquals(1, s.starts);
            assertEquals(6, sampler.snapshot().getJSONObject("radio").getInt("wifi_count"));
        }
    }
    @Test(timeout = 8000) public void stalePostGpsCacheCannotRenewOldObservation() throws Exception {
        Scenario s = new Scenario(); s.gpsMs = 200000; s.gpsRelease.countDown();
        try (RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock)) {
            sampler.tick(); finish(sampler);
            JSONObject radio = sampler.merge(new JSONObject()).getJSONObject("radio");
            assertEquals(WALL + 2000, radio.getLong("sampled_at_ms"));
            assertEquals(1, radio.getJSONArray("cellTowers").length()); assertEquals(0, radio.getJSONArray("wifiAccessPoints").length());
            assertEquals(0, sampler.snapshot().getJSONObject("radio_cache_recheck").getInt("wifi_valid_count"));
            assertEquals(1, s.starts); assertEquals(1, s.cacheReads);
        }
    }
    @Test(timeout = 8000) public void closeDuringGracePeriodCancelsDeferredRead() throws Exception {
        Scenario s = new Scenario(); s.gpsFix = true; s.gpsMs = 0; s.gpsRelease.countDown();
        RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock);
        try {
            sampler.tick(); assertTrue(s.gpsEntered.await(2, TimeUnit.SECONDS));
            sampler.close(); finish(sampler);
            assertEquals("宽限期内关闭应取消被动读", 0, s.cacheReads);
        } finally { sampler.close(); }
    }
    @Test public void earlyGpsDelayIsBoundedAndDoesNotExtendLateGps() {
        assertEquals(3000, RemoteLocationSampler.cacheRecheckDelayMs(START, START));
        assertEquals(1072, RemoteLocationSampler.cacheRecheckDelayMs(START, START + 1928000000L));
        assertEquals(0, RemoteLocationSampler.cacheRecheckDelayMs(START, START + 3000000000L));
        assertEquals(0, RemoteLocationSampler.cacheRecheckDelayMs(START, START + 45000000000L));
        assertEquals(0, RemoteLocationSampler.cacheRecheckDelayMs(START, START - 1));
    }
    @Test(timeout = 8000) public void closeDuringGpsPreventsPassiveReads() throws Exception {
        Scenario s = new Scenario();
        RemoteLocationSampler sampler = new RemoteLocationSampler(s, s.clock);
        try {
            sampler.tick(); assertTrue(s.gpsEntered.await(2, TimeUnit.SECONDS)); sampler.close(); finish(sampler);
            assertEquals(0, s.cacheReads);
        } finally { s.gpsRelease.countDown(); sampler.close(); }
    }
}
