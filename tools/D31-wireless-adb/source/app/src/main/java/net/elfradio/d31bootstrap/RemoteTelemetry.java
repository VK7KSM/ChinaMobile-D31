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

    /** 初始化一次；回调由后台线程执行，主线应只置位并唤醒，不在回调内同步上报。 */
    synchronized void enableLocation(android.content.Context context, Runnable wakeCallback) {
        if (location == null) location = new RemoteLocationSampler(context, wakeCallback);
    }

    synchronized void tickLocation() { if (location != null) location.tick(); }

    /** 只读脱敏缓存快照；不触发采样、Binder或报告唤醒。 */
    synchronized JSONObject locationSnapshot() throws Exception {
        return location == null ? RemoteLocationSampler.emptySnapshot() : location.snapshot();
    }

    private synchronized JSONObject mergeLocation(JSONObject report) throws Exception {
        return location == null ? report : location.merge(report);
    }

    RemoteTelemetry() { this(5000); }
    RemoteTelemetry(long preparationMs) {
        if (preparationMs < 0 || preparationMs > 5000) throw new IllegalArgumentException("报告准备窗口无效");
        this.preparationMs = preparationMs;
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
