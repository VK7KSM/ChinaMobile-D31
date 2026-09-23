package net.elfradio.d31bootstrap;

import net.elfradio.d31bootstrap.telemetry.TelemetryCollector;
import net.elfradio.d31bootstrap.telemetry.RemoteLocationSampler;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 报告只短暂等待采样；服务卡住时保留一个任务，不累积线程或阻塞维护循环。 */
final class RemoteTelemetry implements AutoCloseable {
    static final class PreparationPending extends RuntimeException { }
    private final long preparationMs;
    private long startedNanos;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "d31-telemetry"); thread.setDaemon(true); return thread;
    });
    private Future<TelemetryCollector.Sample> pending;
    private TelemetryCollector.Sample latest;
    private RemoteLocationSampler location;
    /** 一份报告为等本轮定位最多推迟这么久；一轮正常约 2–6 秒，超时就照常上报，不因定位卡住报告。 */
    static final long LOCATION_WAIT_MS = 15000;
    private final long locationWaitMs;
    private boolean waitingForLocation;
    private long locationWaitStartedNanos;

    /** 初始化一次。 */
    synchronized void enableLocation(android.content.Context context) {
        if (location == null) location = new RemoteLocationSampler(context);
    }

    /** 测试用：注入不连设备的采样器。 */
    synchronized void enableLocation(RemoteLocationSampler sampler) {
        if (location == null) location = sampler;
    }

    /**
     * 先采后报：新建报告前调用。本轮定位还没完成就抛 {@link PreparationPending}，报告阶段稍后重试，
     * 期间工作循环照常处理其它阶段；等满 {@link #LOCATION_WAIT_MS} 仍未完成则放行，按已有缓存如实上报。
     */
    synchronized void prepareLocation() {
        if (location == null || location.readyForReport()) { waitingForLocation = false; return; }
        long now = System.nanoTime();
        if (!waitingForLocation) { waitingForLocation = true; locationWaitStartedNanos = now; }
        if (now - locationWaitStartedNanos < TimeUnit.MILLISECONDS.toNanos(locationWaitMs)) throw new PreparationPending();
        waitingForLocation = false;
    }

    /** 只读脱敏缓存快照；不触发采样、Binder或报告唤醒。 */
    synchronized JSONObject locationSnapshot() throws Exception {
        return location == null ? RemoteLocationSampler.emptySnapshot() : location.snapshot();
    }

    private synchronized JSONObject mergeLocation(JSONObject report) throws Exception {
        return location == null ? report : location.merge(report);
    }

    RemoteTelemetry() { this(5000); }
    RemoteTelemetry(long preparationMs) { this(preparationMs, LOCATION_WAIT_MS); }
    RemoteTelemetry(long preparationMs, long locationWaitMs) {
        if (preparationMs < 0 || preparationMs > 5000) throw new IllegalArgumentException("报告准备窗口无效");
        if (locationWaitMs < 0 || locationWaitMs > LOCATION_WAIT_MS) throw new IllegalArgumentException("定位等待上限无效");
        this.preparationMs = preparationMs; this.locationWaitMs = locationWaitMs;
    }

    JSONObject enrich(JSONObject report, Callable<TelemetryCollector.Sample> collect, long waitMs) throws Exception {
        if (waitMs < 0 || waitMs > 250) throw new IllegalArgumentException("采样等待超出范围");
        if (pending != null && pending.isDone()) {
            try { latest = pending.get(); }
            catch (Exception unavailable) { latest = null; }
            pending = null;
        }
        if (latest != null) {
            long captured = latest.toJson().getLong("captured_at_ms"), now = System.currentTimeMillis();
            if (now >= captured && now - captured <= 60000) return mergeLocation(latest.mergeReport(report, now, 60000));
            latest = null;
        }
        if (pending == null) { startedNanos = System.nanoTime(); pending = worker.submit(collect); }
        String reason = "sampling_pending";
        try {
            latest = pending.get(waitMs, TimeUnit.MILLISECONDS); pending = null;
        } catch (TimeoutException stillRunning) {
            // 不取消后再反复启动；同一个阻塞的框架调用最多占一个后台线程。
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) < preparationMs)
                throw new PreparationPending();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw interrupted;
        } catch (Exception unavailable) {
            pending = null; latest = null; reason = "sampling_unavailable";
        }
        if (latest != null) return mergeLocation(latest.mergeReport(report, System.currentTimeMillis(), 60000));
        return mergeLocation(new JSONObject(report.toString()).put("gps", JSONObject.NULL).put("location_reason", reason)
                .put("battery", JSONObject.NULL).put("charging", JSONObject.NULL).put("battery_present", JSONObject.NULL));
    }

    @Override public void close() {
        if (location != null) location.close();
        if (pending != null) pending.cancel(true);
        worker.shutdownNow();
    }
}
