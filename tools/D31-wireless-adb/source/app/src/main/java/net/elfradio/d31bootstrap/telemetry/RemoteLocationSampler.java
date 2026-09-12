package net.elfradio.d31bootstrap.telemetry;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** 单轮无线观测和主动定位在独立线程运行；tick和报告合并不调用框架服务。 */
public final class RemoteLocationSampler implements AutoCloseable {
    public static final long INTERVAL_MS = 300000, GPS_WINDOW_MS = 45000, FIX_AGE_MS = 300000;
    public interface Source {
        TelemetryCollector.LocationReading read(long windowMs, boolean radio) throws Exception;
        default TelemetryCollector.LocationReading readCachedRadio() throws Exception { return null; }
    }
    private final Source source;
    private final TelemetryCollector.Clock clock;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "d31-location-sampler"); thread.setDaemon(true); return thread;
    });
    private boolean closed, active;
    private long nextNanos;
    private TelemetryCollector.LocationReading latest;
    private String radio;
    private String cacheRecheck;
    private long radioWall, radioElapsed;

    public RemoteLocationSampler(Context context, Runnable changed) {
        this(new Source() {
            public TelemetryCollector.LocationReading read(long window, boolean includeRadio) throws Exception {
                return new AndroidTelemetryAccess(context, includeRadio).location(
                        new TelemetryCollector.Limits(window, FIX_AGE_MS), AndroidTelemetryAccess.clock());
            }
            public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                return AppLocationCache.read(context, new TelemetryCollector.Limits(0, FIX_AGE_MS),
                        AndroidTelemetryAccess.clock(), true, false);
            }
        }, AndroidTelemetryAccess.clock(), changed);
    }

    public RemoteLocationSampler(Source source, TelemetryCollector.Clock clock, Runnable changed) {
        if (source == null || clock == null || changed == null) throw new IllegalArgumentException("MISSING_LOCATION_DEPENDENCY");
        this.source = source; this.clock = clock; this.changed = changed;
    }

    /** 可每次核心tick调用。只投递一次任务；重报或唤醒不重置节流时间。 */
    public synchronized boolean tick() {
        if (closed || active || clock.elapsedRealtimeNanos() < nextNanos) return false;
        active = true;
        cacheRecheck = null;
        nextNanos = clock.elapsedRealtimeNanos() + INTERVAL_MS * 1000000L;
        worker.execute(() -> {
            try {
                TelemetryCollector.LocationReading initial = readStage(0, true);
                long initialCompleted = clock.elapsedRealtimeNanos();
                publish(initial);
                if (!isClosed() && !Thread.currentThread().isInterrupted()) {
                    publish(readStage(GPS_WINDOW_MS, false));
                    if (needsCacheRecheck(initial) && !isClosed() && !Thread.currentThread().isInterrupted()) {
                        // GPS可提前结束；仅定位工作线程等待已发出的扫描，不延长任何桥回包预算。
                        long delay = cacheRecheckDelayMs(initialCompleted, clock.elapsedRealtimeNanos());
                        if (delay > 0) Thread.sleep(delay);
                        if (!isClosed() && !Thread.currentThread().isInterrupted()) recheckCachedRadio();
                    }
                }
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (Exception unavailable) {
                publish(new TelemetryCollector.LocationReading(null, "provider_unavailable", false));
            } finally { synchronized (RemoteLocationSampler.this) { active = false; } }
        });
        return true;
    }

    private synchronized boolean isClosed() { return closed; }

    static long cacheRecheckDelayMs(long completed, long now) {
        // 首次2秒后再给3秒，覆盖已捕获的最长3.928秒扫描；不是额外扫描或重试循环。
        return now < completed ? 0 : Math.max(0, 3000 - (now - completed) / 1000000L);
    }

    private static boolean needsCacheRecheck(TelemetryCollector.LocationReading initial) {
        if (initial == null || initial.radio == null) return false;
        try { return "no_fresh_results_before_deadline".equals(new JSONObject(initial.radio).optString("wifi_scan_result")); }
        catch (Exception invalid) { return false; }
    }

    private void recheckCachedRadio() throws InterruptedException {
        try {
            TelemetryCollector.LocationReading reading = readStage(0, true, true);
            if (reading == null || reading.radio == null || !reading.listenerReleased) {
                recordCacheRecheck(new JSONObject().put("result", "unavailable")); return;
            }
            JSONObject observed = RemoteLocationRadio.validated(new JSONObject(reading.radio), clock.wallTimeMillis());
            JSONObject detail = new JSONObject().put("result", "completed").put("sampled_at_ms", observed.getLong("sampled_at_ms"))
                    .put("wifi_reason", observed.optString("wifi_reason", "not_reported"))
                    .put("wifi_count", observed.getJSONArray("wifiAccessPoints").length());
            for (String field : WIFI_DIAGNOSTICS) detail.put(field, observed.has(field) ? observed.get(field) : JSONObject.NULL);
            recordCacheRecheck(detail);
            // 补读缺失或仅一个AP不抹掉先前cell；单AP证据仍保留在脱敏诊断中。
            if (observed.getJSONArray("wifiAccessPoints").length() >= 2) publish(reading, true);
        } catch (InterruptedException cancelled) { throw cancelled; }
        catch (Exception unavailable) {
            try { recordCacheRecheck(new JSONObject().put("result", "rejected_or_unavailable")); }
            catch (Exception ignored) { }
        }
    }

    private synchronized void recordCacheRecheck(JSONObject detail) { if (!closed) cacheRecheck = detail.toString(); }

    private TelemetryCollector.LocationReading readStage(long window, boolean includeRadio) throws Exception {
        return readStage(window, includeRadio, false);
    }

    private TelemetryCollector.LocationReading readStage(long window, boolean includeRadio, boolean cacheOnly) throws Exception {
        TelemetryCollector.LocationReading reading = null;
        // 同一个Service的上一回包可能先于stopSelf到达；仅对明确未采集的busy重试。
        for (int i = 0; i < 40 && !isClosed(); i++) {
            reading = cacheOnly ? source.readCachedRadio() : source.read(window, includeRadio);
            if (reading == null || !"app_cache_busy".equals(reading.reason)) return reading;
            Thread.sleep(100);
        }
        return reading;
    }

    private void publish(TelemetryCollector.LocationReading reading) {
        publish(reading, false);
    }

    private void publish(TelemetryCollector.LocationReading reading, boolean radioOnly) {
        boolean notify;
        synchronized (this) {
            if (closed || reading == null) return;
            TelemetryCollector.Limits limits = new TelemetryCollector.Limits(0, FIX_AGE_MS);
            TelemetryCollector.LocationReading chosen = radioOnly && latest != null ? latest : reading;
            if (latest != null && TelemetryCollector.invalidFix(latest.fix, limits, clock) == null
                    && (TelemetryCollector.invalidFix(reading.fix, limits, clock) != null
                    || ("gps".equals(latest.fix.provider) && !"gps".equals(reading.fix.provider)))) chosen = latest;
            notify = latest == null || !same(latest, chosen);
            latest = chosen;
            if (reading.radio != null) {
                try {
                    long nowWall = clock.wallTimeMillis(), nowElapsed = clock.elapsedRealtimeNanos();
                    JSONObject observed = RemoteLocationRadio.validated(new JSONObject(reading.radio), nowWall);
                    if (radioOnly && radio != null && observed.getJSONArray("cellTowers").length() == 0) {
                        long elapsed = nowElapsed - radioElapsed, wall = nowWall - radioWall;
                        if (elapsed >= 0 && wall >= 0 && Math.abs(wall - elapsed / 1000000L) <= 1000) {
                            try {
                                // 旧小区重新经过120秒准入，不能借十五分钟报告有效期变成新观测。
                                JSONObject previous = RemoteLocationRadio.validated(new JSONObject(radio), nowWall);
                                if (previous.getJSONArray("cellTowers").length() > 0) {
                                    observed.put("cellTowers", previous.getJSONArray("cellTowers"))
                                            .put("radioType", previous.getString("radioType"))
                                            .put("cell_reason", previous.optString("cell_reason", "observed"))
                                            .put("sampled_at_ms", Math.min(observed.getLong("sampled_at_ms"), previous.getLong("sampled_at_ms")));
                                }
                            } catch (Exception rejectedOldCells) { /* 旧观测无效不影响已验证的新Wi-Fi。 */ }
                        }
                    }
                    String validated = observed.toString();
                    notify |= !validated.equals(radio);
                    radio = validated; radioWall = nowWall; radioElapsed = nowElapsed;
                } catch (Exception rejected) { /* 无效无线数据不替代已有新鲜观测。 */ }
            }
        }
        if (notify && !isClosed()) {
            try { changed.run(); } catch (RuntimeException unavailable) { /* 唤醒失败不能泄漏定位服务。 */ }
        }
    }

    private static boolean same(TelemetryCollector.LocationReading a, TelemetryCollector.LocationReading b) {
        if (a == b) return true;
        if (a.fix == null || b.fix == null) return a.fix == b.fix && a.reason.equals(b.reason)
                && a.listenerReleased == b.listenerReleased;
        return a.fix.sampledAtMs == b.fix.sampledAtMs && a.fix.elapsedNanos == b.fix.elapsedNanos
                && a.fix.latitude == b.fix.latitude && a.fix.longitude == b.fix.longitude
                && a.fix.provider.equals(b.fix.provider) && java.util.Objects.equals(a.fix.accuracyMetres, b.fix.accuracyMetres)
                && a.fix.mock == b.fix.mock && a.listenerReleased == b.listenerReleased;
    }

    /** 仅合并新报告；调用方不得用此方法改写已冻结、待重传的报告。 */
    public synchronized JSONObject merge(JSONObject report) throws Exception {
        JSONObject result = new JSONObject(report.toString());
        TelemetryCollector.Limits limits = new TelemetryCollector.Limits(0, FIX_AGE_MS);
        if (latest != null) {
            String invalid = TelemetryCollector.invalidFix(latest.fix, limits, clock);
            if (invalid == null && latest.listenerReleased) {
                TelemetryCollector.Fix fix = latest.fix;
                result.put("gps", new JSONObject().put("lat", fix.latitude).put("lng", fix.longitude)
                        .put("acc_m", fix.accuracyMetres == null ? JSONObject.NULL : fix.accuracyMetres)
                        .put("provider", fix.provider).put("at", TelemetryJson.utc(fix.sampledAtMs)))
                        .put("location_reason", "recent_cache".equals(latest.reason) ? "recent_cache" : "sampled");
            } else if (result.isNull("gps")) result.put("location_reason",
                    !latest.listenerReleased ? "cleanup_failed" : latest.fix == null ? latest.reason : invalid);
        }
        result.put("radio", JSONObject.NULL);
        if (radio != null) {
            long elapsed = clock.elapsedRealtimeNanos() - radioElapsed, wall = clock.wallTimeMillis() - radioWall;
            if (elapsed >= 0 && wall >= 0 && Math.abs(wall - elapsed / 1000000L) <= 1000) {
                try { result.put("radio", RemoteLocationRadio.forReport(new JSONObject(radio), clock.wallTimeMillis())); }
                catch (IllegalArgumentException stale) { /* 不能将旧扫描续期为本次报告时间。 */ }
            }
        }
        return result;
    }

    public static JSONObject emptySnapshot() throws Exception {
        JSONObject result = new JSONObject().put("location_reason", "not_sampled").put("gpsPresent", false)
                .put("radio", new JSONObject().put("sampled_at_ms", JSONObject.NULL).put("wifi_reason", "not_sampled")
                        .put("cell_reason", "not_sampled").put("wifi_count", 0).put("cell_count", 0).put("usable", false));
        for (String field : WIFI_DIAGNOSTICS) result.getJSONObject("radio").put(field, JSONObject.NULL);
        result.getJSONObject("radio").put("wifi_scan_result", "not_sampled");
        return result;
    }

    private static final String[] WIFI_DIAGNOSTICS = {"wifi_raw_count", "wifi_valid_count", "wifi_initial_raw_count",
            "wifi_initial_valid_count", "wifi_scan_wait_ms", "wifi_newest_age_ms", "wifi_scan_result"};

    /** 与报告使用同一有效性判定，只返回白名单状态；不包含位置或无线标识。 */
    public synchronized JSONObject snapshot() throws Exception {
        JSONObject result = emptySnapshot();
        if (cacheRecheck != null) result.put("radio_cache_recheck", new JSONObject(cacheRecheck));
        JSONObject fields = merge(new JSONObject().put("gps", JSONObject.NULL).put("location_reason", "not_sampled"));
        result.put("location_reason", fields.getString("location_reason")).put("gpsPresent", !fields.isNull("gps"));
        JSONObject detail = result.getJSONObject("radio");
        if (radio != null) {
            JSONObject cached = new JSONObject(radio);
            detail.put("sampled_at_ms", cached.getLong("sampled_at_ms"))
                    .put("wifi_reason", cached.optString("wifi_reason", "not_reported"))
                    .put("cell_reason", cached.optString("cell_reason", "not_reported"));
            for (String field : WIFI_DIAGNOSTICS) detail.put(field, cached.has(field) ? cached.get(field) : JSONObject.NULL);
        }
        JSONObject usable = fields.optJSONObject("radio");
        if (usable != null) {
            int wifiCount = usable.getJSONArray("wifiAccessPoints").length(), cellCount = usable.getJSONArray("cellTowers").length();
            detail.put("wifi_count", wifiCount).put("cell_count", cellCount).put("usable", wifiCount >= 2 || cellCount > 0);
        }
        return result;
    }

    public synchronized boolean isActive() { return active; }
    @Override public synchronized void close() { closed = true; worker.shutdownNow(); }
}
