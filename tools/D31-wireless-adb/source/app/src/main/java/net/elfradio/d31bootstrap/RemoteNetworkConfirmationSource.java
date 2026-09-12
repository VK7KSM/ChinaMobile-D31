package net.elfradio.d31bootstrap;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.management.NetworkConfirmationDispatch;

/** 独立确认进程专用；只读既有身份，不注册、不保存凭据或回执。 */
public final class RemoteNetworkConfirmationSource implements NetworkConfirmationDispatch.Source, AutoCloseable {
    static final int MAX_BYTES = 16384;
    private static final AtomicReference<Attempt> PROCESS_SLOT = new AtomicReference<>();
    interface Clock { long elapsed(); long wall(); }
    interface Connections { HttpsURLConnection open() throws Exception; }
    private final String deviceId, token, cloudTaskId, requestDigest;
    private final Clock clock;
    private final Connections connections;
    private final AtomicReference<Attempt> slot;
    private volatile long retryAt;
    private volatile boolean closed;

    public RemoteNetworkConfirmationSource(JSONObject identity, String cloudTaskId, String requestDigest) throws IOException {
        this(identity, cloudTaskId, requestDigest, new Clock() {
            public long elapsed() { return SystemClock.elapsedRealtime(); }
            public long wall() { return System.currentTimeMillis(); }
        }, () -> (HttpsURLConnection) new URL(RemoteProtocol.BASE + "/api/elfremote/task-progress").openConnection(), PROCESS_SLOT);
    }

    RemoteNetworkConfirmationSource(JSONObject identity, String cloudTaskId, String requestDigest,
                                    Clock clock, Connections connections, AtomicReference<Attempt> slot) throws IOException {
        this.deviceId = RemoteNetworkConfirmationJson.string(identity, "device_id", "[A-Za-z0-9_-]{1,128}");
        this.token = RemoteNetworkConfirmationJson.string(identity, "token", "[a-f0-9]{64}");
        if (cloudTaskId == null || !cloudTaskId.matches("[A-Za-z0-9_-]{1,96}")
                || requestDigest == null || !requestDigest.matches("[a-f0-9]{64}")
                || clock == null || connections == null || slot == null) throw failure("INPUT");
        this.cloudTaskId = cloudTaskId; this.requestDigest = requestDigest;
        this.clock = clock; this.connections = connections; this.slot = slot;
    }

    /** 当前实例的剩余退避；不得据此延长原事务截止时间。 */
    public long retryAfterMs() { return Math.max(0, retryAt - clock.elapsed()); }

    @Override public NetworkConfirmationDispatch.Receipt fetchVerified(NetworkConfirmationDispatch.Request request,
                                                                       long maxWaitMs) throws Exception {
        long beganNanos = System.nanoTime();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NETWORK_CONFIRM_CANCELLED");
        if (closed) throw failure("CLOSED");
        if (request == null || request.original == null || maxWaitMs <= 0) throw failure("INPUT");
        long now = clock.elapsed();
        if (now < request.issuedElapsed || now < request.original.lastElapsed
                || now >= request.original.deadlineElapsed) throw failure("DEADLINE");
        Budget budget = new Budget(clock, now, beganNanos,
                Math.min(3000, Math.min(maxWaitMs, request.original.deadlineElapsed - now)));
        if (retryAfterMs() > 0) return null;
        Attempt attempt = new Attempt(this, budget, request);
        if (!slot.compareAndSet(null, attempt)) throw failure("BUSY");
        NetworkConfirmationDispatch.Receipt receipt;
        try {
            if (closed) throw failure("CLOSED");
            attempt.thread.start();
            int left = budget.remaining();
            // 为取消及线程收集保留预算；DNS、写入及清理均共用原始总期限。
            receipt = attempt.future.get(Math.max(1, left - Math.min(100, left / 4)), TimeUnit.MILLISECONDS);
            budget.check();
            if (closed) throw failure("CLOSED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new InterruptedException("NETWORK_CONFIRM_CANCELLED");
        } catch (TimeoutException expired) { throw failure("TIMEOUT"); }
        catch (ExecutionException failed) { throw failure("UNAVAILABLE"); }
        catch (java.util.concurrent.CancellationException cancelled) { throw failure("CANCELLED"); }
        finally {
            attempt.cancel();
            if (attempt.thread.getState() == Thread.State.NEW) slot.compareAndSet(attempt, null);
            else attempt.collect();
        }
        budget.check();
        if (clock.elapsed() >= request.original.deadlineElapsed) throw failure("DEADLINE");
        if (closed || !attempt.workerDone || !attempt.cleanupDone) throw failure("CLEANUP");
        return receipt;
    }

    @Override public void close() {
        closed = true;
        Attempt attempt = slot.get();
        if (attempt != null && attempt.owner == this) attempt.cancel();
    }

    static final class Budget {
        private final Clock clock;
        private final long start, nanos, duration;
        Budget(Clock clock, long start, long nanos, long duration) throws IOException {
            if (duration <= 0 || duration > 3000) throw failure("BUDGET");
            this.clock = clock; this.start = start; this.nanos = nanos; this.duration = duration;
        }
        int remaining() throws IOException, InterruptedException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NETWORK_CONFIRM_CANCELLED");
            long delta = clock.elapsed() - start;
            long real = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
            long remaining = duration - Math.max(delta, real);
            if (delta < 0 || remaining <= 0) throw failure("TIMEOUT");
            return (int) remaining;
        }
        void check() throws IOException, InterruptedException { remaining(); }
    }

    /** 即使DNS或disconnect忽略中断，槽也保持占用，直至工作和清理实际结束。 */
    static final class Attempt {
        final RemoteNetworkConfirmationSource owner;
        final Budget budget;
        final FutureTask<NetworkConfirmationDispatch.Receipt> future;
        final Thread thread;
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean closing = new AtomicBoolean();
        volatile HttpsURLConnection connection;
        volatile boolean workerDone, cleanupDone;
        Attempt(RemoteNetworkConfirmationSource owner, Budget budget, NetworkConfirmationDispatch.Request request) {
            this.owner = owner; this.budget = budget;
            future = new FutureTask<>(() -> owner.exchange(this, request));
            thread = new Thread(() -> {
                try { future.run(); }
                finally {
                    try { cleanup(); }
                    finally { workerDone = true; release(); }
                }
            }, "network-confirm-https");
            thread.setDaemon(true);
        }
        void cancel() {
            future.cancel(true);
            // disconnect可能阻塞，不能在预算等待者或持锁的监督线程执行。
            if (connection != null && closing.compareAndSet(false, true)) {
                Thread closer = new Thread(this::disconnect, "network-confirm-close");
                closer.setDaemon(true); closer.start();
            }
        }
        void cleanup() {
            if (closing.compareAndSet(false, true)) disconnect();
        }
        void disconnect() {
            try { if (connection != null) connection.disconnect(); }
            catch (RuntimeException ignored) { }
            finally { cleanupDone = true; release(); }
        }
        void release() {
            if (workerDone && cleanupDone) {
                owner.slot.compareAndSet(this, null);
                finished.countDown();
            }
        }
        void collect() {
            boolean interrupted = Thread.interrupted();
            try { finished.await(budget.remaining(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException cancelled) { interrupted = true; }
            catch (IOException expired) { /* 总期限已到，保留占用槽，禁止并发遗留请求。 */ }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private NetworkConfirmationDispatch.Receipt exchange(Attempt attempt, NetworkConfirmationDispatch.Request request) throws Exception {
        Budget budget = attempt.budget;
        budget.check();
        JSONObject body = RemoteNetworkConfirmationJson.encode(deviceId, token, cloudTaskId, requestDigest, request);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw failure("REQUEST_SIZE");
        budget.check();
        HttpsURLConnection c = connections.open();
        attempt.connection = c;
        budget.check();
        // 不使用URL.equals，避免比较端点本身触发DNS。
        if (c == null || !(RemoteProtocol.BASE + "/api/elfremote/task-progress").equals(c.getURL().toExternalForm()))
            throw failure("ENDPOINT");
        c.setSSLSocketFactory(RemoteTls.factory());
        budget.check();
        c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        c.setConnectTimeout(budget.remaining()); c.setReadTimeout(budget.remaining());
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            budget.check(); out.write(bytes); budget.check();
        }
        budget.check(); c.setReadTimeout(budget.remaining());
        int status = c.getResponseCode();
        budget.check();
        long delay = RemoteHttp.retryAfterDelay(c.getHeaderField("Retry-After"), clock.wall());
        budget.check();
        if (delay > 0) retryAt = clock.elapsed() + delay;
        if (status != 200) return null;
        String type = c.getContentType();
        if (type == null || !type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?\\s*")) throw failure("CONTENT_TYPE");
        String encoding = c.getHeaderField("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw failure("ENCODING");
        // D31 API23没有getContentLengthLong；手动解析头以免窄化超大长度。
        String declared = c.getHeaderField("Content-Length");
        long length = -1;
        if (declared != null) {
            if (!declared.matches("[0-9]{1,9}")) throw failure("RESPONSE_SIZE");
            length = Long.parseLong(declared);
        }
        if (length > MAX_BYTES) throw failure("RESPONSE_SIZE");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buffer = new byte[2048];
            while (true) {
                budget.check(); c.setReadTimeout(budget.remaining());
                int n = in.read(buffer);
                budget.check();
                if (n < 0) break;
                if (out.size() + n > MAX_BYTES) throw failure("RESPONSE_SIZE");
                out.write(buffer, 0, n);
            }
        }
        budget.check();
        if (length >= 0 && length != out.size()) throw failure("RESPONSE_LENGTH");
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
        NetworkConfirmationDispatch.Receipt receipt = RemoteNetworkConfirmationJson.decode(
                json, deviceId, cloudTaskId, requestDigest, request);
        budget.check();
        return receipt;
    }

    static IOException failure(String code) { return new IOException("NETWORK_CONFIRM_" + code); }
}
