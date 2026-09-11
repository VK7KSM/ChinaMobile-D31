package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 复用Controller.Session的单次适配器；原生动作串行，控制线程只改状态和取消标记。 */
public final class AudioCaptureLifecycleSession implements AppMediaController.Session, AutoCloseable {
    public interface Source {
        /** 只返回可信来源已有的不可变快照，禁止在此启动dumpsys或阻塞采集。 */
        AudioCaptureLifecycle.Evidence cached();
        default void start(AudioCaptureLifecycleSession session) throws Exception { }
        default void close() throws Exception { }
    }
    public interface Driver {
        /** 只启动本次输入；实际调用前及可取消等待中检查取消，不发布声音或启动网络。 */
        void start(Cancellation cancellation) throws Exception;
        /** 即使start部分失败也清理；先stop完成再release，并返回真实回读证据。 */
        AudioCaptureLifecycle.Release stopAndRelease() throws Exception;
    }

    private final AudioCaptureLifecycle lifecycle;
    private final Source source;
    private final Driver driver;
    private final Cancellation requestCancellation;
    private final Cancellation nativeCancellation = new Cancellation();
    private final AtomicBoolean cleanupQueued = new AtomicBoolean();
    private final ExecutorService worker;

    public AudioCaptureLifecycleSession(AudioCaptureLifecycle lifecycle, Source source, Driver driver, Cancellation cancellation) {
        if (lifecycle == null || source == null || driver == null || cancellation == null) {
            throw new IllegalArgumentException("生命周期依赖缺失");
        }
        this.lifecycle = lifecycle; this.source = source; this.driver = driver; requestCancellation = cancellation;
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable action) {
                Thread thread = new Thread(action, "d31-audio-lifecycle"); thread.setDaemon(true); return thread;
            }
        });
    }

    @Override public synchronized void start() throws Exception {
        try {
            requestCancellation.check(); nativeCancellation.check();
            lifecycle.begin(source.cached());
            worker.execute(new Runnable() {
                @Override public void run() {
                    try {
                        requestCancellation.check(); nativeCancellation.check();
                        driver.start(nativeCancellation);
                        lifecycle.started();
                    } catch (Exception | LinkageError failure) {
                        lifecycle.startFailed();
                    } finally { tick(); }
                }
            });
            source.start(this);
        } catch (Exception failure) {
            stop(); throw failure;
        }
    }

    /** 由可信证据采集工作线程推入，不从高频音频读取循环启动系统转储。 */
    public void observe(AudioCaptureLifecycle.Evidence evidence) {
        if (requestCancellation.isCancelled()) stop();
        lifecycle.observe(evidence); tick();
    }

    /** 总负责人的既有低频心跳须驱动此方法；不创建第二个周期线程。 */
    public void tick() {
        if (requestCancellation.isCancelled()) lifecycle.requestStop();
        if (lifecycle.snapshot().stopRequired) {
            nativeCancellation.cancel(); queueCleanup();
        }
    }

    /** 缓存准入，不替换现有guard，也不改变managed_media；失败先撤销并安排清理。 */
    public void requireContinuation() throws IOException {
        tick();
        if (!lifecycle.snapshot().continuationEligible) throw new IOException("MEDIA_CAPTURE_CONTINUATION_REJECTED");
    }

    @Override public synchronized void stop() {
        lifecycle.requestStop(); nativeCancellation.cancel(); queueCleanup();
    }

    private synchronized void queueCleanup() {
        if (!cleanupQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(new Runnable() {
                @Override public void run() {
                    try {
                        AudioCaptureLifecycle.Release release = driver.stopAndRelease();
                        source.close();
                        lifecycle.confirmRelease(release);
                    } catch (Exception | LinkageError failure) {
                        lifecycle.releaseFailed();
                        try { source.close(); } catch (Exception | LinkageError ignored) { }
                    }
                    finally { worker.shutdown(); }
                }
            });
        } catch (RuntimeException failure) { lifecycle.releaseFailed(); worker.shutdown(); }
    }

    @Override public JSONObject snapshot() throws Exception {
        tick();
        AudioCaptureLifecycle.Snapshot value = lifecycle.snapshot();
        return new JSONObject().put("session_id", lifecycle.sessionId())
                .put("state", value.phase.name().toLowerCase(Locale.US)).put("reason", value.reason)
                .put("stop_reason", value.stopReason)
                .put("continuation_eligible", value.continuationEligible).put("stop_required", value.stopRequired)
                .put("release_confirmed", value.releaseConfirmed).put("io_bound", value.ioBound)
                .put("managed_media", false).put("atomic_reservation", false)
                .put("scope", "OFFLINE_LIFECYCLE_NOT_CONNECTED_TO_MEDIA_GUARD");
    }

    @Override public void close() { stop(); }
}
