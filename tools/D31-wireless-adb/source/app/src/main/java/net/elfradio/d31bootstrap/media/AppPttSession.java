package net.elfradio.d31bootstrap.media;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** APP会话外壳；构造不连接或访问音频设备，文件锁直到完整清理才释放。 */
public final class AppPttSession implements AppMediaController.Session, AutoCloseable {
    static final long CLEANUP_TIMEOUT_MS = 8000;
    interface Signals { void changed(boolean ice); void failed(String code); default void evidence(JSONObject storage) {} }
    interface Factory {
        PttSessionController.Operations create(AndroidPttOperations.Sender sender, Signals signals) throws Exception;
    }

    private final File mediaRoot;
    private final RtcOffer offer;
    private final MediaCapture.Clock clock;
    private final MicrophoneSession.Transport transport;
    private final Factory factory;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(job -> new Thread(job, "d31-ptt-lifecycle"));
    private final ExecutorService transportWorker = Executors.newSingleThreadExecutor(job -> new Thread(job, "d31-ptt-transport"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(job -> new Thread(job, "d31-ptt-watchdog"));
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean abortSent = new AtomicBoolean();
    private final Object transportQueue = new Object();
    private Future<?> transportClosing;
    private final Cancellation cancellation = new Cancellation();
    private volatile PttSessionController controller;
    private volatile MediaFiles.Lease lease;
    private boolean started, stopping, terminal;
    private long startedAt, endedAt, stopElapsed, stopNanos, connectDeadline;
    private String state = "idle", reason = "", cleanupReason = "", protocolSnapshot = "{}";
    private volatile String transportError = "";
    private volatile String evidenceSnapshot = "{}";

    /** files为框架私有files目录；nativeCache为主线已规范化的media-native目录。 */
    public AppPttSession(Context app, File files, File nativeCache, File verifiedApk,
            String hash, RtcOffer offer, MediaCapture.Clock clock) throws Exception {
        this(files, offer, clock, new MediaWebSocket(), androidFactory(app, files, nativeCache, verifiedApk, hash, offer, clock));
    }

    AppPttSession(File files, RtcOffer offer, MediaCapture.Clock clock,
            MicrophoneSession.Transport transport, Factory factory) throws Exception {
        if (files == null || offer == null || clock == null || transport == null || factory == null)
            throw new IOException("MEDIA_DEPENDENCY_MISSING");
        if (!"ptt".equals(offer.mode)) throw new IOException("MEDIA_PTT_MODE_REQUIRED");
        mediaRoot = new File(files.getAbsoluteFile(), "media");
        this.offer = offer; this.clock = clock; this.transport = transport; this.factory = factory;
    }

    private static Factory androidFactory(Context app, File files, File cache, File apk,
            String hash, RtcOffer offer, MediaCapture.Clock clock) throws Exception {
        if (app == null || files == null || cache == null || apk == null || offer == null || clock == null)
            throw new IOException("MEDIA_DEPENDENCY_MISSING");
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("MEDIA_APK_HASH_REQUIRED");
        return (sender, signals) -> {
            PttOutputGuard guard = new PttOutputGuard(android.os.Process.myPid(), clock);
            final AndroidRtcDownlink[] reference = new AndroidRtcDownlink[1];
            AndroidRtcDownlink peer = new AndroidRtcDownlink(app, apk, hash, cache, clock, new AndroidRtcDownlink.Events() {
                public void changed() {
                    try { signals.changed(reference[0].snapshot().optBoolean("ice_connected")); }
                    catch (Exception failure) { signals.failed("MEDIA_PTT_SNAPSHOT_FAILED"); }
                }
                public void failed(String code) { signals.failed(code); }
            });
            reference[0] = peer;
            PttOutputObserver observer = new PttOutputObserver(app, guard,
                    new File(files, "media/ptt-output-diagnostics"), offer.id, hash);
            DownlinkRouteLease route = AndroidDownlinkRoute.create(app, guard,
                    () -> signals.failed("MEDIA_PTT_FOCUS_LOST"));
            AppMediaReadTrace trace = new AppMediaReadTrace();
            return new AppPttEvidence(new AndroidPttOperations(peer, guard, observer, route, clock, sender),
                    peer::privatePlaybackIdentity, new AndroidAudioCaptureObservation(app, trace), trace,
                    new AppMediaRtcDiagnostics(new File(files, "media/ptt-output-identity-diagnostics"), offer.id, hash),
                    clock, state -> {
                        try { state.put("playback", peer.snapshot()).put("output_observer", observer.storage()); }
                        catch (Exception unavailable) { /* 原取证状态仍可独立报告。 */ }
                        signals.evidence(state);
                    });
        };
    }

    public void start() throws Exception {
        synchronized (this) {
            if (started || stopping) throw new IOException("MEDIA_SESSION_NOT_REUSABLE");
            started = true; state = "connecting";
            connectDeadline = clock.elapsed() + Math.max(0, Math.min(45000, offer.expiresAt - clock.wall()));
            // 先排工作再离锁，stop不能越过排队动作提前宣布关闭。
            try {
                lifecycle.execute(this::run);
                watchdog.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException failure) {
                stop("MEDIA_START_FAILED"); throw new IOException("MEDIA_START_FAILED", failure);
            }
        }
    }

    private void run() {
        PttSessionController created = null;
        PttSessionController.Operations operations = null;
        String failure = "";
        try {
            cancellation.check();
            if (offer.expiresAt <= clock.wall() || offer.expiresAt - clock.wall() > 45000)
                throw new IOException("MEDIA_EXPIRED");
            lease = MediaFiles.lease(mediaRoot);
            cancellation.check();
            operations = factory.create(new AndroidPttOperations.Sender() {
                public void send(JSONObject value) throws Exception { cancellation.check(); transport.send(value); }
                public void close() throws Exception { closeTransport(); }
                public void abort() { abortTransport(); }
            }, new Signals() {
                public void changed(boolean ice) {
                    PttSessionController current = controller;
                    if (current != null) { current.ice(ice); current.mediaChanged(); }
                }
                public void failed(String code) { stop(code); }
                public void evidence(JSONObject storage) { evidenceSnapshot = storage.toString(); }
            });
            cancellation.check();
            created = new PttSessionController(clock, offer.expiresAt, new SafeOperations(operations), this::controllerChanged);
            controller = created;
            cancellation.check();
            transport.connect(offer, new MicrophoneSession.Events() {
                public void message(String raw) {
                    if (!cancellation.isCancelled()) { controller.receive(raw); controllerChanged(); }
                }
                public void disconnected() { stop("MEDIA_DISCONNECTED"); }
            });
        } catch (Exception | LinkageError error) {
            stop(code(error, "MEDIA_PTT_START_FAILED"));
        } finally {
            if (created != null) {
                if (cancellation.isCancelled()) created.stop(stopReason());
                // 正常运行期间等待事件；停止后的等待有独立硬时限，不能依赖可暂停的测试时钟。
                try {
                    while (!created.awaitClosed(100)) {
                        if (cleanupExpired()) { failure = "MEDIA_PTT_CLEANUP_TIMEOUT"; break; }
                    }
                    if (failure.isEmpty()) {
                        controllerChanged();
                        failure = created.snapshot().optString("cleanup_reason");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); stop("MEDIA_HOST_CLOSED"); failure = "MEDIA_PTT_RELEASE_UNCONFIRMED";
                } catch (Exception error) { failure = "MEDIA_PTT_RELEASE_UNCONFIRMED"; }
            } else if (operations != null) {
                try { operations.cancel(); } catch (Exception | LinkageError error) { failure = "MEDIA_PTT_CANCEL_UNCONFIRMED"; }
                try { operations.close(); } catch (Exception | LinkageError error) { failure = code(error, "MEDIA_PTT_RELEASE_UNCONFIRMED"); }
            }
            try { closeTransport(); } catch (Exception | LinkageError error) {
                if (failure.isEmpty()) failure = "MEDIA_TRANSPORT_RELEASE_UNCONFIRMED";
            }
            finish(failure);
        }
    }

    private final class SafeOperations implements PttSessionController.Operations {
        private final PttSessionController.Operations delegate;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String cancelError = "";
        SafeOperations(PttSessionController.Operations delegate) { this.delegate = delegate; }
        public void open() throws Exception { cancellation.check(); delegate.open(); }
        public void send(JSONObject body) throws Exception { cancellation.check(); delegate.send(body); }
        public JSONObject subscribe(JSONObject body) throws Exception { cancellation.check(); return delegate.subscribe(body); }
        public void answerAcknowledged() throws Exception { cancellation.check(); delegate.answerAcknowledged(); }
        public void prepareMuted() throws Exception { cancellation.check(); delegate.prepareMuted(); }
        public int outputProof() throws Exception { cancellation.check(); return delegate.outputProof(); }
        public boolean playbackFrames() { return delegate.playbackFrames(); }
        public void unmute() throws Exception { cancellation.check(); delegate.unmute(); }
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            try { delegate.cancel(); } catch (Exception | LinkageError error) { cancelError = "MEDIA_PTT_CANCEL_UNCONFIRMED"; }
            abortTransport();
        }
        public void close() throws Exception {
            delegate.close();
            if (!cancelError.isEmpty()) throw new IOException(cancelError);
        }
    }

    private void controllerChanged() {
        PttSessionController current = controller;
        if (current == null) return;
        try {
            JSONObject value = current.snapshot();
            String next = value.getString("state");
            boolean closing = "closing".equals(next) || "closed".equals(next) || "release_unconfirmed".equals(next);
            synchronized (this) {
                if (terminal) return;
                protocolSnapshot = value.toString();
                if (!stopping && !closing) {
                    state = next;
                    if (value.optBoolean("ready") && startedAt == 0) startedAt = clock.wall();
                }
            }
            if (closing) stop(value.optString("reason", "MEDIA_STOPPED"));
        } catch (Exception error) { stop("MEDIA_PTT_SNAPSHOT_FAILED"); }
    }

    public void tick() {
        boolean running;
        synchronized (this) { if (terminal || !started) return; running = !stopping; }
        if (running) {
            PttSessionController current = controller;
            if (current != null) { current.tick(); controllerChanged(); }
            else if (offer.expiresAt <= clock.wall() || clock.elapsed() >= connectDeadline) stop("MEDIA_SESSION_TIMEOUT");
        }
        if (cleanupExpired()) finish("MEDIA_PTT_CLEANUP_TIMEOUT");
    }

    public void stop() { stop("MEDIA_HOST_CLOSED"); }
    public void close() { stop(); }
    public void stop(String code) {
        PttSessionController current = controller;
        if (current != null) try {
            String protocolReason = current.snapshot().optString("reason");
            if (!protocolReason.isEmpty()) code = protocolReason;
        } catch (Exception ignored) {}
        boolean neverStarted;
        synchronized (this) {
            if (stopping || terminal) return;
            stopping = true; reason = safeReason(code); state = "closing";
            stopElapsed = clock.elapsed(); stopNanos = System.nanoTime();
            cancellation.cancel(); neverStarted = !started;
        }
        abortTransport();
        if (current != null) current.stop(reason);
        if (neverStarted) {
            watchdog.scheduleWithFixedDelay(() -> { if (cleanupExpired()) finish("MEDIA_PTT_CLEANUP_TIMEOUT"); }, 100, 100, TimeUnit.MILLISECONDS);
            lifecycle.execute(() -> {
            String failure = "";
            try { closeTransport(); } catch (Exception | LinkageError error) { failure = "MEDIA_TRANSPORT_RELEASE_UNCONFIRMED"; }
            finish(failure);
            });
        }
    }

    private synchronized String stopReason() { return reason.isEmpty() ? "MEDIA_STOPPED" : reason; }
    private synchronized boolean cleanupExpired() {
        return stopping && (clock.elapsed() - stopElapsed >= CLEANUP_TIMEOUT_MS
                || System.nanoTime() - stopNanos >= TimeUnit.MILLISECONDS.toNanos(CLEANUP_TIMEOUT_MS));
    }
    private void abortTransport() {
        if (!abortSent.compareAndSet(false, true)) return;
        synchronized (transportQueue) {
            if (transportClosing != null) return;
            // Socket.close也可能等待库内锁；核心/回调线程只排队，不触碰传输对象。
            transportWorker.execute(() -> {
                try { transport.abort(); }
                catch (Exception | LinkageError error) { transportError = "MEDIA_TRANSPORT_CANCEL_UNCONFIRMED"; }
            });
        }
    }
    private void closeTransport() throws Exception {
        Future<?> completion;
        synchronized (transportQueue) {
            if (transportClosing == null) {
                transportClosing = transportWorker.submit(() -> { transport.close(); return null; });
                transportWorker.shutdown();
            }
            completion = transportClosing;
        }
        if ("MEDIA_TRANSPORT_RELEASE_UNCONFIRMED".equals(transportError)) throw new IOException(transportError);
        try { completion.get(1750, TimeUnit.MILLISECONDS); }
        catch (Exception error) {
            // 不取消排队关闭，阻塞解除后仍须实际关闭；超时不可提前释放media.lock。
            transportError = "MEDIA_TRANSPORT_RELEASE_UNCONFIRMED";
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException(transportError);
        }
    }
    private synchronized void finish(String failure) {
        if (terminal) { lifecycle.shutdown(); return; }
        cleanupReason = failure.isEmpty() ? transportError : failure;
        if (cleanupReason.isEmpty() && lease != null) {
            try { lease.close(); lease = null; }
            catch (Exception error) { cleanupReason = "MEDIA_LEASE_RELEASE_UNCONFIRMED"; }
        }
        // 超时、JNI或路由释放未知时保留锁；晚回调不能把终态升级为closed。
        terminal = true; endedAt = clock.wall();
        state = cleanupReason.isEmpty() ? "closed" : "release_unconfirmed";
        watchdog.shutdownNow(); lifecycle.shutdown(); finished.countDown();
    }

    public synchronized JSONObject snapshot() throws Exception {
        return new JSONObject(protocolSnapshot).put("schemaVersion", 1).put("session_id", offer.id).put("mode", "ptt")
                .put("state", state).put("reason", reason).put("cleanup_reason", cleanupReason)
                .put("cleanup_complete", "closed".equals(state)).put("ready", "streaming".equals(state))
                .put("started_at_ms", startedAt).put("ended_at_ms", endedAt)
                .put("local_recording", false).put("published", false).put("managed_media", false)
                .put("remote_audio_content", "NOT_VERIFIED")
                .put("output_ownership_required", true)
                .put("ptt_evidence", new JSONObject(evidenceSnapshot))
                .put("diagnostics_scope", "PTT_OUTPUT_MUTED_OBSERVATION");
    }
    public boolean awaitClosed(long timeoutMs) throws InterruptedException {
        return finished.await(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
    }
    private static String safeReason(String value) {
        return value != null && value.matches("MEDIA_[A-Z0-9_]{1,80}") ? value : "MEDIA_STOPPED";
    }
    private static String code(Throwable error, String fallback) {
        String value = error.getMessage();
        return value != null && value.matches("MEDIA_[A-Z0-9_]{1,80}") ? value : fallback;
    }
}
