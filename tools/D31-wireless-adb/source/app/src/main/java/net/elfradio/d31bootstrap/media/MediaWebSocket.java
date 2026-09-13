package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.java_websocket.AbstractWebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/** 保留既有TLS/Bearer合同；停止请求与Socket、开连及库工作线程的释放确认分离。 */
public final class MediaWebSocket implements MicrophoneSession.Transport {
    static final long RELEASE_MS = 1500;
    interface Connector {
        Socket create() throws Exception;
        void connect(Socket socket, RtcOffer offer) throws Exception;
        Socket wrap(Socket plain, RtcOffer offer) throws Exception;
        void verify(Socket secure, RtcOffer offer) throws Exception;
        default void start(WebSocketClient client) throws Exception { client.connect(); }
    }
    private static final Connector NETWORK = new Connector() {
        public Socket create() { return new Socket(); }
        public void connect(Socket socket, RtcOffer offer) throws Exception {
            int port = offer.uri.getPort() < 0 ? 443 : offer.uri.getPort();
            socket.connect(new InetSocketAddress(offer.uri.getHost(), port), 10000);
        }
        public Socket wrap(Socket plain, RtcOffer offer) throws Exception {
            int port = offer.uri.getPort() < 0 ? 443 : offer.uri.getPort();
            return net.elfradio.d31bootstrap.RemoteTls.factory().createSocket(plain, offer.uri.getHost(), port, true);
        }
        public void verify(Socket secure, RtcOffer offer) throws Exception {
            SSLSocket tls = (SSLSocket)secure;
            tls.setSoTimeout(10000); tls.startHandshake();
            verifyPeer(offer.uri.getHost(), tls.getSession(), HttpsURLConnection.getDefaultHostnameVerifier());
            tls.setSoTimeout(0);
        }
    };
    private static final class OwnedSocket {
        final Socket socket;
        boolean claimed, done;
        OwnedSocket(Socket socket) { this.socket = socket; }
    }
    /** 只匹配固定1.5.7结构；先join开连/读线程，再取得其最后发布的写线程。 */
    private static final class Threads {
        final Field reader = member(WebSocketClient.class, "connectReadThread", Thread.class);
        final Field writer = member(WebSocketClient.class, "writeThread", Thread.class);
        final Field timer = member(AbstractWebSocket.class, "connectionLostCheckerService", java.util.concurrent.ScheduledExecutorService.class);
        final Field timerLock = member(AbstractWebSocket.class, "syncConnectionLost", Object.class);
        static Field member(Class<?> type, String name, Class<?> value) {
            try {
                Field field = type.getDeclaredField(name);
                if (field.getType() != value || Modifier.isStatic(field.getModifiers())) throw new IllegalStateException();
                field.setAccessible(true); return field;
            } catch (Exception failure) { throw new IllegalStateException("MEDIA_TRANSPORT_LIBRARY_UNSUPPORTED", failure); }
        }
        Thread reader(WebSocketClient value) throws Exception { return (Thread)reader.get(value); }
        Thread writer(WebSocketClient value) throws Exception { return (Thread)writer.get(value); }
    }
    private final Object state = new Object();
    private final Connector connector;
    private final long releaseMs;
    private final List<OwnedSocket> sockets = new ArrayList<>();
    private final List<ExecutorService> timers = new ArrayList<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ThreadLocal<Integer> activeDepth = new ThreadLocal<>();
    private WebSocketClient client;
    private Socket opening;
    private volatile boolean closed;
    private boolean started, connecting;
    private int activeCalls;
    private Thread opener, closer;
    private long deadline;
    private volatile Throwable releaseFailure;
    private Threads threads;

    public MediaWebSocket() { this(NETWORK, RELEASE_MS); }
    MediaWebSocket(Connector connector, long releaseMs) {
        if (connector == null || releaseMs <= 0 || releaseMs > RELEASE_MS) throw new IllegalArgumentException();
        this.connector = connector; this.releaseMs = releaseMs;
    }
    public void connect(RtcOffer offer, final MicrophoneSession.Events events) throws Exception {
        synchronized (state) {
            if (closed || started) throw new IOException("MEDIA_SOCKET_NOT_REUSABLE");
            started = true; connecting = true; opener = Thread.currentThread();
        }
        try {
            Threads shape = new Threads();
            synchronized (state) { threads = shape; }
            Socket plain = connector.create(); own(plain); check();
            connector.connect(plain, offer); check();
            Socket tls = connector.wrap(plain, offer); own(tls); check();
            connector.verify(tls, offer); check();
            WebSocketClient created = new WebSocketClient(offer.uri, Collections.singletonMap("Authorization", "Bearer " + offer.token)) {
                // 默认实现调用API24方法；鉴权写出前已经独立校验主机名。
                protected void onSetSSLParameters(SSLParameters parameters) { }
                public void onOpen(ServerHandshake handshake) { }
                public void onMessage(String raw) { dispatch(() -> events.message(raw)); }
                public void onClose(int code, String reason, boolean remote) { dispatch(events::disconnected); }
                public void onError(Exception failure) { dispatch(events::disconnected); }
                protected void startConnectionLostTimer() {
                    try {
                        synchronized (shape.timerLock.get(this)) {
                            if (!closed) { super.startConnectionLostTimer(); rememberTimer(this, shape); }
                        }
                    } catch (Exception failure) { fail(failure); abort(); }
                }
                protected void stopConnectionLostTimer() {
                    try {
                        synchronized (shape.timerLock.get(this)) { rememberTimer(this, shape); super.stopConnectionLostTimer(); }
                    } catch (Exception failure) { fail(failure); abort(); }
                }
            };
            created.setSocket(tls); created.setConnectionLostTimeout(20);
            synchronized (state) { check(); client = created; }
            // connecting在实际start返回前不会清除；取消与线程尚未启动的窗口不能提前确认释放。
            connector.start(created); check();
        } catch (Exception | LinkageError failure) {
            abort(); throw new IOException("MEDIA_TLS_CONNECT_FAILED", failure);
        } finally {
            synchronized (state) { connecting = false; opener = null; state.notifyAll(); }
        }
    }
    private void check() throws IOException { if (closed) throw new IOException("MEDIA_SOCKET_CLOSED"); }
    private void own(Socket socket) throws Exception {
        if (socket == null) throw new IOException("MEDIA_SOCKET_MISSING");
        OwnedSocket owned;
        synchronized (state) { opening = socket; owned = ownLocked(socket); state.notifyAll(); }
        // 释放线程超时后才出现的包装也由原开连线程回收，不创建补偿线程。
        if (closed) closeSocket(owned);
    }
    private OwnedSocket ownLocked(Socket socket) {
        for (OwnedSocket owned : sockets) if (owned.socket == socket) return owned;
        OwnedSocket owned = new OwnedSocket(socket); sockets.add(owned); return owned;
    }
    private boolean enter() {
        synchronized (state) {
            if (closed) return false;
            activeCalls++; Integer depth = activeDepth.get(); activeDepth.set(depth == null ? 1 : depth + 1); return true;
        }
    }
    private void leave() {
        synchronized (state) {
            activeCalls--; int depth = activeDepth.get() - 1;
            if (depth == 0) activeDepth.remove(); else activeDepth.set(depth);
            state.notifyAll();
        }
    }
    private void dispatch(Runnable action) { if (!enter()) return; try { action.run(); } finally { leave(); } }
    public void send(JSONObject message) throws Exception {
        if (!enter()) throw new IOException("MEDIA_SOCKET_CLOSED");
        try {
            WebSocketClient current; synchronized (state) { current = client; }
            if (closed || current == null || !current.isOpen()) throw new IOException("MEDIA_SOCKET_CLOSED");
            current.send(message.toString());
        } finally { leave(); }
    }
    public void abort() {
        Thread launch;
        synchronized (state) {
            closed = true;
            if (closer != null) return;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(releaseMs);
            closer = launch = new Thread(this::release, "d31-media-transport-release"); launch.setDaemon(true);
            state.notifyAll();
        }
        try { launch.start(); }
        catch (RuntimeException | LinkageError failure) { fail(failure); finished.countDown(); }
    }
    public void close() throws IOException {
        abort();
        synchronized (state) {
            if (Thread.currentThread() == closer || Thread.currentThread() == opener || activeDepth.get() != null)
                throw new IOException("MEDIA_TRANSPORT_CLOSE_REENTRANT");
        }
        try {
            if (!finished.await(remaining(), TimeUnit.NANOSECONDS)) fail(new IOException("MEDIA_TRANSPORT_RELEASE_TIMEOUT"));
            if (finished.getCount() == 0) join(closer);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("MEDIA_TRANSPORT_CLOSE_INTERRUPTED", interrupted);
        } catch (Exception failure) { fail(failure); }
        Throwable failure = releaseFailure;
        if (failure != null) throw new IOException("MEDIA_TRANSPORT_RELEASE_UNCONFIRMED", failure);
    }
    private long remaining() { return Math.max(0, deadline - System.nanoTime()); }
    private void requireTime() throws IOException { if (remaining() == 0) throw new IOException("MEDIA_TRANSPORT_RELEASE_TIMEOUT"); }
    private void join(Thread thread) throws Exception {
        if (thread == null || !thread.isAlive()) return;
        if (thread == Thread.currentThread()) throw new IOException("MEDIA_TRANSPORT_CLOSE_REENTRANT");
        long left = remaining(); if (left > 0) TimeUnit.NANOSECONDS.timedJoin(thread, left);
        if (thread.isAlive()) throw new IOException("MEDIA_TRANSPORT_THREAD_RELEASE_UNCONFIRMED");
    }
    private void fail(Throwable failure) {
        synchronized (state) {
            if (releaseFailure == null) releaseFailure = failure;
            else if (releaseFailure != failure && releaseFailure.getSuppressed().length < 8) releaseFailure.addSuppressed(failure);
        }
    }
    private void closeSocket(OwnedSocket owned) {
        synchronized (state) { if (owned.claimed) return; owned.claimed = true; }
        try {
            owned.socket.close();
            if (!owned.socket.isClosed()) throw new IOException("MEDIA_SOCKET_RELEASE_UNCONFIRMED");
        } catch (Exception | LinkageError failure) { fail(failure); }
        finally { synchronized (state) { owned.done = true; state.notifyAll(); } }
    }
    private void rememberTimer(WebSocketClient value, Threads shape) throws Exception {
        ExecutorService timer = (ExecutorService)shape.timer.get(value);
        synchronized (state) { if (timer != null && !timers.contains(timer)) timers.add(timer); }
    }
    private void release() {
        try {
            // 先关闭套接字以打断DNS之后的connect/TLS/read/write；外部调用从不持有state锁。
            for (;;) {
                OwnedSocket pending = null;
                synchronized (state) {
                    if (opening != null) ownLocked(opening);
                    for (OwnedSocket owned : sockets) if (!owned.claimed) { pending = owned; break; }
                    if (pending == null) {
                        boolean done = !connecting;
                        for (OwnedSocket owned : sockets) done &= owned.done;
                        if (done) break;
                        requireTime(); TimeUnit.NANOSECONDS.timedWait(state, remaining()); continue;
                    }
                }
                closeSocket(pending);
            }
            WebSocketClient current; Threads shape;
            synchronized (state) { current = client; shape = threads; }
            if (current != null) {
                if (shape == null) shape = new Threads();
                synchronized (shape.timerLock.get(current)) { rememberTimer(current, shape); current.setConnectionLostTimeout(0); }
                try { current.close(); } catch (Exception | LinkageError failure) { fail(failure); }
                join(shape.reader(current));
                join(shape.writer(current));
                rememberTimer(current, shape);
            }
            synchronized (state) {
                while (activeCalls != 0) { requireTime(); TimeUnit.NANOSECONDS.timedWait(state, remaining()); }
            }
            List<ExecutorService> pendingTimers; synchronized (state) { pendingTimers = new ArrayList<>(timers); }
            for (ExecutorService timer : pendingTimers) {
                timer.shutdownNow();
                if (!timer.awaitTermination(remaining(), TimeUnit.NANOSECONDS)) throw new IOException("MEDIA_TRANSPORT_TIMER_RELEASE_UNCONFIRMED");
            }
            requireTime();
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            fail(failure);
        } finally { finished.countDown(); }
    }
    static void verifyPeer(String host, SSLSession session, HostnameVerifier verifier) throws IOException {
        if (session == null || verifier == null || !verifier.verify(host, session)) throw new IOException("MEDIA_TLS_HOST_REJECTED");
    }
}
