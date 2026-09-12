package net.elfradio.d31bootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import net.elfradio.d31bootstrap.management.NetworkConfirmationDispatch;
import net.elfradio.d31bootstrap.management.NetworkRecoveryDispatch;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 全部使用合成身份与不联网替身；阻塞测试自行释放并收集线程。 */
public class RemoteNetworkConfirmationSourceTest {
    private static final String DEVICE = "demo-device", CLOUD = "network-demo";
    private static final String HASH = repeat('a', 64), DIGEST = repeat('b', 64), TOKEN = repeat('d', 64);
    private static final String BOOT = "00000000-0000-4000-8000-000000000001";
    private static final String NONCE = "00000000-0000-4000-8000-000000000002";
    private static final String ENDPOINT = RemoteProtocol.BASE + "/api/elfremote/task-progress";

    private static String repeat(char c, int n) { char[] chars = new char[n]; java.util.Arrays.fill(chars, c); return new String(chars); }
    private static JSONObject identity() throws Exception { return new JSONObject().put("device_id", DEVICE).put("token", TOKEN); }
    private static NetworkConfirmationDispatch.Request request(long issued, long deadline, String task) throws Exception {
        JSONObject job = new JSONObject().put("task_id", task).put("boot_id", BOOT).put("before", true)
                .put("target", false).put("started_elapsed", 100000L).put("deadline_elapsed", deadline).put("last_elapsed", 100900L);
        Constructor<NetworkRecoveryDispatch.Binding> binding = NetworkRecoveryDispatch.Binding.class.getDeclaredConstructor(JSONObject.class, String.class);
        binding.setAccessible(true);
        Constructor<NetworkConfirmationDispatch.Request> constructor = NetworkConfirmationDispatch.Request.class.getDeclaredConstructor(
                NetworkRecoveryDispatch.Binding.class, String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(binding.newInstance(job, HASH), NONCE, issued);
    }
    private static NetworkConfirmationDispatch.Request request() throws Exception {
        return request(101000, 160000, RemoteProtocol.localJobId(DEVICE, CLOUD));
    }
    private static JSONObject allow(NetworkConfirmationDispatch.Request request) throws Exception {
        JSONObject binding = RemoteNetworkConfirmationJson.encode(DEVICE, TOKEN, CLOUD, DIGEST, request).getJSONObject("network_confirmation");
        binding.remove("request_digest"); binding.remove("last_elapsed"); binding.put("decision", "allow");
        return new JSONObject().put("ok", true).put("device_id", DEVICE).put("task_id", CLOUD)
                .put("request_digest", DIGEST).put("network_confirmation", binding);
    }
    private static final class Time implements RemoteNetworkConfirmationSource.Clock {
        volatile long value = 101000;
        public long elapsed() { return value; }
        public long wall() { return 0; }
    }
    private static class Fake extends HttpsURLConnection {
        byte[] reply;
        int status = 200;
        String retry, contentType = "application/json; charset=utf-8", encoding, length;
        String block = "";
        boolean closeReleases = true;
        volatile boolean disconnected;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final AtomicInteger opens = new AtomicInteger();
        Fake() throws Exception { super(new URL(ENDPOINT)); reply = allow(request()).toString().getBytes("UTF-8"); }
        void at(String phase) {
            if (!phase.equals(block)) return;
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        public void connect() { throw new AssertionError("不得联网"); }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; if (closeReleases) release.countDown(); at("disconnect"); }
        public String getCipherSuite() { return "TLS_TEST"; }
        public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return null; }
        public int getResponseCode() { at("status"); return status; }
        public String getContentType() { return contentType; }
        public String getHeaderField(String name) {
            if ("Retry-After".equals(name)) return retry;
            if ("Content-Encoding".equals(name)) return encoding;
            if ("Content-Length".equals(name)) return length;
            return null;
        }
        public OutputStream getOutputStream() {
            at("output");
            return new OutputStream() {
                public void write(int b) { at("write"); sent.write(b); }
                public void close() { at("stream-close"); }
            };
        }
        public InputStream getInputStream() {
            return new ByteArrayInputStream(reply) {
                public synchronized int read(byte[] bytes, int offset, int count) { at("read"); return super.read(bytes, offset, count); }
            };
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Time time = new Time();
        final Fake connection = new Fake();
        final AtomicReference<RemoteNetworkConfirmationSource.Attempt> slot = new AtomicReference<>();
        final RemoteNetworkConfirmationSource source = source(identity());
        Fixture() throws Exception { }
        RemoteNetworkConfirmationSource source(JSONObject identity) throws Exception {
            return new RemoteNetworkConfirmationSource(identity, CLOUD, DIGEST, time, () -> {
                connection.opens.incrementAndGet(); connection.at("open"); return connection;
            }, slot);
        }
        NetworkConfirmationDispatch.Receipt fetch() throws Exception { return source.fetchVerified(request(), 1000); }
        public void close() throws Exception {
            connection.release.countDown(); source.close();
            RemoteNetworkConfirmationSource.Attempt active = slot.get();
            if (active != null) {
                assertTrue("遗留工作未结束", active.finished.await(2, TimeUnit.SECONDS));
                active.thread.join(1000);
                assertFalse(active.thread.isAlive());
            }
            assertNull(slot.get());
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); fail("错误输入得到确认"); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("NETWORK_CONFIRM_"));
            assertFalse(expected.toString().contains(TOKEN));
            assertNull(expected.getCause());
        }
    }

    @Test public void postsFrozenEnvelopeAndReturnsOnlyBoundReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            NetworkConfirmationDispatch.Receipt r = f.fetch();
            assertNotNull(r); assertEquals(NONCE, r.nonce); assertEquals(request().original.taskId, r.taskId);
            assertEquals("POST", f.connection.getRequestMethod());
            assertFalse(f.connection.getInstanceFollowRedirects()); assertFalse(f.connection.getUseCaches());
            assertSame(RemoteTls.factory(), f.connection.getSSLSocketFactory());
            assertEquals("close", f.connection.getRequestProperty("Connection"));
            JSONObject sent = new JSONObject(f.connection.sent.toString("UTF-8"));
            assertEquals(CLOUD, sent.getString("task_id")); assertEquals(DEVICE, sent.getString("device_id"));
            assertEquals(TOKEN, sent.getString("token")); assertEquals("running", sent.getString("state"));
            assertEquals("network-confirmation", sent.getString("action"));
            JSONObject n = sent.getJSONObject("network_confirmation");
            assertEquals(DIGEST, n.getString("request_digest")); assertEquals(100900, n.getLong("last_elapsed"));
            assertEquals(60000, n.getLong("window_ms")); assertEquals(101000, n.getLong("issued_elapsed"));
            assertEquals(NONCE, n.getString("nonce")); assertEquals("wifi_enabled", n.getString("key"));
            assertTrue(f.connection.disconnected); assertNull(f.slot.get());
        }
    }
    @Test public void rejectsEveryChangedBindingAndEnvelopeField() throws Exception {
        for (String key : new String[]{"ok", "device_id", "task_id", "request_digest"}) {
            JSONObject reply = allow(request()); reply.put(key, "wrong");
            rejectedReply(reply.toString());
        }
        for (String key : new String[]{"version", "decision", "task_id", "apk_sha256", "key", "before", "target", "boot_id",
                "started_elapsed", "deadline_elapsed", "window_ms", "issued_elapsed", "nonce"}) {
            JSONObject reply = allow(request()); JSONObject n = reply.getJSONObject("network_confirmation");
            Object old = n.get(key);
            n.put(key, old instanceof Boolean ? !((Boolean) old) : old instanceof Number ? ((Number) old).longValue() + 1 : "wrong");
            rejectedReply(reply.toString());
        }
    }
    @Test public void rejectsMissingFieldsExtraFieldsAndCoercedTypes() throws Exception {
        JSONObject template = allow(request());
        for (String key : new String[]{"ok", "device_id", "task_id", "request_digest", "network_confirmation"}) {
            JSONObject reply = new JSONObject(template.toString()); reply.remove(key); rejectedReply(reply.toString());
        }
        JSONObject n = template.getJSONObject("network_confirmation");
        for (String key : new String[]{"version", "decision", "task_id", "apk_sha256", "key", "before", "target", "boot_id",
                "started_elapsed", "deadline_elapsed", "window_ms", "issued_elapsed", "nonce"}) {
            JSONObject reply = new JSONObject(template.toString()); reply.getJSONObject("network_confirmation").remove(key);
            rejectedReply(reply.toString());
            reply = new JSONObject(template.toString()); reply.getJSONObject("network_confirmation").put(key, JSONObject.NULL);
            rejectedReply(reply.toString());
            if (n.get(key) instanceof Boolean || n.get(key) instanceof Number) {
                reply = new JSONObject(template.toString()); reply.getJSONObject("network_confirmation").put(key, n.get(key).toString());
                rejectedReply(reply.toString());
            }
        }
        rejectedReply(new JSONObject(template.toString()).put("token", TOKEN).toString());
        template.getJSONObject("network_confirmation").put("last_elapsed", 100900);
        rejectedReply(template.toString());
    }
    private static void rejectedReply(String reply) throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.reply = reply.getBytes("UTF-8"); rejected(f::fetch);
        }
    }
    @Test public void rejectsLooseDuplicateTrailingFractionalAndUnsafeJson() throws Exception {
        String valid = allow(request()).toString();
        for (String bad : new String[]{"{\"ok\":true}", valid + "{}", valid.replace("\"ok\":true", "\"ok\":true,\"ok\":true"),
                valid.replace("\"ok\":true", "\"ok\":true,\"\\u006fk\":true"), valid.replace("\"version\":1", "\"version\":1.0"),
                valid.replace("\"version\":1", "\"version\":1e0"), valid.replace("\"version\":1", "\"version\":01"),
                valid.replace("\"version\":1", "\"version\":9007199254740992"), valid.replace('"', '\''),
                valid.replace("\"decision\":\"allow\"", "\"decision\":\"deny\""), "[]", "null"}) rejectedReply(bad);
    }
    @Test public void rejectsNon200IncludingRedirectAndIgnoresErrorBody() throws Exception {
        for (int status : new int[]{201, 204, 301, 302, 307, 308, 401, 404, 409, 429, 503}) {
            try (Fixture f = new Fixture()) {
                f.connection.status = status; f.connection.block = "read";
                assertNull(f.fetch()); assertEquals(1, f.connection.opens.get());
            }
        }
    }
    @Test public void retryAfterPreventsAnotherRequestAndCountsDown() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.status = 429; f.connection.retry = "2";
            assertNull(f.fetch()); assertEquals(2000, f.source.retryAfterMs());
            assertNull(f.fetch()); assertEquals(1, f.connection.opens.get());
            f.time.value += 1999; assertEquals(1, f.source.retryAfterMs());
            assertNull(f.fetch()); f.time.value++; assertEquals(0, f.source.retryAfterMs());
            f.connection.status = 200; f.connection.retry = null; assertNotNull(f.fetch());
            assertEquals(2, f.connection.opens.get());
        }
    }
    @Test public void retryAfterAlsoAppliesTo200WithoutConfirmationAndHttpDate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.reply = "{\"ok\":true}".getBytes("UTF-8");
            f.connection.retry = "Thu, 01 Jan 1970 00:15:00 GMT";
            rejected(f::fetch); assertEquals(900000, f.source.retryAfterMs()); assertNull(f.fetch());
        }
    }
    @Test public void rejectsWrongEndpointBeforeWriting() throws Exception {
        for (String endpoint : new String[]{"https://example.invalid/api/elfremote/task-progress", ENDPOINT + "?x=1", ENDPOINT + "/"}) {
            try (Fixture f = new Fixture()) {
                Fake other = new Fake() { public URL getURL() { try { return new URL(endpoint); } catch (Exception e) { throw new AssertionError(e); } } };
                RemoteNetworkConfirmationSource source = new RemoteNetworkConfirmationSource(identity(), CLOUD, DIGEST, f.time, () -> other, f.slot);
                rejected(() -> source.fetchVerified(request(), 1000)); assertEquals(0, other.sent.size()); assertTrue(other.disconnected);
            }
        }
    }
    @Test public void validatesIdentityAndLocalTaskBeforeOpeningConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            for (Object bad : new Object[]{true, 12, JSONObject.NULL, "", "bad\nvalue"}) {
                rejected(() -> f.source(identity().put("device_id", bad)));
                rejected(() -> f.source(identity().put("token", bad)));
            }
            rejected(() -> f.source.fetchVerified(request(101000, 160000, repeat('c', 64)), 1000));
            rejected(() -> f.source.fetchVerified(request(101000, 100000, request().original.taskId), 1000));
            assertEquals(0, f.connection.opens.get());
        }
    }
    @Test public void identityIsSnapshotted() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject identity = identity(); RemoteNetworkConfirmationSource source = f.source(identity);
            identity.put("device_id", "changed").put("token", repeat('e', 64));
            assertNotNull(source.fetchVerified(request(), 1000));
            assertEquals(DEVICE, new JSONObject(f.connection.sent.toString("UTF-8")).getString("device_id"));
        }
    }
    @Test public void rejectsExpiredFutureIssuedAndZeroBudgetWithoutIo() throws Exception {
        try (Fixture f = new Fixture()) {
            rejected(() -> f.source.fetchVerified(request(), 0));
            rejected(() -> f.source.fetchVerified(request(101001, 160000, request().original.taskId), 1000));
            f.time.value = 160000; rejected(f::fetch); assertEquals(0, f.connection.opens.get());
        }
    }
    @Test public void validatesContentTypeEncodingLengthAndUtf8() throws Exception {
        try (Fixture f = new Fixture()) { f.connection.contentType = "text/html"; rejected(f::fetch); }
        try (Fixture f = new Fixture()) { f.connection.encoding = "gzip"; rejected(f::fetch); }
        for (String length : new String[]{"999999999999999999999", "16385", "-1", "1", "no"}) {
            try (Fixture f = new Fixture()) { f.connection.length = length; rejected(f::fetch); }
        }
        try (Fixture f = new Fixture()) { f.connection.reply = new byte[]{(byte) 0xc3, 0x28}; rejected(f::fetch); }
        try (Fixture f = new Fixture()) { f.connection.reply = new byte[16385]; rejected(f::fetch); }
        try (Fixture f = new Fixture()) { f.connection.length = Integer.toString(f.connection.reply.length); assertNotNull(f.fetch()); }
    }
    @Test public void callerDeadlineCoversOpenConnectWriteReadAndClose() throws Exception {
        for (String phase : new String[]{"open", "output", "write", "status", "read", "stream-close", "disconnect"}) {
            try (Fixture f = new Fixture()) {
                f.connection.block = phase; f.connection.closeReleases = false;
                long began = System.nanoTime();
                rejected(() -> f.source.fetchVerified(request(), 140));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                assertTrue(phase + "超出调度容差：" + elapsed, elapsed < 400);
                assertEquals(0, f.connection.entered.getCount()); assertNotNull(f.slot.get());
                RemoteNetworkConfirmationSource another = f.source(identity());
                rejected(() -> another.fetchVerified(request(), 140)); assertEquals(1, f.connection.opens.get());
            }
        }
    }
    @Test public void timeoutClosesTransportAndCollectsCooperativeWorker() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.block = "read";
            rejected(() -> f.source.fetchVerified(request(), 300));
            assertTrue(f.connection.disconnected); assertNull(f.slot.get());
        }
    }
    @Test public void lateOpenCannotSendBodyAndSlotReleasesOnlyAfterActualExit() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.block = "open"; f.connection.closeReleases = false;
            rejected(() -> f.source.fetchVerified(request(), 120));
            RemoteNetworkConfirmationSource.Attempt attempt = f.slot.get(); assertNotNull(attempt);
            f.connection.release.countDown(); assertTrue(attempt.finished.await(1, TimeUnit.SECONDS));
            assertEquals(0, f.connection.sent.size()); assertTrue(f.connection.disconnected); assertNull(f.slot.get());
            assertNotNull(f.fetch());
        }
    }
    @Test public void remainingWindowCapsLargeCallerBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.time.value = 159850; f.connection.block = "read";
            long began = System.nanoTime(); rejected(() -> f.source.fetchVerified(request(), Long.MAX_VALUE));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 400);
            assertTrue(f.connection.getConnectTimeout() <= 150); assertNull(f.slot.get());
        }
    }
    @Test public void hardCapRemainsThreeSecondsWhenClockDoesNotAdvance() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.block = "read";
            long began = System.nanoTime(); rejected(() -> f.source.fetchVerified(request(), Long.MAX_VALUE));
            long spent = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(spent >= 2700); assertTrue(spent < 3300); assertTrue(f.connection.getConnectTimeout() <= 3000);
        }
    }
    @Test public void postResponseDeadlineAndClockRollbackCannotAuthorize() throws Exception {
        for (long after : new long[]{160000, 100999}) {
            try (Fixture f = new Fixture()) {
                Fake connection = new Fake() { public int getResponseCode() { f.time.value = after; return 200; } };
                RemoteNetworkConfirmationSource source = new RemoteNetworkConfirmationSource(identity(), CLOUD, DIGEST, f.time, () -> connection, f.slot);
                rejected(() -> source.fetchVerified(request(), 1000));
            }
        }
    }
    @Test public void budgetCannotRestartAfterInitialRemainingWindowSample() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger reads = new AtomicInteger();
            RemoteNetworkConfirmationSource.Clock clock = new RemoteNetworkConfirmationSource.Clock() {
                public long elapsed() { return reads.getAndIncrement() == 0 ? 159990 : 160000; }
                public long wall() { return 0; }
            };
            RemoteNetworkConfirmationSource source = new RemoteNetworkConfirmationSource(identity(), CLOUD, DIGEST, clock,
                    () -> { f.connection.opens.incrementAndGet(); return f.connection; }, f.slot);
            rejected(() -> source.fetchVerified(request(), 3000)); assertEquals(0, f.connection.opens.get());
        }
    }
    @Test public void interruptionIsPreservedAndRequestIsClosed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.block = "read";
            AtomicReference<Throwable> error = new AtomicReference<>(); AtomicReference<Boolean> flag = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try { f.fetch(); error.set(new AssertionError("中断后不应确认")); }
                catch (InterruptedException expected) { flag.set(Thread.currentThread().isInterrupted()); }
                catch (Throwable failure) { error.set(failure); }
            });
            caller.start();
            try { assertTrue(f.connection.entered.await(1, TimeUnit.SECONDS)); caller.interrupt(); caller.join(1500); }
            finally { f.connection.release.countDown(); caller.interrupt(); caller.join(1500); }
            assertFalse(caller.isAlive()); assertNull(error.get()); assertEquals(Boolean.TRUE, flag.get());
            assertTrue(f.connection.disconnected); assertNull(f.slot.get());
        }
    }
    @Test public void closeCancelsInFlightAndDisallowsNewRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connection.block = "read"; AtomicReference<Throwable> error = new AtomicReference<>();
            Thread caller = new Thread(() -> { try { rejected(f::fetch); } catch (Throwable e) { error.set(e); } });
            caller.start();
            try { assertTrue(f.connection.entered.await(1, TimeUnit.SECONDS)); f.source.close(); caller.join(1500); }
            finally { f.connection.release.countDown(); caller.interrupt(); caller.join(1500); }
            assertFalse(caller.isAlive()); assertNull(error.get()); rejected(f::fetch); assertTrue(f.connection.disconnected);
        }
    }
}
