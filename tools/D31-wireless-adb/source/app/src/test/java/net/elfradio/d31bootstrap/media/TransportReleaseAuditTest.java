package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 只注入关闭失败；不调用生产connect，不建立网络连接。断言要求真实释放合同。 */
public class TransportReleaseAuditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final class FailedSocket extends Socket {
        final AtomicInteger attempts = new AtomicInteger();
        @Override public void close() throws IOException {
            attempts.incrementAndGet();
            throw new IOException("offline injected close failure");
        }
    }
    static MediaWebSocket wire(FailedSocket socket) throws Exception {
        MediaWebSocket wire = new MediaWebSocket();
        Field field = MediaWebSocket.class.getDeclaredField("opening");
        field.setAccessible(true); field.set(wire, socket);
        return wire;
    }
    @Test public void failedSocketReleaseMustRemainObservableAfterAbort() throws Exception {
        FailedSocket socket = new FailedSocket();
        MicrophoneSession.Transport transport = wire(socket);
        try { transport.abort(); } catch (RuntimeException expected) { }
        boolean rejected = false;
        try { transport.close(); } catch (Exception expected) { rejected = true; }
        assertEquals(1, socket.attempts.get());
        assertFalse(socket.isClosed());
        assertTrue("Socket释放未确认必须传播，不能因已请求closed而吞错", rejected);
    }
    @Test public void callMustKeepMediaLockWhenRealTransportCloseFails() throws Exception {
        FailedSocket socket = new FailedSocket();
        MediaWebSocket real = wire(socket);
        CountDownLatch connected = new CountDownLatch(1);
        MicrophoneSession.Transport transport = new MicrophoneSession.Transport() {
            public void connect(RtcOffer offer, MicrophoneSession.Events events) { connected.countDown(); }
            public void send(JSONObject value) { }
            public void abort() { real.abort(); }
            public void close() throws Exception { real.close(); }
        };
        Constructor<RtcOffer> ctor = RtcOffer.class.getDeclaredConstructor(String.class, URI.class,
                long.class, String.class, String.class, String.class, JSONObject.class);
        ctor.setAccessible(true);
        RtcOffer offer = ctor.newInstance("offline-audit", new URI("wss://control.example/api/elfremote/media/device?session_id=offline-audit"),
                System.currentTimeMillis() + 45000L, "synthetic_test_token_1234", "call", "front", null);
        File files = temporary.newFolder();
        AppCallSession session = new AppCallSession(files, offer, MediaCapture.SYSTEM_CLOCK, transport,
                (sender, signals) -> { throw new IOException("MEDIA_TEST_FACTORY_UNEXPECTED"); });
        try {
            session.start(); assertTrue(connected.await(2, TimeUnit.SECONDS));
            session.stop(); assertTrue(session.awaitClosed(2000));
            boolean lockReleased;
            try (MediaFiles.Lease ignored = MediaFiles.lease(new File(files, "media"))) { lockReleased = true; }
            catch (IOException occupied) { lockReleased = false; }
            JSONObject result = session.snapshot();
            System.out.println("关闭尝试=" + socket.attempts.get() + ",Socket已关闭=" + socket.isClosed()
                    + ",cleanup_complete=" + result.getBoolean("cleanup_complete") + ",媒体锁已释放=" + lockReleased);
            assertFalse("实际传输关闭失败仍释放媒体锁", lockReleased);
            assertFalse(result.getBoolean("cleanup_complete"));
        } finally {
            session.stop();
            Field field = AppCallSession.class.getDeclaredField("lease"); field.setAccessible(true);
            MediaFiles.Lease held = (MediaFiles.Lease)field.get(session);
            if (held != null) held.close();
        }
    }
}
