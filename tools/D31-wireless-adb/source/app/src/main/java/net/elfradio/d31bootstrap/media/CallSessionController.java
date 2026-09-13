package net.elfradio.d31bootstrap.media;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 单媒体执行器；回执直接进入纯状态机，取消另在线程执行，不添加定时轮询。 */
public final class CallSessionController implements AutoCloseable {
    public interface Operations {
        /** 默认SPEAKER；适配器须先保持双向软件静音，不支持的路由须明确失败。 */
        void open(CallProtocol.Route route) throws Exception;
        void send(JSONObject message) throws Exception;
        JSONObject createPublish(JSONObject newResult) throws Exception;
        void applyPublish(JSONObject publishResult) throws Exception;
        /** SFU不保证回显location，空errorCode不是失败；仍须唯一请求音轨及实际mid/onTrack/方向绑定。 */
        CallProtocol.SubscriptionResult subscribe(JSONObject subscribeResult) throws Exception;
        void negotiationComplete() throws Exception;
        void prepareMuted() throws Exception;
        /** 0尚待证据，1严格通过；其他值或抛异常均拒绝。不得以PCM能量代替归属。 */
        int inputProof() throws Exception;
        int outputProof() throws Exception;
        boolean captureFrames();
        boolean playbackFrames();
        /** 必须再次检查取消及实时归属；只能在此处解除双向软件静音。 */
        void unmute() throws Exception;
        /** 可与其他操作并发，幂等，不能在取消结束后重建资源；不在调用者线程执行。 */
        void cancel();
        /** 确认peer、采集、播放、route及transport物理退出；无法确认须抛错。 */
        void close() throws Exception;
    }

    private final CallProtocol protocol;
    private final Operations operations;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "d31-call-session"));
    private final AtomicBoolean queued = new AtomicBoolean(), dirty = new AtomicBoolean(), cancelling = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Object releaseLock = new Object();
    private boolean cancelDone, closeDone;
    private String closeFailure = "", published = "";

    public CallSessionController(MediaCapture.Clock clock, long expiresAt, Operations operations, Runnable changed) throws Exception {
        this(clock, expiresAt, operations, changed, CallProtocol.Route.SPEAKER);
    }
    public CallSessionController(MediaCapture.Clock clock, long expiresAt, Operations operations, Runnable changed,
                                 CallProtocol.Route route) throws Exception {
        if (operations == null) throw new IllegalArgumentException("MEDIA_CALL_OPERATIONS_INVALID");
        protocol = new CallProtocol(clock, expiresAt, route);
        this.operations = operations; this.changed = changed;
    }
    public void receive(String raw) {
        try {
            if (raw == null || raw.length() > 96000) throw new IllegalArgumentException();
            protocol.receive(new JSONObject(raw));
        } catch (Exception invalid) { protocol.stop("MEDIA_CALL_MESSAGE_INVALID"); }
        advance();
    }
    public void ice(boolean connected) { protocol.ice(connected); advance(); }
    public void tick() { protocol.tick(); advance(); }
    public void mediaChanged() { protocol.tick(); advance(); }
    public JSONObject snapshot() { return protocol.snapshot(); }
    public void stop(String reason) { protocol.stop(reason); advance(); }
    public void close() { stop("MEDIA_HOST_CLOSED"); }

    private void advance() { if (protocol.stopping()) cancel(); wake(); }
    private void cancel() {
        if (!cancelling.compareAndSet(false, true)) return;
        new Thread(() -> {
            try { operations.cancel(); } catch (Exception | LinkageError ignored) {
                // 取消异常不能跳过真实close，只有未退出的取消线程阻挡释放确认。
            } finally {
                synchronized (releaseLock) { cancelDone = true; completeRelease(); }
                wake();
            }
        }, "d31-call-cancel").start();
    }
    private void completeRelease() {
        if (closeDone && cancelDone) {
            protocol.released(closeFailure);
            finished.countDown();
        }
    }
    private void wake() {
        dirty.set(true);
        if (!queued.compareAndSet(false, true)) return;
        try { worker.execute(this::pump); }
        catch (RejectedExecutionException closed) { queued.set(false); }
    }
    private void pump() {
        try {
            do {
                dirty.set(false);
                CallProtocol.Action action;
                while ((action = protocol.poll()) != null) {
                    if (protocol.stopping() && !"CLOSE".equals(action.kind)) continue;
                    try {
                        switch (action.kind) {
                            case "OPEN": operations.open(protocol.route()); protocol.opened(); break;
                            case "SEND": operations.send(action.body); break;
                            case "CREATE_PUBLISH": protocol.publishCreated(operations.createPublish(action.body)); break;
                            case "APPLY_PUBLISH": operations.applyPublish(action.body); protocol.publishApplied(); break;
                            case "APPLY_SUBSCRIBE": protocol.subscriptionApplied(operations.subscribe(action.body)); break;
                            case "NEGOTIATED": operations.negotiationComplete(); protocol.negotiationApplied(); break;
                            case "PREPARE_MUTED": operations.prepareMuted(); protocol.prepared(); break;
                            case "UNMUTE": operations.unmute(); protocol.unmuted(); break;
                            case "CLOSE":
                                cancel();
                                String failure = "";
                                try { operations.close(); }
                                catch (Exception | LinkageError error) { failure = CallProtocol.code(error, "MEDIA_CALL_RELEASE_UNCONFIRMED"); }
                                synchronized (releaseLock) { closeFailure = failure; closeDone = true; completeRelease(); }
                                break;
                            default: throw new IllegalStateException("MEDIA_CALL_ACTION_INVALID");
                        }
                    } catch (Exception | LinkageError error) {
                        protocol.stop(CallProtocol.code(error, "MEDIA_CALL_ACTION_FAILED")); cancel();
                    }
                }
                String state = protocol.snapshot().getString("state");
                if ("muted_verification".equals(state) || "streaming".equals(state)) {
                    try {
                        observe();
                    } catch (Exception | LinkageError error) {
                        protocol.stop(CallProtocol.code(error, "MEDIA_CALL_OWNERSHIP_UNKNOWN")); cancel();
                    }
                    if (protocol.hasActions()) dirty.set(true);
                }
                publish();
                if (finished.getCount() == 0) { worker.shutdown(); return; }
            } while (dirty.get());
        } catch (Exception | LinkageError failure) {
            protocol.stop("MEDIA_CALL_CONTROLLER_FAILED"); cancel(); dirty.set(true);
        } finally {
            queued.set(false);
            if (finished.getCount() == 0) { publish(); worker.shutdown(); }
            else if (dirty.get()) wake();
        }
    }
    private boolean live() { protocol.tick(); return !protocol.stopping(); }
    private void observe() throws Exception {
        if (!live()) return;
        int input = operations.inputProof();
        if (!live()) return;
        int output = operations.outputProof();
        if (!live()) return;
        if (input < 0 || input > 1 || output < 0 || output > 1)
            throw new IllegalStateException("MEDIA_CALL_PROOF_INVALID");
        boolean capture = operations.captureFrames();
        if (!live()) return;
        boolean playback = operations.playbackFrames();
        protocol.mediaObserved(input == 1, output == 1, capture, playback);
    }
    private void publish() {
        String next = protocol.snapshot().toString();
        if (next.equals(published)) return;
        published = next;
        if (changed != null) try { changed.run(); } catch (Exception | LinkageError ignored) { }
    }
    /** 等待实际close与cancel返回；清理超时的终态不代表底层线程已退出。 */
    public boolean awaitClosed(long timeout) throws InterruptedException {
        return finished.await(Math.max(0, timeout), TimeUnit.MILLISECONDS);
    }
}
