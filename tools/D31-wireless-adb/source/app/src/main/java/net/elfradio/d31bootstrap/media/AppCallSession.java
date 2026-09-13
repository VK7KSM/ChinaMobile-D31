package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 双向通话生命周期外壳；媒体动作仍只由CallSessionController串行执行。 */
public final class AppCallSession implements AppMediaController.Session, AutoCloseable {
    static final long CLEANUP_TIMEOUT_MS = 8000L;
    public interface Sender {
        void send(JSONObject value) throws Exception;
        void abort();
        void close() throws Exception;
    }
    public interface Signals {
        void changed(boolean ice);
        void failed(String code);
        default void diagnostics(JSONObject value) { }
    }
    public interface Factory {
        /** 只构造惰性Operations；音频、网络和工作线程从open开始，抛错前自行回收未交付对象。 */
        CallSessionController.Operations create(Sender sender, Signals signals) throws Exception;
    }

    private final File mediaRoot;
    private final RtcOffer offer;
    private final MediaCapture.Clock clock;
    private final MicrophoneSession.Transport transport;
    private final Factory factory;
    private final Cancellation cancellation = new Cancellation();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> new Thread(r, "d31-call-lifecycle"));
    private final ExecutorService transportWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "d31-call-transport"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "d31-call-watchdog"));
    private final Object transportQueue = new Object();
    private final AtomicBoolean abortSent = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private Future<?> transportClosing;
    private volatile String transportError = "";
    private volatile CallSessionController controller;
    private volatile MediaFiles.Lease lease;
    private boolean started, stopping, terminal, invalidInviteAtStart;
    private long startedAt, endedAt, connectDeadline, stopElapsed, stopNanos;
    private String state = "idle", reason = "", cleanupReason = "", protocolSnapshot = "{}";
    private volatile String mediaDiagnostics = "{}";

    /** files为框架私有files目录；Android上下文、已验证APK和缓存由主线Factory闭包持有。 */
    public AppCallSession(File files, RtcOffer offer, MediaCapture.Clock clock, Factory factory) throws Exception {
        this(files, offer, clock, new MediaWebSocket(), factory);
    }
    public AppCallSession(File files, RtcOffer offer, MediaCapture.Clock clock,
                          MicrophoneSession.Transport transport, Factory factory) throws Exception {
        if (files == null || offer == null || clock == null || transport == null || factory == null)
            throw new IOException("MEDIA_DEPENDENCY_MISSING");
        if (!"call".equals(offer.mode)) throw new IOException("MEDIA_CALL_MODE_REQUIRED");
        mediaRoot = new File(files.getAbsoluteFile(), "media");
        this.offer = offer; this.clock = clock; this.transport = transport; this.factory = factory;
    }

    public synchronized void start() throws Exception {
        if (started || stopping) throw new IOException("MEDIA_SESSION_NOT_REUSABLE");
        started = true; state = "connecting";
        long remaining = offer.expiresAt - clock.wall();
        invalidInviteAtStart = remaining <= 0 || remaining > 45000L;
        connectDeadline = clock.elapsed() + Math.max(0, Math.min(45000L, remaining));
        lifecycle.execute(this::run);
        watchdog.scheduleWithFixedDelay(this::tick, 0, 100, TimeUnit.MILLISECONDS);
    }
    private void run() {
        CallSessionController created = null;
        String failure = "";
        try {
            cancellation.check();
            if (offer.expiresAt <= clock.wall() || offer.expiresAt - clock.wall() > 45000L)
                throw new IOException("MEDIA_EXPIRED");
            lease = MediaFiles.lease(mediaRoot);
            cancellation.check();
            created = new CallSessionController(clock, offer.expiresAt, new SafeOperations(), this::controllerChanged);
            controller = created;
            cancellation.check();
            transport.connect(offer, new MicrophoneSession.Events() {
                public void message(String raw) {
                    if (!cancellation.isCancelled()) { controller.receive(raw); controllerChanged(); }
                }
                public void disconnected() { stop("MEDIA_DISCONNECTED"); }
            });
        } catch (Exception | LinkageError error) { stop(code(error, "MEDIA_CALL_START_FAILED")); }
        finally {
            if (created != null) {
                if (cancellation.isCancelled()) created.stop(stopReason());
                try {
                    while (!created.awaitClosed(100)) {
                        if (cleanupExpired()) { failure = "MEDIA_CALL_CLEANUP_TIMEOUT"; break; }
                    }
                    if (failure.isEmpty()) {
                        controllerChanged(); failure = created.snapshot().optString("cleanup_reason");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); stop("MEDIA_HOST_CLOSED"); failure = "MEDIA_CALL_RELEASE_UNCONFIRMED";
                }
            }
            try { closeTransport(); }
            catch (Exception | LinkageError error) { if (failure.isEmpty()) failure = "MEDIA_TRANSPORT_RELEASE_UNCONFIRMED"; }
            finish(failure);
        }
    }

    private final class SafeOperations implements CallSessionController.Operations {
        private volatile CallSessionController.Operations delegate;
        public void open(CallProtocol.Route route) throws Exception {
            cancellation.check();
            // 工厂与open/close在同一媒体执行器；取消期间返回的对象也不会逃过close。
            delegate = factory.create(new Sender() {
                public void send(JSONObject body) throws Exception { cancellation.check(); transport.send(body); }
                public void abort() { abortTransport(); }
                public void close() throws Exception { closeTransport(); }
            }, new Signals() {
                public void changed(boolean ice) {
                    CallSessionController current = controller;
                    if (!cancellation.isCancelled() && current != null) { current.ice(ice); current.mediaChanged(); }
                }
                public void failed(String code) { stop(code); }
                public void diagnostics(JSONObject value) {
                    try { mediaDiagnostics = AppCallEvidence.publicView(value).toString(); }
                    catch (Exception ignored) { }
                }
            });
            if (delegate == null) throw new IOException("MEDIA_CALL_FACTORY_EMPTY");
            cancellation.check(); delegate.open(route);
        }
        public void send(JSONObject body) throws Exception { cancellation.check(); delegate.send(body); }
        public JSONObject createPublish(JSONObject body) throws Exception { cancellation.check(); return delegate.createPublish(body); }
        public void applyPublish(JSONObject body) throws Exception { cancellation.check(); delegate.applyPublish(body); }
        public CallProtocol.SubscriptionResult subscribe(JSONObject body) throws Exception { cancellation.check(); return delegate.subscribe(body); }
        public void negotiationComplete() throws Exception { cancellation.check(); delegate.negotiationComplete(); }
        public void prepareMuted() throws Exception { cancellation.check(); delegate.prepareMuted(); }
        public int inputProof() throws Exception { cancellation.check(); return delegate.inputProof(); }
        public int outputProof() throws Exception { cancellation.check(); return delegate.outputProof(); }
        public boolean captureFrames() { return !cancellation.isCancelled() && delegate.captureFrames(); }
        public boolean playbackFrames() { return !cancellation.isCancelled() && delegate.playbackFrames(); }
        public void unmute() throws Exception { cancellation.check(); delegate.unmute(); }
        public void cancel() {
            cancellation.cancel(); abortTransport();
            CallSessionController.Operations current = delegate;
            if (current != null) current.cancel();
        }
        public void close() throws Exception {
            CallSessionController.Operations current = delegate;
            if (current != null) current.close();
        }
    }

    private void controllerChanged() {
        CallSessionController current = controller;
        if (current == null) return;
        JSONObject value = current.snapshot();
        String next = value.optString("state");
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
    }
    public void tick() {
        boolean running;
        synchronized (this) { if (terminal) return; running = started && !stopping; }
        CallSessionController current = controller;
        if (current != null) { current.tick(); controllerChanged(); }
        else if (running && (invalidInviteAtStart || clock.elapsed() >= connectDeadline))
            stop(invalidInviteAtStart ? "MEDIA_EXPIRED" : "MEDIA_SESSION_TIMEOUT");
        if (cleanupExpired()) terminal("MEDIA_CALL_CLEANUP_TIMEOUT");
    }
    public void stop() { stop("MEDIA_HOST_CLOSED"); }
    public void close() { stop(); }
    public void stop(String error) {
        CallSessionController current = controller;
        if (current != null) {
            String original = current.snapshot().optString("reason");
            if (!original.isEmpty()) error = original;
        }
        boolean neverStarted;
        String selected;
        synchronized (this) {
            if (stopping || terminal) return;
            stopping = true; reason = safeReason(error); selected = reason; state = "closing";
            stopElapsed = clock.elapsed(); stopNanos = System.nanoTime();
            cancellation.cancel(); neverStarted = !started;
            if (neverStarted) watchdog.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        }
        abortTransport(); requestTransportClose();
        if (current != null) current.stop(selected);
        if (neverStarted) lifecycle.execute(() -> {
            String failure = "";
            try { closeTransport(); } catch (Exception | LinkageError errorClosing) { failure = "MEDIA_TRANSPORT_RELEASE_UNCONFIRMED"; }
            finish(failure);
        });
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
            // 与PTT相同，abort/close共享唯一传输清理队列，不在外层控制锁内等待Socket。
            transportWorker.execute(() -> { try { transport.abort(); } catch (Exception | LinkageError ignored) { } });
        }
    }
    private Future<?> requestTransportClose() {
        synchronized (transportQueue) {
            if (transportClosing == null) {
                transportClosing = transportWorker.submit(() -> { transport.close(); return null; });
                transportWorker.shutdown();
            }
            return transportClosing;
        }
    }
    private void closeTransport() throws Exception {
        Future<?> completion = requestTransportClose();
        if (!transportError.isEmpty()) throw new IOException(transportError);
        try { completion.get(1750, TimeUnit.MILLISECONDS); }
        catch (Exception failure) {
            transportError = "MEDIA_TRANSPORT_RELEASE_UNCONFIRMED";
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException(transportError);
        }
    }
    private void finish(String failure) {
        synchronized (this) { if (terminal) return; }
        String error = failure.isEmpty() ? transportError : failure;
        if (error.isEmpty() && cleanupExpired()) error = "MEDIA_CALL_CLEANUP_TIMEOUT";
        if (error.isEmpty() && lease != null) {
            // 文件关闭不持快照锁；直到真实释放完成前外层仍只能看到closing。
            try { lease.close(); lease = null; }
            catch (Exception closeFailure) { error = "MEDIA_LEASE_RELEASE_UNCONFIRMED"; }
        }
        terminal(error);
    }
    private synchronized void terminal(String failure) {
        if (terminal) return;
        terminal = true; cleanupReason = failure; endedAt = clock.wall();
        state = failure.isEmpty() ? "closed" : "release_unconfirmed";
        watchdog.shutdownNow(); lifecycle.shutdown(); finished.countDown();
    }
    public synchronized JSONObject snapshot() throws Exception {
        return new JSONObject(protocolSnapshot).put("schemaVersion", 1).put("session_id", offer.id).put("mode", "call")
                .put("route", "speaker").put("state", state).put("reason", reason).put("cleanup_reason", cleanupReason)
                .put("cleanup_complete", "closed".equals(state)).put("ready", "streaming".equals(state))
                .put("started_at_ms", startedAt).put("ended_at_ms", endedAt).put("local_recording", false)
                .put("managed_media", false).put("input_ownership_required", true).put("output_ownership_required", true)
                .put("remote_audio_content", "NOT_VERIFIED").put("media_diagnostics", new JSONObject(mediaDiagnostics));
    }
    /** 表示外壳终态已确定；release_unconfirmed不代表底层线程已经退出。 */
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
