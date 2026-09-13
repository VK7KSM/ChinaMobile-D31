package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.SSLSession;
import org.java_websocket.AbstractWebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.HandshakeImpl1Server;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** 固定库真实线程与内存Socket；不调用DNS、网络、Android音频或JNI。 */
public class MediaWebSocketReleaseTest {
    static Object read(Object value, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    static void write(Object value, Class<?> owner, String name, Object next) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(value, next);
    }
    static void await(CountDownLatch gate) throws IOException {
        try { if (!gate.await(4, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
    static final class MemorySocket extends Socket {
        final CountDownLatch readEntered = new CountDownLatch(1), io = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1), closePermit = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean closed, blockClose;
        volatile IOException error;
        public boolean isConnected() { return true; }
        public boolean isClosed() { return closed; }
        public void connect(SocketAddress address) { throw new AssertionError("禁止网络连接"); }
        public void connect(SocketAddress address, int timeout) { throw new AssertionError("禁止网络连接"); }
        public void setTcpNoDelay(boolean value) { }
        public void setReuseAddress(boolean value) { }
        public void setReceiveBufferSize(int value) { }
        public void close() throws IOException {
            closes.incrementAndGet(); closeEntered.countDown();
            if (blockClose) await(closePermit);
            if (error != null) throw error;
            closed = true; io.countDown();
        }
        public InputStream getInputStream() { return new InputStream() {
            public int read() throws IOException { readEntered.countDown(); await(io); return -1; }
        }; }
        public OutputStream getOutputStream() { return new OutputStream() { public void write(int value) { } }; }
        void release() { blockClose = false; closePermit.countDown(); io.countDown(); }
    }
    static final class Dialer implements MediaWebSocket.Connector {
        final MemorySocket plain = new MemorySocket(), secure = new MemorySocket();
        final CountDownLatch entered = new CountDownLatch(1), permit = new CountDownLatch(1);
        final AtomicInteger creates = new AtomicInteger(), connects = new AtomicInteger(), wraps = new AtomicInteger();
        final AtomicInteger verifies = new AtomicInteger(), starts = new AtomicInteger();
        String hold = "";
        boolean separateSecure, failVerify;
        void gate(String phase) throws Exception { if (hold.equals(phase)) { entered.countDown(); await(permit); } }
        public Socket create() throws Exception { creates.incrementAndGet(); gate("create"); return plain; }
        public void connect(Socket socket, RtcOffer offer) throws Exception { connects.incrementAndGet(); gate("connect"); }
        public Socket wrap(Socket socket, RtcOffer offer) throws Exception { wraps.incrementAndGet(); gate("wrap"); return separateSecure ? secure : plain; }
        public void verify(Socket socket, RtcOffer offer) throws Exception {
            verifies.incrementAndGet(); gate("verify"); if (failVerify) throw new IOException("MEDIA_TLS_HOST_REJECTED");
        }
        public void start(WebSocketClient client) throws Exception {
            starts.incrementAndGet(); client.setDnsResolver(uri -> { throw new AssertionError("禁止DNS查询"); });
            gate("start"); client.connect();
        }
    }
    static final class Events implements MicrophoneSession.Events {
        final AtomicInteger messages = new AtomicInteger(), disconnected = new AtomicInteger();
        Runnable onMessage = () -> { };
        public void message(String value) { messages.incrementAndGet(); onMessage.run(); }
        public void disconnected() { disconnected.incrementAndGet(); }
    }
    static RtcOffer offer() throws Exception {
        Constructor<RtcOffer> constructor = RtcOffer.class.getDeclaredConstructor(String.class, URI.class,
                long.class, String.class, String.class, String.class, JSONObject.class);
        constructor.setAccessible(true);
        return constructor.newInstance("offline-release", new URI("wss://offline.invalid/api/elfremote/media/device?session_id=offline-release"),
                System.currentTimeMillis() + 45000L, "synthetic_test_token_1234", "call", "front", null);
    }
    static final class Rig implements AutoCloseable {
        final Dialer dialer = new Dialer();
        final Events events = new Events();
        final ExecutorService callers = Executors.newFixedThreadPool(6);
        final MediaWebSocket wire;
        Rig() { this(1500); }
        Rig(long budget) { wire = new MediaWebSocket(dialer, budget); }
        Future<?> start() { return callers.submit(() -> { wire.connect(offer(), events); return null; }); }
        WebSocketClient client() throws Exception { return (WebSocketClient)read(wire, MediaWebSocket.class, "client"); }
        void running() throws Exception { start().get(2, TimeUnit.SECONDS); assertTrue(dialer.plain.readEntered.await(2, TimeUnit.SECONDS)); }
        Future<?> closing() { return callers.submit(() -> { wire.close(); return null; }); }
        public void close() throws Exception {
            dialer.permit.countDown(); dialer.plain.release(); dialer.secure.release();
            wire.abort(); try { wire.close(); } catch (IOException expected) { }
            callers.shutdown(); assertTrue("调用线程未退出", callers.awaitTermination(3, TimeUnit.SECONDS));
            WebSocketClient client = client();
            if (client != null) {
                for (String name : new String[]{"connectReadThread", "writeThread"}) {
                    Thread worker = (Thread)read(client, WebSocketClient.class, name);
                    if (worker != null) { worker.join(2000); assertFalse("库线程未退出", worker.isAlive()); }
                }
            }
            Thread closer = (Thread)read(wire, MediaWebSocket.class, "closer");
            if (closer != null) { closer.join(2000); assertFalse("清理线程未退出", closer.isAlive()); }
        }
    }
    static IOException rejected(MediaWebSocket wire) throws Exception {
        try { wire.close(); throw new AssertionError("必须拒绝未确认释放"); }
        catch (IOException failure) { return failure; }
    }
    static void cancelled(Future<?> start) throws Exception {
        try { start.get(2, TimeUnit.SECONDS); fail("取消后不得成功开连"); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
    }
    @Test public void unopenedCloseIsIdempotentAndNeverAllocatesNetwork() throws Exception {
        try (Rig rig = new Rig()) {
            rig.wire.abort(); rig.wire.close(); rig.wire.close(); assertEquals(0, rig.dialer.creates.get());
            cancelled(rig.start()); assertEquals(0, rig.dialer.creates.get());
        }
    }
    @Test public void concurrentAbortAndCloseShareOnePhysicalResult() throws Exception {
        try (Rig rig = new Rig()) {
            rig.dialer.plain.blockClose = true;
            write(rig.wire, MediaWebSocket.class, "opening", rig.dialer.plain);
            long began = System.nanoTime(); rig.wire.abort();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 200);
            assertTrue(rig.dialer.plain.closeEntered.await(1, TimeUnit.SECONDS));
            Thread closer = (Thread)read(rig.wire, MediaWebSocket.class, "closer");
            List<Future<?>> results = new ArrayList<>();
            for (int n = 0; n < 6; n++) results.add(rig.closing());
            rig.wire.abort(); assertSame(closer, read(rig.wire, MediaWebSocket.class, "closer"));
            for (Future<?> result : results) assertFalse(result.isDone());
            rig.dialer.plain.closePermit.countDown();
            for (Future<?> result : results) result.get(2, TimeUnit.SECONDS);
            assertEquals(1, rig.dialer.plain.closes.get()); assertTrue(rig.dialer.plain.isClosed());
        }
    }
    @Test public void originalSocketFailureSurvivesAbortAndEveryRepeatedClose() throws Exception {
        try (Rig rig = new Rig()) {
            IOException original = new IOException("offline original failure"); rig.dialer.plain.error = original;
            write(rig.wire, MediaWebSocket.class, "opening", rig.dialer.plain); rig.wire.abort();
            assertSame(original, rejected(rig.wire).getCause()); assertSame(original, rejected(rig.wire).getCause());
            assertEquals(1, rig.dialer.plain.closes.get());
        }
    }
    @Test public void ordinaryCloseConfirmsActualLibraryThreadsTerminated() throws Exception {
        try (Rig rig = new Rig()) {
            rig.running(); WebSocketClient client = rig.client(); rig.wire.close();
            for (String name : new String[]{"connectReadThread", "writeThread"}) {
                Thread worker = (Thread)read(client, WebSocketClient.class, name); assertNotNull(worker); assertFalse(worker.isAlive());
            }
        }
    }
    @Test public void stopSuppressesAllLateMessageAndDisconnectCallbacks() throws Exception {
        try (Rig rig = new Rig()) {
            rig.running(); WebSocketClient client = rig.client(); client.onMessage("before");
            rig.wire.abort(); client.onMessage("late"); client.onClose(1000, "late", true); client.onError(new IOException());
            rig.wire.close(); assertEquals(1, rig.events.messages.get()); assertEquals(0, rig.events.disconnected.get());
        }
    }
    @Test public void admittedCallbackMustReturnBeforeCloseSucceeds() throws Exception {
        try (Rig rig = new Rig()) {
            CountDownLatch inside = new CountDownLatch(1), leave = new CountDownLatch(1);
            rig.events.onMessage = () -> { inside.countDown(); try { await(leave); } catch (IOException e) { throw new RuntimeException(e); } };
            rig.running(); Future<?> callback = rig.callers.submit(() -> { rig.client().onMessage("held"); return null; });
            assertTrue(inside.await(1, TimeUnit.SECONDS)); Future<?> closing = rig.closing();
            assertTrue(rig.dialer.plain.closeEntered.await(1, TimeUnit.SECONDS)); assertFalse(closing.isDone());
            leave.countDown(); callback.get(2, TimeUnit.SECONDS); closing.get(2, TimeUnit.SECONDS);
        }
    }
    @Test public void reentrantCallbackCloseRequestsStopWithoutWaitingForItself() throws Exception {
        try (Rig rig = new Rig()) {
            AtomicReference<IOException> rejected = new AtomicReference<>();
            rig.events.onMessage = () -> { try { rig.wire.close(); } catch (IOException expected) { rejected.set(expected); } };
            rig.running(); long began = System.nanoTime(); rig.client().onMessage("reentrant");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 200);
            assertNotNull(rejected.get()); assertEquals("MEDIA_TRANSPORT_CLOSE_REENTRANT", rejected.get().getMessage());
            rig.wire.close();
        }
    }
    @Test public void socketCreatedAfterStopIsStillClosedBeforeConfirmation() throws Exception {
        try (Rig rig = new Rig()) {
            rig.dialer.hold = "create"; Future<?> opening = rig.start(); assertTrue(rig.dialer.entered.await(1, TimeUnit.SECONDS));
            rig.wire.abort(); Future<?> closing = rig.closing(); assertFalse(closing.isDone()); rig.dialer.permit.countDown();
            cancelled(opening); closing.get(2, TimeUnit.SECONDS);
            assertTrue(rig.dialer.plain.isClosed()); assertEquals(0, rig.dialer.connects.get());
        }
    }
    @Test public void lateTlsWrapperIsNotLostWhenPlainSocketAlreadyClosed() throws Exception {
        try (Rig rig = new Rig()) {
            rig.dialer.hold = "wrap"; rig.dialer.separateSecure = true;
            Future<?> opening = rig.start(); assertTrue(rig.dialer.entered.await(1, TimeUnit.SECONDS));
            rig.wire.abort(); assertTrue(rig.dialer.plain.closeEntered.await(1, TimeUnit.SECONDS));
            Future<?> closing = rig.closing(); assertFalse(closing.isDone()); rig.dialer.permit.countDown();
            cancelled(opening); closing.get(2, TimeUnit.SECONDS);
            assertTrue(rig.dialer.plain.isClosed()); assertTrue(rig.dialer.secure.isClosed()); assertEquals(0, rig.dialer.verifies.get());
        }
    }
    @Test public void pendingClientStartCannotOutliveSuccessfulClose() throws Exception {
        try (Rig rig = new Rig()) {
            rig.dialer.hold = "start"; Future<?> opening = rig.start(); assertTrue(rig.dialer.entered.await(1, TimeUnit.SECONDS));
            rig.wire.abort(); assertTrue(rig.dialer.plain.closeEntered.await(1, TimeUnit.SECONDS));
            Future<?> closing = rig.closing(); assertFalse(closing.isDone()); rig.dialer.permit.countDown();
            cancelled(opening); closing.get(2, TimeUnit.SECONDS);
            assertFalse(((Thread)read(rig.client(), WebSocketClient.class, "connectReadThread")).isAlive());
        }
    }
    @Test public void openingTimeoutIsBoundedAndNeverUpgradedAfterLateReturn() throws Exception {
        try (Rig rig = new Rig(180)) {
            rig.dialer.hold = "connect"; Future<?> opening = rig.start(); assertTrue(rig.dialer.entered.await(1, TimeUnit.SECONDS));
            long began = System.nanoTime(); IOException first = rejected(rig.wire);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 700);
            rig.dialer.permit.countDown(); cancelled(opening);
            assertSame(first.getCause(), rejected(rig.wire).getCause()); assertEquals(0, rig.dialer.wraps.get());
        }
    }
    @Test public void stalledSocketCloseIsBoundedAndFailureRemainsSticky() throws Exception {
        try (Rig rig = new Rig(180)) {
            rig.dialer.plain.blockClose = true; write(rig.wire, MediaWebSocket.class, "opening", rig.dialer.plain);
            long began = System.nanoTime(); IOException first = rejected(rig.wire);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 700);
            rig.dialer.plain.closePermit.countDown();
            assertSame(first.getCause(), rejected(rig.wire).getCause());
        }
    }
    @Test public void failedTlsVerificationClosesBothSocketsAndNeverStartsClient() throws Exception {
        try (Rig rig = new Rig()) {
            rig.dialer.separateSecure = true; rig.dialer.failVerify = true; cancelled(rig.start()); rig.wire.close();
            assertTrue(rig.dialer.plain.isClosed()); assertTrue(rig.dialer.secure.isClosed()); assertEquals(0, rig.dialer.starts.get());
        }
    }
    @Test public void interruptRestoresCallerFlagWithoutCancellingSharedCleanup() throws Exception {
        try (Rig rig = new Rig()) {
            rig.dialer.plain.blockClose = true; write(rig.wire, MediaWebSocket.class, "opening", rig.dialer.plain);
            AtomicBoolean flag = new AtomicBoolean(); AtomicReference<String> error = new AtomicReference<>();
            Thread caller = new Thread(() -> { try { rig.wire.close(); } catch (IOException interrupted) {
                flag.set(Thread.currentThread().isInterrupted()); error.set(interrupted.getMessage());
            } });
            caller.start(); assertTrue(rig.dialer.plain.closeEntered.await(1, TimeUnit.SECONDS)); caller.interrupt(); caller.join(1000);
            assertFalse(caller.isAlive()); assertTrue(flag.get()); assertEquals("MEDIA_TRANSPORT_CLOSE_INTERRUPTED", error.get());
            rig.dialer.plain.closePermit.countDown(); rig.wire.close();
        }
    }
    @Test public void libraryHeartbeatExecutorIsTerminatedAlongsideReaderAndWriter() throws Exception {
        try (Rig rig = new Rig()) {
            rig.running(); WebSocketClient client = rig.client();
            client.onWebsocketOpen(client.getConnection(), new HandshakeImpl1Server());
            ExecutorService timer = (ExecutorService)read(client, AbstractWebSocket.class, "connectionLostCheckerService");
            assertNotNull(timer); rig.wire.close(); assertTrue(timer.isTerminated());
        }
    }
    @Test public void tlsHostnameVerificationStillRejectsMissingAndWrongPeer() throws Exception {
        SSLSession session = (SSLSession)java.lang.reflect.Proxy.newProxyInstance(SSLSession.class.getClassLoader(), new Class<?>[]{SSLSession.class},
                (proxy, method, args) -> { throw new AssertionError("验证器不应读取会话内容"); });
        MediaWebSocket.verifyPeer("offline.invalid", session, (host, peer) -> true);
        for (SSLSession value : new SSLSession[]{null, session}) {
            try { MediaWebSocket.verifyPeer("offline.invalid", value, (host, peer) -> false); fail(); }
            catch (IOException expected) { assertEquals("MEDIA_TLS_HOST_REJECTED", expected.getMessage()); }
        }
    }
}
