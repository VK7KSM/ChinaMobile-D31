package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 真实WebSocket读写线程只接内存流；所有网络连接及DNS入口均明确拒绝。 */
public class WebSocketWorkerReleaseAuditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final class MemorySocket extends Socket {
        final CountDownLatch reading = new CountDownLatch(1), writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean blockWriter;
        volatile boolean closed;
        MemorySocket(boolean blockWriter) { this.blockWriter = blockWriter; }
        public boolean isConnected() { return true; }
        public boolean isClosed() { return closed; }
        public void close() { closed = true; }
        public void connect(SocketAddress address, int timeout) { throw new AssertionError("禁止网络连接"); }
        public void connect(SocketAddress address) { throw new AssertionError("禁止网络连接"); }
        public void setTcpNoDelay(boolean value) { }
        public void setReuseAddress(boolean value) { }
        public void setReceiveBufferSize(int value) { }
        private void waitRelease() throws IOException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean interrupted = false;
            try {
                while (release.getCount() != 0) {
                    long left = end - System.nanoTime();
                    if (left <= 0) throw new IOException("MEDIA_TEST_TIMEOUT");
                    try { release.await(left, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException stopped) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        public InputStream getInputStream() { return new InputStream() {
            public int read() throws IOException { reading.countDown(); waitRelease(); return -1; }
        }; }
        public OutputStream getOutputStream() { return new OutputStream() {
            public void write(int value) throws IOException { writing.countDown(); if (blockWriter) waitRelease(); }
            public void write(byte[] value, int offset, int length) throws IOException { write(0); }
        }; }
    }
    static Object field(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @Test public void readerStillRunningMustPreventLeaseRelease() throws Exception { verify(false); }
    @Test public void writerStillRunningMustPreventLeaseRelease() throws Exception { verify(true); }
    private void verify(boolean blockWriter) throws Exception {
        MemorySocket socket = new MemorySocket(blockWriter);
        WebSocketClient client = new WebSocketClient(new URI("ws://offline.invalid/audit")) {
            public void onOpen(ServerHandshake value) { }
            public void onMessage(String value) { }
            public void onClose(int code, String reason, boolean remote) { }
            public void onError(Exception failure) { }
        };
        client.setDnsResolver(uri -> { throw new AssertionError("禁止DNS查询"); });
        client.setSocket(socket);
        MediaWebSocket real = new MediaWebSocket();
        field(real, MediaWebSocket.class, "opening", socket);
        field(real, MediaWebSocket.class, "client", client);
        CountDownLatch connected = new CountDownLatch(1);
        MicrophoneSession.Transport transport = new MicrophoneSession.Transport() {
            public void connect(RtcOffer offer, MicrophoneSession.Events events) { client.connect(); connected.countDown(); }
            public void send(JSONObject value) { }
            public void abort() { real.abort(); }
            public void close() throws Exception { real.close(); }
        };
        Constructor<RtcOffer> ctor = RtcOffer.class.getDeclaredConstructor(String.class, URI.class,
                long.class, String.class, String.class, String.class, JSONObject.class);
        ctor.setAccessible(true);
        RtcOffer offer = ctor.newInstance("offline-worker", new URI("wss://control.example/api/elfremote/media/device?session_id=offline-worker"),
                System.currentTimeMillis() + 45000L, "synthetic_test_token_1234", "call", "front", null);
        File files = temporary.newFolder();
        AppCallSession session = new AppCallSession(files, offer, MediaCapture.SYSTEM_CLOCK, transport,
                (sender, signals) -> { throw new IOException("MEDIA_TEST_FACTORY_UNEXPECTED"); });
        Thread reader = null, writer = null;
        try {
            session.start(); assertTrue(connected.await(2, TimeUnit.SECONDS));
            assertTrue(socket.reading.await(2, TimeUnit.SECONDS)); assertTrue(socket.writing.await(2, TimeUnit.SECONDS));
            reader = (Thread)field(client, WebSocketClient.class, "connectReadThread");
            writer = (Thread)field(client, WebSocketClient.class, "writeThread");
            assertNotNull(reader); assertNotNull(writer);
            session.stop(); assertTrue(session.awaitClosed(2500));
            boolean alive = (blockWriter ? writer : reader).isAlive();
            boolean unlocked;
            try (MediaFiles.Lease ignored = MediaFiles.lease(new File(files, "media"))) { unlocked = true; }
            catch (IOException occupied) { unlocked = false; }
            System.out.println("检查=" + (blockWriter ? "写线程" : "读线程") + ",线程仍存活=" + alive
                    + ",Socket已关闭=" + socket.isClosed() + ",cleanup_complete=" + session.snapshot().getBoolean("cleanup_complete")
                    + ",媒体锁已释放=" + unlocked);
            assertTrue("夹具必须仍持有真实库工作线程", alive);
            assertFalse("真实WebSocket工作线程未退出时不得释放媒体锁", unlocked);
        } finally {
            session.stop(); socket.release.countDown();
            if (reader == null) reader = (Thread)field(client, WebSocketClient.class, "connectReadThread");
            if (writer == null) writer = (Thread)field(client, WebSocketClient.class, "writeThread");
            if (reader != null) { reader.join(2000); assertFalse("读线程夹具未退出", reader.isAlive()); }
            if (writer != null) { writer.join(2000); assertFalse("写线程夹具未退出", writer.isAlive()); }
            MediaFiles.Lease held = (MediaFiles.Lease)field(session, AppCallSession.class, "lease");
            if (held != null) held.close();
        }
    }
}
