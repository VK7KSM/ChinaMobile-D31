package net.elfradio.d31bootstrap.telemetry;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * 单轮无线观测和主动定位在独立线程运行；tick和报告合并不调用框架服务。
 *
 * 采样结果只等下一次常规报告带出去，不因为位置变了就提前上报。D31 没有可用的 GPS，
 * 也没有陀螺仪，位置全部来自 Wi-Fi 与基站，本身就在十几米到公里量级漂移；
 * 拿这种数据判断「设备动了」只会把漂移当成位移。位置在 D31 上仅供参考。
 */
public final class RemoteLocationSampler implements AutoCloseable {
    public static final long INTERVAL_MS = 300000, GPS_WINDOW_MS = 45000, FIX_AGE_MS = 300000;
    public interface Source {
        TelemetryCollector.LocationReading read(long windowMs, boolean radio) throws Exception;
        default TelemetryCollector.LocationReading readCachedRadio() throws Exception { return null; }
    }
    private final Source source;
    private final TelemetryCollector.Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "d31-location-sampler"); thread.setDaemon(true); return thread;
    });
    private boolean closed, active;
    private long nextNanos;
    private TelemetryCollector.LocationReading latest;
    private String radio;
    private String cacheRecheck;
    private long radioWall, radioElapsed;
    private boolean holdingRadio;
    private String pendingRadio;
    private long pendingRadioWall, pendingRadioElapsed;

    public RemoteLocationSampler(Context context) {
        this(new Source() {
            public TelemetryCollector.LocationReading read(long window, boolean includeRadio) throws Exception {
                return new AndroidTelemetryAccess(context, includeRadio).location(
                        new TelemetryCollector.Limits(window, FIX_AGE_MS), AndroidTelemetryAccess.clock());
            }
            public TelemetryCollector.LocationReading readCachedRadio() throws Exception {
                return AppLocationCache.read(context, new TelemetryCollector.Limits(0, FIX_AGE_MS),
                        AndroidTelemetryAccess.clock(), true, false);
            }
        }, AndroidTelemetryAccess.clock());
    }

    public RemoteLocationSampler(Source source, TelemetryCollector.Clock clock) {
        if (source == null || clock == null) throw new IllegalArgumentException("MISSING_LOCATION_DEPENDENCY");
        this.source = source; this.clock = clock;
    }

    /** 可每次核心tick调用。只投递一次任务；重报不重置节流时间。 */
    public synchronized boolean tick() {
        if (closed || active || clock.elapsedRealtimeNanos() < nextNanos) return false;
        active = true;
        cacheRecheck = null;
        JSONObject previous = reportRadio(radio, radioWall, radioElapsed);
        holdingRadio = previous != null && previous.optJSONArray("wifiAccessPoints").length() >= 2;
        pendingRadio = null;
        nextNanos = clock.elapsedRealtimeNanos() + INTERVAL_MS * 1000000L;
        worker.execute(() -> {
            try {
                TelemetryCollector.LocationReading initial = readStage(0, true);
                long initialCompleted = clock.elapsedRealtimeNanos();
                boolean recheck = needsCacheRecheck(initial);
                publish(initial);
                if (!isClosed() && !Thread.currentThread().isInterrupted()) {
                    publish(readStage(GPS_WINDOW_MS, false));
                    if (recheck && !isClosed() && !Thread.currentThread().isInterrupted()) {
                        // GPS可提前结束；仅定位工作线程等待已发出的扫描，不延长任何桥回包预算。
                        long delay = cacheRecheckDelayMs(initialCompleted, clock.elapsedRealtimeNanos());
                        if (delay > 0) Thread.sleep(delay);
                        if (!isClosed() && !Thread.currentThread().isInterrupted()) recheckCachedRadio();
                    }
                }
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (Exception unavailable) {
                publish(new TelemetryCollector.LocationReading(null, "provider_unavailable", false));
            } finally {
                finishRadioRound();
                synchronized (RemoteLocationSampler.this) { active = false; }
            }
        });
        return true;
    }

    private synchronized boolean isClosed() { return closed; }

    static long cacheRecheckDelayMs(long completed, long now) {
        // 首次2秒后再给3秒，覆盖已捕获的最长3.928秒扫描；不是额外扫描或重试循环。
        return now < completed ? 0 : Math.max(0, 3000 - (now - completed) / 1000000L);
    }

    private boolean needsCacheRecheck(TelemetryCollector.LocationReading initial) {
        if (initial == null || initial.radio == null) return false;
        try {
            JSONObject observed = RemoteLocationRadio.validated(new JSONObject(initial.radio), clock.wallTimeMillis());
            String result = observed.optString("wifi_scan_result");
            return "no_fresh_results_before_deadline".equals(result) || ("results_updated".equals(result)
                    && observed.getJSONArray("wifiAccessPoints").length() < 2);
        }
        catch (Exception invalid) { return false; }
    }

    private JSONObject reportRadio(String value, long wall, long elapsed) {
        if (value == null) return null;
        long elapsedAge = clock.elapsedRealtimeNanos() - elapsed, wallAge = clock.wallTimeMillis() - wall;
        if (elapsedAge < 0 || wallAge < 0 || Math.abs(wallAge - elapsedAge / 1000000L) > 1000) return null;
        try { return RemoteLocationRadio.forReport(new JSONObject(value), clock.wallTimeMillis()); }
        catch (Exception invalid) { return null; }
    }

    private synchronized void finishRadioRound() {
        if (holdingRadio) {
            // 只提交本轮已准入的原观测；失败或时钟失稳不能让上一轮Wi-Fi无限续留。
            JSONObject fallback = reportRadio(pendingRadio, pendingRadioWall, pendingRadioElapsed);
            radio = fallback == null ? null : fallback.toString();
            radioWall = pendingRadioWall; radioElapsed = pendingRadioElapsed;
            holdingRadio = false; pendingRadio = null;
        }
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

    private synchronized void publish(TelemetryCollector.LocationReading reading, boolean radioOnly) {
        if (closed || reading == null) return;
        TelemetryCollector.Limits limits = new TelemetryCollector.Limits(0, FIX_AGE_MS);
        TelemetryCollector.LocationReading chosen = radioOnly && latest != null ? latest : reading;
        if (latest != null && TelemetryCollector.invalidFix(latest.fix, limits, clock) == null
                && (TelemetryCollector.invalidFix(reading.fix, limits, clock) != null
                || ("gps".equals(latest.fix.provider) && !"gps".equals(reading.fix.provider)))) chosen = latest;
        latest = chosen;
        if (reading.radio != null) {
            try {
                long nowWall = clock.wallTimeMillis(), nowElapsed = clock.elapsedRealtimeNanos();
                JSONObject observed = RemoteLocationRadio.validated(new JSONObject(reading.radio), nowWall);
                if (holdingRadio && !radioOnly && observed.getJSONArray("wifiAccessPoints").length() < 2
                        && !"wifi_disabled".equals(observed.optString("wifi_reason"))
                        && !"wifi_disabled".equals(observed.optString("wifi_scan_result"))
                        && reportRadio(radio, radioWall, radioElapsed) != null) {
                    pendingRadio = observed.toString(); pendingRadioWall = nowWall; pendingRadioElapsed = nowElapsed;
                } else {
                    String previousRadio = holdingRadio ? pendingRadio : radio;
                    long previousWall = holdingRadio ? pendingRadioWall : radioWall;
                    long previousElapsed = holdingRadio ? pendingRadioElapsed : radioElapsed;
                    if (radioOnly && previousRadio != null && observed.getJSONArray("cellTowers").length() == 0) {
                        long elapsed = nowElapsed - previousElapsed, wall = nowWall - previousWall;
                        if (elapsed >= 0 && wall >= 0 && Math.abs(wall - elapsed / 1000000L) <= 1000) {
                            try {
                                // 旧小区重新经过120秒准入，不能借十五分钟报告有效期变成新观测。
                                JSONObject previous = RemoteLocationRadio.validated(new JSONObject(previousRadio), nowWall);
                                if (previous.getJSONArray("cellTowers").length() > 0) {
                                    observed.put("cellTowers", previous.getJSONArray("cellTowers"))
                                            .put("radioType", previous.getString("radioType"))
                                            .put("cell_reason", previous.optString("cell_reason", "observed"))
                                            .put("sampled_at_ms", Math.min(observed.getLong("sampled_at_ms"), previous.getLong("sampled_at_ms")));
                                }
                            } catch (Exception rejectedOldCells) { /* 旧观测无效不影响已验证的新Wi-Fi。 */ }
                        }
                    }
                    radio = observed.toString(); radioWall = nowWall; radioElapsed = nowElapsed;
                    holdingRadio = false; pendingRadio = null;
                }
            } catch (Exception rejected) { /* 无效无线数据不替代已有新鲜观测。 */ }
        }
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
        JSONObject usableRadio = reportRadio(radio, radioWall, radioElapsed);
        if (usableRadio != null) result.put("radio", usableRadio);
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
    @Override public synchronized void close() {
        closed = true;
        if (holdingRadio) radio = null;
        holdingRadio = false; pendingRadio = null;
        worker.shutdownNow();
    }
}
