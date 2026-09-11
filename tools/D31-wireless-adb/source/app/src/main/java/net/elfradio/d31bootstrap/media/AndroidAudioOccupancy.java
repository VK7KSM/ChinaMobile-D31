package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 后台读取当前占用，媒体高频检查只消费缓存；未知、超时和过期一律拒绝。 */
public final class AndroidAudioOccupancy implements AudioGuard, AutoCloseable {
    public static final long REFRESH_DELAY_MS = 2000;
    public static final long MAX_AGE_MS = 4000;
    public static final long MAX_SAMPLE_MS = 1500;
    enum State { IDLE, BUSY, UNKNOWN }
    interface Source { JSONObject read() throws Exception; }
    interface Clock { long elapsed(); }
    private final Source source;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final CountDownLatch firstSample = new CountDownLatch(1);
    private final Thread mainThread;
    private volatile Observation latest;
    private volatile boolean started, closed;

    /** 构造不访问Binder、Provider或文件；服务实际启用时显式调用start。 */
    public AndroidAudioOccupancy(final Context context) {
        if (context == null) throw new IllegalArgumentException("缺少媒体上下文");
        this.source = new Source() {
            public JSONObject read() throws Exception {
                if (android.os.Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(android.os.Build.DEVICE))
                    throw new IOException("占用适配只适用于D31的API23");
                return AndroidAudioOccupancyCheck.inspect(context);
            }
        };
        this.clock = new Clock() { public long elapsed() { return android.os.SystemClock.elapsedRealtime(); } };
        this.worker = executor();
        android.os.Looper main = android.os.Looper.getMainLooper();
        this.mainThread = main == null ? null : main.getThread();
    }

    AndroidAudioOccupancy(Source source, Clock clock) {
        this(source, clock, null);
    }

    AndroidAudioOccupancy(Source source, Clock clock, Thread mainThread) {
        if (source == null || clock == null) throw new IllegalArgumentException("缺少占用采样依赖");
        this.source = source; this.clock = clock; this.worker = executor();
        this.mainThread = mainThread;
    }

    private static ScheduledExecutorService executor() {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "d31-audio-occupancy"); thread.setDaemon(true); return thread;
            }
        });
    }

    public synchronized AndroidAudioOccupancy start() {
        if (closed) throw new IllegalStateException("占用监控已关闭");
        if (!started) {
            started = true;
            worker.scheduleWithFixedDelay(new Runnable() { public void run() { refresh(); } },
                    0, REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    /** 仅等首轮结束或关闭；true不表示空闲，调用者仍须读取快照或requireIdle。 */
    public boolean awaitFirstSample(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 1 || timeoutMs > MAX_SAMPLE_MS) throw new IllegalArgumentException("首次采样等待必须为1至1500毫秒");
        if (Thread.currentThread() == mainThread) throw new IllegalStateException("禁止在Android主线程等待占用采样");
        return firstSample.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // 单线程固定延迟，不通过Future超时后不断创建替代线程；卡住时由缓存年龄拒绝。
    void refresh() {
        try {
            if (closed) return;
            long began = clock.elapsed();
            Observation next;
            try { next = evaluate(source.read(), began, clock.elapsed()); }
            catch (Exception failure) { next = Observation.unknown(began, clock.elapsed(), "SOURCE_READ_FAILED"); }
            catch (LinkageError failure) { next = Observation.unknown(began, clock.elapsed(), "SOURCE_API_UNAVAILABLE"); }
            if (!closed) latest = next;
        } finally { firstSample.countDown(); }
    }

    /** 仅返回脱敏摘要，不执行IO，不返回Provider行、手机号或音频客户标识。 */
    public JSONObject snapshot() throws Exception {
        Observation value = current();
        long now = clock.elapsed();
        return new JSONObject().put("cellular", value.cellular.name()).put("sip", value.sip.name())
                .put("other", value.other.name()).put("state", value.overall().name())
                .put("idle", value.overall() == State.IDLE).put("reason", value.reason)
                .put("sample_started_elapsed_ms", value.began).put("sample_finished_elapsed_ms", value.finished)
                .put("age_ms", value.began < 0 ? -1 : Math.max(0, now - value.began))
                .put("max_age_ms", MAX_AGE_MS).put("refresh_delay_ms", REFRESH_DELAY_MS)
                .put("sip_scope", "NEXUI_SESSION_MAP_INCLUDING_HFP")
                .put("other_scope", "GLOBAL_STREAMS_FOCUS_AND_SOURCES_NO_SELF_EXEMPTION")
                .put("read_only", true).put("atomic_reservation", false);
    }

    @Override public void requireIdle() throws IOException {
        Observation value = current();
        if (value.overall() != State.IDLE) throw new IOException("MEDIA_AUDIO_" + value.overall().name() + ":" + value.reason);
    }

    private Observation current() {
        if (closed) return Observation.unknown(-1, -1, "CLOSED");
        if (!started) return Observation.unknown(-1, -1, "NOT_STARTED");
        Observation value = latest;
        if (value == null) return Observation.unknown(-1, -1, "NO_SAMPLE");
        long now = clock.elapsed();
        if (now < value.began || now - value.began > MAX_AGE_MS)
            return Observation.unknown(value.began, value.finished, "STALE_SAMPLE");
        return value;
    }

    @Override public synchronized void close() {
        closed = true; latest = null; firstSample.countDown(); worker.shutdownNow();
    }

    static Observation evaluate(JSONObject raw, long began, long finished) {
        if (raw == null || began < 0 || finished < began || finished - began > MAX_SAMPLE_MS)
            return Observation.unknown(began, finished, "SAMPLE_DEADLINE");
        JSONObject phone = raw.optJSONObject("cellular"), vendor = raw.optJSONObject("nexui"), audio = raw.optJSONObject("audio");
        State cellular = State.UNKNOWN, sip = State.UNKNOWN, other = State.UNKNOWN;
        if (phone != null) {
            Integer call = number(phone, "call_state"), count = number(phone, "phone_count");
            if (call != null && (call == 1 || call == 2)) cellular = State.BUSY;
            else if (!phone.has("error_type") && call != null && call == 0 && count != null && count == 1)
                cellular = State.IDLE;
        }
        if (vendor != null && Boolean.TRUE.equals(vendor.opt("resolved")) && !vendor.has("error_type")) {
            JSONArray statuses = vendor.optJSONArray("statuses");
            if (statuses != null && statuses.length() > 0 && statuses.length() <= 32) {
                sip = State.IDLE;
                for (int i = 0; i < statuses.length(); i++) {
                    Object state = statuses.opt(i);
                    if ("CONNECTED".equals(state) || "INCOMING".equals(state) || "OUTGOING".equals(state)
                            || "CALLING".equals(state) || "HOLD".equals(state) || "HOLDING".equals(state)
                            || "CONNECTING".equals(state) || "DISCONNECTING".equals(state)) { sip = State.BUSY; break; }
                    if (!"IDLE".equals(state)) sip = State.UNKNOWN;
                }
            }
        }
        if (audio != null) {
            Integer mode = number(audio, "mode"), focus = number(audio, "focus_gain");
            State streams = activity(audio.optJSONArray("streams"), "stream", "active", "remote_active", 1, 32);
            State sources = activity(audio.optJSONArray("sources"), "source", "active", null, 9, 9);
            if ((mode != null && mode > 0) || (focus != null && focus > 0) || streams == State.BUSY || sources == State.BUSY)
                other = State.BUSY;
            else if (!audio.has("service_error_type") && !audio.has("system_error_type") && mode != null && mode == 0
                    && focus != null && focus == 0 && streams == State.IDLE && sources == State.IDLE) other = State.IDLE;
        }
        String reason = cellular == State.BUSY ? "CELLULAR_CALL_ACTIVE" : sip == State.BUSY ? "NEXUI_SESSION_ACTIVE"
                : other == State.BUSY ? "GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED"
                : cellular == State.UNKNOWN ? "CELLULAR_COVERAGE_UNKNOWN" : sip == State.UNKNOWN ? "NEXUI_STATE_UNKNOWN"
                : other == State.UNKNOWN ? "GLOBAL_AUDIO_COVERAGE_UNKNOWN" : "IDLE_OBSERVED";
        return new Observation(cellular, sip, other, began, finished, reason);
    }

    private static State activity(JSONArray rows, String index, String field, String second, int minimum, int maximum) {
        if (rows == null || rows.length() < minimum || rows.length() > maximum) return State.UNKNOWN;
        boolean unknown = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || number(row, index) == null || number(row, index) != i) { unknown = true; continue; }
            if (Boolean.TRUE.equals(row.opt(field)) || (second != null && Boolean.TRUE.equals(row.opt(second)))) return State.BUSY;
            if (!Boolean.FALSE.equals(row.opt(field)) || (second != null && !Boolean.FALSE.equals(row.opt(second)))) unknown = true;
        }
        return unknown ? State.UNKNOWN : State.IDLE;
    }
    private static Integer number(JSONObject value, String key) {
        Object number = value.opt(key); return number instanceof Integer ? (Integer) number : null;
    }
    static final class Observation {
        final State cellular, sip, other;
        final long began, finished;
        final String reason;
        Observation(State cellular, State sip, State other, long began, long finished, String reason) {
            this.cellular = cellular; this.sip = sip; this.other = other; this.began = began; this.finished = finished; this.reason = reason;
        }
        State overall() {
            if (cellular == State.BUSY || sip == State.BUSY || other == State.BUSY) return State.BUSY;
            return cellular == State.IDLE && sip == State.IDLE && other == State.IDLE ? State.IDLE : State.UNKNOWN;
        }
        static Observation unknown(long began, long finished, String reason) {
            return new Observation(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, began, finished, reason);
        }
    }
}
