package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppPttSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final class Time implements MediaCapture.Clock {
        volatile long offset;
        final long origin = System.nanoTime();
        public long elapsed() { return 1000 + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin) + offset; }
        public long wall() { return 100000 + elapsed(); }
    }
    static JSONObject offerJson(Time time, String mode) throws Exception {
        return new JSONObject().put("session_id", "ptt-test").put("mode", mode)
                .put("url", "wss://control.example/api/elfremote/media/device?session_id=ptt-test")
                .put("expires_at", time.wall() + 45000).put("token", "synthetic_test_token_1234");
    }
    static RtcOffer offer(Time time, String mode) throws Exception {
        return RtcOffer.parse(offerJson(time, mode), new URI("https://control.example"), time.wall());
    }
    static JSONObject hello() throws Exception { return new JSONObject().put("type", "hello").put("mode", "ptt"); }
    static JSONObject tracks() throws Exception {
        return new JSONObject().put("type", "tracks").put("sessionId", "remote-test")
                .put("tracks", new JSONArray().put(new JSONObject().put("location", "remote")
                        .put("sessionId", "remote-test").put("trackName", "audio")));
    }
    static final class Wire implements MicrophoneSession.Transport {
        volatile MicrophoneSession.Events events;
        final CountDownLatch entered = new CountDownLatch(1), releaseConnect = new CountDownLatch(1);
        final CountDownLatch abortEntered = new CountDownLatch(1), releaseAbort = new CountDownLatch(1);
        final AtomicInteger connects = new AtomicInteger(), closes = new AtomicInteger(), aborts = new AtomicInteger();
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        volatile boolean blocked, blockAbort, noReplies, withholdAnswer, disconnectOnAbort, connectFails, closeFails, closed;
        public void connect(RtcOffer offer, MicrophoneSession.Events events) throws Exception {
            connects.incrementAndGet(); this.events = events; entered.countDown();
            if (blocked && !releaseConnect.await(3, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
            if (connectFails) throw new IOException("sensitive detail must not escape");
        }
        public void send(JSONObject value) throws Exception {
            if (closed) throw new IOException("MEDIA_SOCKET_CLOSED");
            sent.add(new JSONObject(value.toString()));
            if ("rpc".equals(value.optString("type")) && !noReplies
                    && !(withholdAnswer && "answer".equals(value.optString("action")))) {
                JSONObject result = new JSONObject();
                if ("subscribe".equals(value.optString("action"))) result.put("marker", "exact-subscribe-result");
                reply(value.getInt("id"), result);
            }
        }
        void reply(int id, JSONObject result) throws Exception {
            emit(new JSONObject().put("type", "rpc").put("id", id).put("result", result));
        }
        void emit(JSONObject value) { events.message(value.toString()); }
        public void abort() {
            aborts.incrementAndGet(); abortEntered.countDown();
            if (blockAbort) try {
                if (!releaseAbort.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("测试中止等待超时");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            closed = true; releaseConnect.countDown();
            if (disconnectOnAbort && events != null) events.disconnected();
        }
        public void close() throws Exception {
            closes.incrementAndGet(); closed = true;
            if (closeFails) throw new IOException("private transport detail");
        }
        List<String> actions() {
            List<String> result = new ArrayList<>();
            for (JSONObject message : sent) if ("rpc".equals(message.optString("type"))) result.add(message.optString("action"));
            return result;
        }
    }
    static final class Ops implements PttSessionController.Operations {
        AndroidPttOperations.Sender sender;
        AppPttSession.Signals signals;
        final AtomicInteger opens = new AtomicInteger(), cancels = new AtomicInteger(), closes = new AtomicInteger(), unmutes = new AtomicInteger();
        final AtomicInteger proofs = new AtomicInteger();
        final CountDownLatch openEntered = new CountDownLatch(1), releaseOpen = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1), closeExited = new CountDownLatch(1);
        volatile boolean muted, ack, blockOpen, blockClose, cleanupFails, cancelFails, nativeFails;
        volatile int proof;
        volatile JSONObject subscribed;
        public void open() throws Exception {
            opens.incrementAndGet(); openEntered.countDown();
            if (nativeFails) throw new UnsatisfiedLinkError("private native detail");
            if (blockOpen && !releaseOpen.await(3, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
        }
        public void send(JSONObject body) throws Exception { sender.send(body); }
        public JSONObject subscribe(JSONObject body) throws Exception {
            subscribed = body; signals.changed(true);
            return new JSONObject().put("sessionDescription", new JSONObject().put("type", "answer").put("sdp", "test-sdp"));
        }
        public void answerAcknowledged() { ack = true; }
        public void prepareMuted() { assertTrue(ack); muted = true; }
        public int outputProof() throws Exception {
            proofs.incrementAndGet();
            if (proof < 0) throw new IOException("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED");
            return proof;
        }
        public boolean playbackFrames() { return muted; }
        public void unmute() { unmutes.incrementAndGet(); }
        public void cancel() {
            cancels.incrementAndGet(); releaseOpen.countDown(); sender.abort();
            if (cancelFails) throw new IllegalStateException("private cancel detail");
        }
        public void close() throws Exception {
            closes.incrementAndGet(); closeEntered.countDown();
            try {
                if (blockClose && !releaseClose.await(3, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
                sender.close();
                if (cleanupFails) throw new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED");
            } finally { closeExited.countDown(); }
        }
    }
    final class Fixture implements AutoCloseable {
        final Time time = new Time();
        final File files = temporary.newFolder();
        final Wire wire = new Wire();
        final Ops ops = new Ops();
        final AtomicInteger factories = new AtomicInteger();
        final CountDownLatch factoryEntered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        volatile boolean factoryFails, blockFactory;
        final AppPttSession session;
        Fixture() throws Exception {
            session = new AppPttSession(files, offer(time, "ptt"), time, wire, (sender, signals) -> {
                factories.incrementAndGet(); factoryEntered.countDown();
                if (blockFactory && !releaseFactory.await(3, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
                if (factoryFails) throw new IOException("private factory detail");
                ops.sender = sender; ops.signals = signals; return ops;
            });
        }
        void start() throws Exception { session.start(); assertTrue(wire.entered.await(2, TimeUnit.SECONDS)); }
        void negotiate() throws Exception { start(); wire.emit(tracks()); wire.emit(tracks()); wire.emit(hello()); }
        public void close() throws Exception {
            releaseFactory.countDown(); ops.releaseOpen.countDown(); ops.releaseClose.countDown(); wire.releaseConnect.countDown(); wire.releaseAbort.countDown();
            session.close(); assertTrue(session.awaitClosed(2500));
            if (factories.get() > 0 && !factoryFails) ops.closeExited.await(2, TimeUnit.SECONDS);
            // 仅测试回收有意保留的真实文件锁，不向生产添加强制解锁接口。
            Field field = AppPttSession.class.getDeclaredField("lease"); field.setAccessible(true);
            MediaFiles.Lease retained = (MediaFiles.Lease) field.get(session);
            if (retained != null) retained.close();
        }
    }
    interface Check { boolean ok() throws Exception; }
    static void until(Check check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) { if (check.ok()) return; Thread.sleep(5); }
        assertTrue("等待条件未满足", check.ok());
    }
    static String state(AppPttSession session) throws Exception { return session.snapshot().getString("state"); }
    static void locked(File files) throws Exception {
        try (MediaFiles.Lease ignored = MediaFiles.lease(new File(files, "media"))) { fail("媒体锁提前释放"); }
        catch (IOException busy) { assertEquals("MEDIA_BUSY", busy.getMessage()); }
    }
    static void unlocked(File files) throws Exception {
        try (MediaFiles.Lease ignored = MediaFiles.lease(new File(files, "media"))) {}
    }

    @Test public void constructionAndCloseBeforeStartHaveNoMediaSideEffects() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("idle", state(f.session)); assertFalse(new File(f.files, "media").exists());
            f.session.stop(); assertTrue(f.session.awaitClosed(2000));
            assertEquals(0, f.factories.get()); assertEquals(0, f.wire.connects.get());
            assertEquals("closed", state(f.session));
            try { f.session.start(); fail(); } catch (IOException expected) { assertEquals("MEDIA_SESSION_NOT_REUSABLE", expected.getMessage()); }
        }
    }
    @Test public void startSnapshotAndStopDoNotWaitForConnect() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.blocked = true;
            f.start(); assertEquals(1, f.wire.releaseConnect.getCount()); locked(f.files);
            assertEquals("connecting", state(f.session)); f.session.tick(); f.session.stop();
            assertTrue(f.session.awaitClosed(2000)); assertEquals("closed", state(f.session));
            assertEquals(1, f.wire.closes.get()); assertEquals(1, f.ops.closes.get()); unlocked(f.files);
        }
    }
    @Test public void duplicateStartIsRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start();
            try { f.session.start(); fail(); } catch (IOException expected) { assertEquals("MEDIA_SESSION_NOT_REUSABLE", expected.getMessage()); }
            assertEquals(1, f.wire.connects.get());
        }
    }
    @Test public void expiredInviteDoesNotOpenOrConnectAndStillCleansUp() throws Exception {
        try (Fixture f = new Fixture()) {
            f.time.offset = 46000; f.session.start(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("closed", state(f.session)); assertEquals(0, f.factories.get()); assertEquals(0, f.wire.connects.get());
            assertFalse(f.session.snapshot().getString("reason").isEmpty());
        }
    }
    @Test public void sharesExistingMediaLockAndDoesNotDisturbOwner() throws Exception {
        try (Fixture f = new Fixture(); MediaFiles.Lease held = MediaFiles.lease(new File(f.files, "media"))) {
            f.session.start(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_BUSY", f.session.snapshot().getString("reason"));
            assertEquals("closed", state(f.session)); assertEquals(0, f.factories.get()); locked(f.files);
        }
    }
    @Test public void factoryFailureReleasesLockAndRedactsException() throws Exception {
        try (Fixture f = new Fixture()) {
            f.factoryFails = true; f.session.start(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_PTT_START_FAILED", f.session.snapshot().getString("reason"));
            assertFalse(f.session.snapshot().toString().contains("private")); unlocked(f.files);
        }
    }
    @Test public void stopDuringFactoryClosesReturnedOperationsWithoutConnect() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockFactory = true; f.session.start(); assertTrue(f.factoryEntered.await(1, TimeUnit.SECONDS));
            f.session.stop(); assertEquals("closing", state(f.session)); f.releaseFactory.countDown();
            assertTrue(f.session.awaitClosed(2000)); assertEquals(1, f.ops.closes.get()); assertEquals(0, f.wire.connects.get()); unlocked(f.files);
        }
    }
    @Test public void connectFailureClosesPeerTransportAndLock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.connectFails = true; f.start(); assertTrue(f.session.awaitClosed(2000));
            assertEquals(1, f.ops.closes.get()); assertEquals(1, f.wire.closes.get()); unlocked(f.files);
        }
    }
    @Test public void actualHelloAndRpcSequenceHandlesEarlyDuplicateTracks() throws Exception {
        try (Fixture f = new Fixture()) {
            f.negotiate(); until(() -> f.ops.muted);
            assertEquals(java.util.Arrays.asList("new", "subscribe", "answer"), f.wire.actions());
            assertEquals("exact-subscribe-result", f.ops.subscribed.getString("marker"));
            JSONObject answer = f.wire.sent.get(2).getJSONObject("body").getJSONObject("sessionDescription");
            assertEquals("answer", answer.getString("type")); assertEquals("test-sdp", answer.getString("sdp"));
            assertEquals(1, f.ops.opens.get()); assertEquals(0, f.ops.unmutes.get());
            assertFalse(f.session.snapshot().getBoolean("ready"));
            String snapshot = f.session.snapshot().toString();
            assertFalse(snapshot.contains("synthetic_test_token")); assertFalse(snapshot.contains("test-sdp")); assertFalse(snapshot.contains("control.example"));
        }
    }
    @Test public void answerReceiptIsRequiredEvenAfterIce() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.withholdAnswer = true; f.negotiate(); until(() -> f.wire.actions().size() == 3);
            assertFalse(f.ops.ack); assertFalse(f.ops.muted);
            f.wire.reply(3, new JSONObject()); until(() -> f.ops.muted);
        }
    }
    @Test public void realGuardGapStopsMutedFlowWithoutReadyOrUnmute() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.proof = -1; f.wire.disconnectOnAbort = true; f.negotiate(); assertTrue(f.session.awaitClosed(2000));
            assertTrue(f.ops.muted); assertEquals(0, f.ops.unmutes.get());
            assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", f.session.snapshot().getString("reason"));
            assertFalse(f.session.snapshot().getBoolean("ready"));
            for (JSONObject message : f.wire.sent) assertNotEquals("ready", message.optString("type"));
        }
    }
    @Test public void disconnectCancelsBlockedPeerAndIgnoresLateReceipts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.blockOpen = true; f.start(); f.wire.emit(hello()); assertTrue(f.ops.openEntered.await(1, TimeUnit.SECONDS));
            f.session.tick(); f.wire.events.disconnected(); assertTrue(f.session.awaitClosed(2000));
            String before = f.session.snapshot().toString(); f.wire.reply(1, new JSONObject()); f.wire.emit(hello()); f.wire.events.disconnected();
            f.session.stop(); f.session.close(); f.session.tick(); assertEquals(before, f.session.snapshot().toString());
            assertEquals(1, f.ops.closes.get()); assertEquals(1, f.ops.cancels.get()); assertEquals(1, f.wire.closes.get());
        }
    }
    @Test public void rpcTimeoutPreservesPrimaryReasonDuringAbortCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.noReplies = true; f.wire.disconnectOnAbort = true; f.start(); f.wire.emit(hello()); until(() -> f.wire.sent.size() == 1);
            f.time.offset = 21000; f.session.tick(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_RPC_TIMEOUT", f.session.snapshot().getString("reason")); unlocked(f.files);
        }
    }
    @Test public void waitingHelloIsBounded() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); f.time.offset = 46000; f.session.tick(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_SESSION_TIMEOUT", f.session.snapshot().getString("reason")); assertEquals(0, f.ops.opens.get());
        }
    }
    @Test public void malformedHelloAndNativeFailureCloseWithoutPublishing() throws Exception {
        for (boolean nativeFailure : new boolean[] { false, true }) try (Fixture f = new Fixture()) {
            f.ops.nativeFails = nativeFailure; f.start(); f.wire.emit(hello().put("mode", nativeFailure ? "ptt" : "microphone"));
            assertTrue(f.session.awaitClosed(2000)); assertTrue(f.wire.sent.isEmpty());
            assertEquals("closed", state(f.session)); assertFalse(f.session.snapshot().toString().contains("private"));
        }
    }
    @Test public void cleanupFailureKeepsBothReasonsAndMediaLock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.cleanupFails = true; f.start(); f.session.stop("MEDIA_PTT_FOCUS_LOST"); assertTrue(f.session.awaitClosed(2000));
            JSONObject value = f.session.snapshot(); assertEquals("release_unconfirmed", value.getString("state"));
            assertEquals("MEDIA_PTT_FOCUS_LOST", value.getString("reason")); assertEquals("MEDIA_ROUTE_RESTORE_UNCONFIRMED", value.getString("cleanup_reason"));
            assertFalse(value.getBoolean("cleanup_complete")); locked(f.files);
        }
    }
    @Test public void cleanupTimeoutIsBoundedAndLateReleaseCannotClaimSuccess() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.blockClose = true; f.start(); f.session.stop(); assertTrue(f.ops.closeEntered.await(1, TimeUnit.SECONDS));
            assertEquals("closing", state(f.session)); locked(f.files);
            f.time.offset = AppPttSession.CLEANUP_TIMEOUT_MS + 1; f.session.tick(); assertTrue(f.session.awaitClosed(100));
            assertEquals("release_unconfirmed", state(f.session)); assertEquals("MEDIA_PTT_CLEANUP_TIMEOUT", f.session.snapshot().getString("cleanup_reason"));
            f.ops.releaseClose.countDown(); assertTrue(f.ops.closeExited.await(1, TimeUnit.SECONDS));
            f.session.tick(); locked(f.files); assertEquals("release_unconfirmed", state(f.session));
        }
    }
    @Test public void transportAndCancelFailureCannotSkipCleanupOrReleaseLock() throws Exception {
        for (boolean cancelFailure : new boolean[] { false, true }) try (Fixture f = new Fixture()) {
            f.ops.cancelFails = cancelFailure; f.wire.closeFails = !cancelFailure;
            f.start(); f.session.stop(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("release_unconfirmed", state(f.session)); assertEquals(1, f.ops.closes.get()); locked(f.files);
        }
    }
    // 复用主线刚接入的真实PTT邀请解析和外层租约；仅Android后端由离线替身替代。
    private static AppMediaController outer(Fixture f, Object owner) throws Exception {
        AppMediaController outer = new AppMediaController(new AppMediaController.Backend() {
            public URI origin() throws Exception { return new URI("https://control.example"); }
            public JSONObject prepare(String hash, Cancellation cancel) { return new JSONObject(); }
            public AppMediaController.Session create(String hash, RtcOffer offer, Cancellation cancel) { return f.session; }
        }, f.time);
        JSONObject command = new JSONObject().put("operation", "start").put("apk_sha256", String.join("", java.util.Collections.nCopies(64, "a")))
                .put("offer", offerJson(f.time, "ptt"));
        outer.execute("start-test", AppMediaContract.command(command.toString()), owner, new Cancellation());
        assertTrue(f.wire.entered.await(1, TimeUnit.SECONDS)); return outer;
    }
    @Test public void outerOwnerDeathAndServiceExitStopActualSession() throws Exception {
        for (boolean serviceExit : new boolean[] { false, true }) try (Fixture f = new Fixture()) {
            Object owner = new Object(); AppMediaController outer = outer(f, owner);
            outer.ownerDied(new Object()); assertTrue(outer.hasActive());
            if (serviceExit) outer.serviceDestroyed(); else outer.ownerDied(owner);
            assertTrue(f.session.awaitClosed(2000)); assertFalse(outer.hasActive());
        }
    }
    @Test public void outerLeaseOnlyRenewsForMatchingOwnerAndRetainsFailedCleanup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.cleanupFails = true; Object owner = new Object(); AppMediaController outer = outer(f, owner);
            f.time.offset = 14000; outer.query("ptt-test", owner);
            f.time.offset = 16000; outer.tick(); assertNotEquals("closing", state(f.session));
            outer.query("", owner); outer.query("ptt-test", new Object());
            f.time.offset = 30000; outer.tick(); assertTrue(f.session.awaitClosed(2000));
            assertTrue(outer.hasActive()); assertEquals("release_unconfirmed", outer.query("ptt-test", owner).getString("state"));
        }
    }
    @Test public void blockingAbortCannotHoldOuterStopQueryOrOwnerDeathMonitor() throws Exception {
        for (boolean ownerDeath : new boolean[] { false, true }) try (Fixture f = new Fixture()) {
            f.wire.blockAbort = true; Object owner = new Object(); AppMediaController outer = outer(f, owner);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<?> stopped = caller.submit(() -> {
                    try { if (ownerDeath) outer.ownerDied(owner); else outer.stop("ptt-test"); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                });
                assertTrue(f.wire.abortEntered.await(1, TimeUnit.SECONDS));
                stopped.get(500, TimeUnit.MILLISECONDS);
                Future<JSONObject> query = caller.submit(() -> outer.query("ptt-test", owner));
                assertEquals("closing", query.get(500, TimeUnit.MILLISECONDS).getString("state"));
                assertEquals(1, f.wire.releaseAbort.getCount()); assertEquals(0, f.wire.closes.get()); locked(f.files);
                f.wire.releaseAbort.countDown(); assertTrue(f.session.awaitClosed(2000));
                assertEquals("closed", state(f.session)); assertEquals(1, f.wire.closes.get()); unlocked(f.files);
            } finally { f.wire.releaseAbort.countDown(); caller.shutdownNow(); }
        }
    }
    @Test public void blockedAbortCloseBarrierTimesOutWithoutReleasingMediaLock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.blockAbort = true; f.start(); f.session.stop();
            assertTrue(f.wire.abortEntered.await(1, TimeUnit.SECONDS));
            assertTrue(f.session.awaitClosed(3000)); assertEquals("release_unconfirmed", state(f.session));
            assertEquals("MEDIA_TRANSPORT_RELEASE_UNCONFIRMED", f.session.snapshot().getString("cleanup_reason"));
            assertEquals(0, f.wire.closes.get()); locked(f.files);
            f.wire.releaseAbort.countDown(); until(() -> f.wire.closes.get() == 1);
            assertEquals("release_unconfirmed", state(f.session)); locked(f.files);
        }
    }
}
