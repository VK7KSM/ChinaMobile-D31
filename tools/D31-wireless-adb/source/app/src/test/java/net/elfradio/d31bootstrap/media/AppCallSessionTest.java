package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppCallSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final class Time implements MediaCapture.Clock {
        final long origin = System.nanoTime();
        volatile long offset;
        public long elapsed() { return 1000 + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin) + offset; }
        public long wall() { return 1000000 + elapsed(); }
    }
    // 权威call解析尚未开放。仅构造已验证邀请夹具，不改共享解析门，不声称已验证其接线。
    static RtcOffer offer(Time time, String mode) throws Exception {
        Constructor<RtcOffer> ctor = RtcOffer.class.getDeclaredConstructor(String.class, URI.class, long.class, String.class, String.class, String.class, JSONObject.class);
        ctor.setAccessible(true);
        return ctor.newInstance("call-test", new URI("wss://control.example/api/elfremote/media/device?session_id=call-test"),
                time.wall() + 45000L, "synthetic_test_token_1234", mode, "front", null);
    }
    static JSONObject sdp(String type) throws Exception {
        return new JSONObject().put("sessionDescription", new JSONObject().put("type", type).put("sdp", "offline-test-sdp"));
    }
    static JSONObject hello() throws Exception { return new JSONObject().put("type", "hello").put("mode", "call"); }
    static JSONObject tracks() throws Exception {
        return new JSONObject().put("type", "tracks").put("sessionId", "remote-test").put("tracks", new JSONArray().put(
                new JSONObject().put("sessionId", "remote-test").put("location", "remote").put("trackName", "audio")));
    }
    static final class Wire implements MicrophoneSession.Transport {
        volatile MicrophoneSession.Events events;
        volatile RtcOffer receivedOffer;
        final CountDownLatch entered = new CountDownLatch(1), releaseConnect = new CountDownLatch(1);
        final CountDownLatch abortEntered = new CountDownLatch(1), releaseAbort = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        final AtomicInteger connects = new AtomicInteger(), aborts = new AtomicInteger(), closes = new AtomicInteger();
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        final List<String> cleanupOrder = new CopyOnWriteArrayList<>();
        volatile boolean blockConnect, blockAbort, blockClose, ignoreAbortConnect, connectFails, closeFails, abortFails;
        volatile boolean disconnectOnAbort, withholdAnswer, noReplies;
        public void connect(RtcOffer offer, MicrophoneSession.Events events) throws Exception {
            this.events = events; receivedOffer = offer; connects.incrementAndGet(); entered.countDown();
            if (blockConnect && !releaseConnect.await(5, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
            if (connectFails) throw new IOException("private connect detail");
        }
        public void send(JSONObject body) throws Exception {
            sent.add(new JSONObject(body.toString()));
            if (!"rpc".equals(body.optString("type")) || noReplies) return;
            String action = body.getString("action");
            if (withholdAnswer && "answer".equals(action)) return;
            JSONObject result = new JSONObject();
            if ("new".equals(action)) result.put("sessionId", "local-test");
            if ("publish".equals(action)) result = sdp("answer");
            if ("subscribe".equals(action)) result = sdp("offer").put("tracks", new JSONArray().put(
                    new JSONObject().put("trackName", "audio").put("mid", "1").put("errorCode", JSONObject.NULL)));
            reply(body.getInt("id"), result);
        }
        void reply(int id, JSONObject result) throws Exception { emit(new JSONObject().put("type", "rpc").put("id", id).put("result", result)); }
        void emit(JSONObject body) { events.message(body.toString()); }
        public void abort() {
            aborts.incrementAndGet(); cleanupOrder.add("abort"); abortEntered.countDown();
            if (blockAbort) await(releaseAbort);
            if (!ignoreAbortConnect) releaseConnect.countDown();
            if (disconnectOnAbort && events != null) events.disconnected();
            if (abortFails) throw new IllegalStateException("private abort detail");
        }
        public void close() throws Exception {
            closes.incrementAndGet(); cleanupOrder.add("close"); closeEntered.countDown();
            if (blockClose && !releaseClose.await(5, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
            if (closeFails) throw new IOException("private close detail");
        }
        List<String> actions() {
            List<String> result = new ArrayList<>();
            for (JSONObject value : sent) if ("rpc".equals(value.optString("type"))) result.add(value.optString("action"));
            return result;
        }
        static void await(CountDownLatch latch) {
            try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    static final class Ops implements CallSessionController.Operations {
        AppCallSession.Sender sender;
        AppCallSession.Signals signals;
        final AtomicInteger opens = new AtomicInteger(), cancels = new AtomicInteger(), closes = new AtomicInteger(), unmutes = new AtomicInteger();
        final CountDownLatch openEntered = new CountDownLatch(1), releaseOpen = new CountDownLatch(1);
        final CountDownLatch cancelEntered = new CountDownLatch(1), releaseCancel = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1), closeExited = new CountDownLatch(1);
        volatile boolean blockOpen, blockCancel, blockClose, cancelFails, cleanupFails, nativeFails;
        volatile boolean ack, muted, capture = true, playback = true, senderClose;
        volatile int input, output;
        volatile CallProtocol.Route route;
        volatile String factoryThread, openThread;
        public void open(CallProtocol.Route route) throws Exception {
            this.route = route; openThread = Thread.currentThread().getName(); opens.incrementAndGet(); openEntered.countDown();
            if (nativeFails) throw new UnsatisfiedLinkError("private native detail");
            if (blockOpen && !releaseOpen.await(5, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
            signals.changed(true);
        }
        public void send(JSONObject body) throws Exception { sender.send(body); }
        public JSONObject createPublish(JSONObject body) throws Exception {
            assertEquals("local-test", body.getString("sessionId"));
            return sdp("offer").put("tracks", new JSONArray().put(new JSONObject().put("mid", "0").put("trackName", "audio")));
        }
        public void applyPublish(JSONObject body) throws Exception { assertEquals("answer", body.getJSONObject("sessionDescription").getString("type")); }
        public CallProtocol.SubscriptionResult subscribe(JSONObject body) throws Exception { return CallProtocol.SubscriptionResult.answer(sdp("answer")); }
        public void negotiationComplete() { ack = true; }
        public void prepareMuted() { assertTrue(ack); muted = true; }
        public int inputProof() throws Exception {
            if (input < 0) throw new IOException("MEDIA_CALL_INPUT_UNVERIFIED"); return input;
        }
        public int outputProof() throws Exception {
            if (output < 0) throw new IOException("MEDIA_CALL_OUTPUT_UNVERIFIED"); return output;
        }
        public boolean captureFrames() { return muted && capture; }
        public boolean playbackFrames() { return muted && playback; }
        public void unmute() { unmutes.incrementAndGet(); }
        public void cancel() {
            cancels.incrementAndGet(); cancelEntered.countDown(); releaseOpen.countDown(); sender.abort();
            if (blockCancel) Wire.await(releaseCancel);
            if (cancelFails) throw new IllegalStateException("private cancel detail");
        }
        public void close() throws Exception {
            closes.incrementAndGet(); closeEntered.countDown();
            try {
                if (blockClose && !releaseClose.await(5, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
                if (senderClose) sender.close();
                if (cleanupFails) throw new IOException("MEDIA_CALL_ROUTE_UNRELEASED");
            } finally { closeExited.countDown(); }
        }
    }
    final class Fixture implements AutoCloseable {
        final File files = temporary.newFolder();
        final Time time = new Time();
        final Wire wire = new Wire();
        final Ops ops = new Ops();
        final AtomicInteger factories = new AtomicInteger();
        final CountDownLatch factoryEntered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        final AppCallSession session;
        volatile boolean blockFactory, factoryFails, factoryNull;
        Fixture() throws Exception {
            session = new AppCallSession(files, offer(time, "call"), time, wire, (sender, signals) -> {
                factories.incrementAndGet(); ops.factoryThread = Thread.currentThread().getName(); factoryEntered.countDown();
                if (blockFactory && !releaseFactory.await(5, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_TIMEOUT");
                if (factoryFails) throw new IOException("private factory detail");
                if (factoryNull) return null;
                ops.sender = sender; ops.signals = signals; return ops;
            });
        }
        void start() throws Exception { session.start(); assertTrue(wire.entered.await(2, TimeUnit.SECONDS)); }
        void negotiate() throws Exception { start(); wire.emit(tracks()); wire.emit(tracks()); wire.emit(hello()); }
        void stream() throws Exception { ops.input = 1; ops.output = 1; negotiate(); until(() -> session.snapshot().optBoolean("ready")); }
        public void close() throws Exception {
            releaseFactory.countDown(); wire.releaseConnect.countDown(); wire.releaseAbort.countDown(); wire.releaseClose.countDown();
            ops.releaseOpen.countDown(); ops.releaseCancel.countDown(); ops.releaseClose.countDown(); session.close();
            assertTrue(session.awaitClosed(3000));
            CallSessionController current = (CallSessionController) field(session, "controller");
            if (current != null) assertTrue(current.awaitClosed(2500));
            until(() -> wire.closes.get() == 1);
            // 只在测试中回收刻意保留的锁；生产不存在强制解锁入口。
            MediaFiles.Lease held = (MediaFiles.Lease) field(session, "lease");
            if (held != null) held.close();
        }
    }
    interface Check { boolean ok() throws Exception; }
    static void until(Check condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) { if (condition.ok()) return; Thread.sleep(3); }
        assertTrue("状态未在期限内满足", condition.ok());
    }
    static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    static void set(Object value, String name, Object content) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.set(value, content);
    }
    static void locked(File files) throws Exception {
        try (MediaFiles.Lease ignored = MediaFiles.lease(new File(files, "media"))) { fail("媒体锁提前释放"); }
        catch (IOException busy) { assertEquals("MEDIA_BUSY", busy.getMessage()); }
    }
    static void unlocked(File files) throws Exception { try (MediaFiles.Lease ignored = MediaFiles.lease(new File(files, "media"))) { } }
    static void fast(Runnable call) throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try { Future<?> result = caller.submit(call); result.get(500, TimeUnit.MILLISECONDS); }
        finally { caller.shutdownNow(); }
    }
    // 直接登记已创建会话，复用真实外层owner/租约/控制锁逻辑；不绕过或修改生产解析器。
    static AppMediaController outer(Fixture f, Object owner) throws Exception {
        AppMediaController outer = new AppMediaController(null, f.time);
        set(outer, "session", f.session); set(outer, "owner", owner); set(outer, "sessionId", "call-test");
        set(outer, "startRequest", "call-start"); set(outer, "renewed", f.time.elapsed()); return outer;
    }

    @Test public void constructionIsInertAndCloseBeforeStartIsOneShot() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("idle", f.session.snapshot().getString("state")); assertFalse(new File(f.files, "media").exists());
            f.session.stop(); assertTrue(f.session.awaitClosed(2000)); assertEquals(0, f.factories.get()); assertEquals(0, f.wire.connects.get());
            assertEquals("closed", f.session.snapshot().getString("state"));
            try { f.session.start(); fail(); } catch (IOException expected) { assertEquals("MEDIA_SESSION_NOT_REUSABLE", expected.getMessage()); }
        }
    }
    @Test public void wrongModeIsRejectedWithoutSideEffects() throws Exception {
        Time time = new Time(); Wire wire = new Wire(); File files = temporary.newFolder();
        try { new AppCallSession(files, offer(time, "ptt"), time, wire, (sender, signals) -> null); fail(); }
        catch (IOException expected) { assertEquals("MEDIA_CALL_MODE_REQUIRED", expected.getMessage()); }
        assertEquals(0, wire.connects.get()); assertFalse(new File(files, "media").exists());
    }
    @Test public void factoryRunsOnlyOnExistingMediaWorkerAfterHello() throws Exception {
        try (Fixture f = new Fixture()) {
            f.start(); assertEquals(0, f.factories.get()); f.wire.emit(hello()); until(() -> f.ops.opens.get() == 1);
            assertEquals("d31-call-session", f.ops.factoryThread); assertEquals(f.ops.factoryThread, f.ops.openThread);
            assertEquals(CallProtocol.Route.SPEAKER, f.ops.route);
            try { f.session.start(); fail(); } catch (IOException expected) { assertEquals("MEDIA_SESSION_NOT_REUSABLE", expected.getMessage()); }
        }
    }
    @Test public void blockedConnectDoesNotBlockStopOrSnapshot() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.blockConnect = true; f.start(); locked(f.files);
            fast(() -> { f.session.tick(); f.session.stop(); }); assertTrue(f.session.awaitClosed(2000));
            assertEquals(0, f.factories.get()); assertEquals(1, f.wire.closes.get()); unlocked(f.files);
        }
    }
    @Test public void expiredOfferNeverConnectsAndReleasesEverything() throws Exception {
        try (Fixture f = new Fixture()) {
            f.time.offset = 46000; f.session.start(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_EXPIRED", f.session.snapshot().getString("reason")); assertEquals(0, f.wire.connects.get()); assertEquals(0, f.factories.get()); unlocked(f.files);
        }
    }
    @Test public void sameMediaLockRejectsCallWithoutDisturbingExistingOwner() throws Exception {
        try (Fixture f = new Fixture(); MediaFiles.Lease held = MediaFiles.lease(new File(f.files, "media"))) {
            f.session.start(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_BUSY", f.session.snapshot().getString("reason")); assertEquals(0, f.wire.connects.get()); locked(f.files);
        }
    }
    @Test public void secondSessionCanAcquireLockAfterFirstPhysicalCleanup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.stream(); f.session.stop(); assertTrue(f.session.awaitClosed(2000)); unlocked(f.files);
            Wire nextWire = new Wire(); AppCallSession next = new AppCallSession(f.files, offer(f.time, "call"), f.time, nextWire, (sender, signals) -> null);
            try { next.start(); assertTrue(nextWire.entered.await(1000, TimeUnit.MILLISECONDS)); locked(f.files); }
            finally { next.stop(); assertTrue(next.awaitClosed(2000)); }
            unlocked(f.files);
        }
    }
    @Test public void factoryFailureOrNullResultCleansTransportAndLock() throws Exception {
        for (boolean nullResult : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            f.factoryFails = !nullResult; f.factoryNull = nullResult; f.negotiate(); assertTrue(f.session.awaitClosed(2000));
            assertEquals(0, f.ops.opens.get()); assertEquals(0, f.ops.closes.get());
            assertFalse(f.session.snapshot().toString().contains("private")); assertTrue(f.session.snapshot().getBoolean("cleanup_complete")); unlocked(f.files);
        }
    }
    @Test public void stopDuringFactoryClosesLateReturnedOperationsWithoutOpen() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockFactory = true; f.negotiate(); assertTrue(f.factoryEntered.await(1000, TimeUnit.MILLISECONDS));
            fast(f.session::stop); locked(f.files); f.releaseFactory.countDown(); assertTrue(f.session.awaitClosed(2000));
            assertEquals(0, f.ops.opens.get()); assertEquals(1, f.ops.closes.get()); assertTrue(f.wire.sent.isEmpty()); unlocked(f.files);
        }
    }
    @Test public void connectAndNativeFailuresAreRedactedAndReleased() throws Exception {
        for (boolean nativeFailure : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            f.wire.connectFails = !nativeFailure; f.ops.nativeFails = nativeFailure;
            if (nativeFailure) f.negotiate(); else f.start();
            assertTrue(f.session.awaitClosed(2000)); assertFalse(f.session.snapshot().toString().contains("private"));
            assertEquals(nativeFailure ? 1 : 0, f.ops.closes.get()); unlocked(f.files);
        }
    }
    @Test public void realWireEnvelopeAndEarlyTracksReachFiveRpcFlow() throws Exception {
        try (Fixture f = new Fixture()) {
            f.negotiate(); until(() -> f.ops.muted);
            assertEquals(Arrays.asList("new", "publish", "published", "subscribe", "answer"), f.wire.actions());
            assertEquals("call", f.wire.receivedOffer.mode); assertEquals("synthetic_test_token_1234", f.wire.receivedOffer.token);
            assertTrue(f.session.snapshot().getBoolean("published")); assertFalse(f.session.snapshot().getBoolean("ready"));
            assertEquals(0, f.ops.unmutes.get());
            String text = f.session.snapshot().toString();
            assertFalse(text.contains("synthetic_test_token")); assertFalse(text.contains("offline-test-sdp")); assertFalse(text.contains("control.example"));
            assertFalse(f.session.snapshot().getBoolean("local_recording"));
        }
    }
    @Test public void answerReceiptAndBothProofsAreRequired() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.withholdAnswer = true; f.ops.input = 1; f.ops.output = 1; f.negotiate(); until(() -> f.wire.actions().size() == 5);
            assertFalse(f.ops.ack); assertFalse(f.ops.muted); assertEquals(0, f.ops.unmutes.get());
            f.wire.reply(5, new JSONObject()); until(() -> f.session.snapshot().optBoolean("ready")); assertEquals(1, f.ops.unmutes.get());
        }
    }
    @Test public void unverifiedInputOrOutputDoesNotClaimEmptyDevicePass() throws Exception {
        for (boolean inputFailure : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            f.ops.input = inputFailure ? -1 : 1; f.ops.output = inputFailure ? 1 : -1;
            f.wire.disconnectOnAbort = true; f.negotiate(); assertTrue(f.session.awaitClosed(2000));
            assertEquals(inputFailure ? "MEDIA_CALL_INPUT_UNVERIFIED" : "MEDIA_CALL_OUTPUT_UNVERIFIED", f.session.snapshot().getString("reason"));
            assertEquals(0, f.ops.unmutes.get()); assertFalse(f.session.snapshot().getBoolean("ready")); unlocked(f.files);
        }
    }
    @Test public void callbackFramesAreRequiredAndSilenceIsValid() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.input = 1; f.ops.output = 1; f.ops.playback = false; f.negotiate(); until(() -> f.ops.muted);
            assertEquals(0, f.ops.unmutes.get()); f.ops.playback = true; f.ops.signals.changed(true);
            until(() -> f.session.snapshot().optBoolean("ready")); assertEquals("NOT_VERIFIED", f.session.snapshot().getString("remote_audio_content"));
        }
    }
    @Test public void disconnectionCancelsBlockedOpenAndLateEventsAreInert() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.blockOpen = true; f.negotiate(); assertTrue(f.ops.openEntered.await(1000, TimeUnit.MILLISECONDS));
            f.wire.events.disconnected(); assertTrue(f.session.awaitClosed(2000)); String before = f.session.snapshot().toString();
            f.wire.reply(1, new JSONObject()); f.wire.emit(hello()); f.ops.signals.changed(true); f.ops.signals.failed("MEDIA_LATE_ERROR");
            f.session.stop(); f.session.tick(); assertEquals(before, f.session.snapshot().toString()); assertEquals(1, f.ops.closes.get());
        }
    }
    @Test public void rpcAndHelloTimeoutsHaveStableReasons() throws Exception {
        for (boolean rpc : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            f.wire.disconnectOnAbort = true; f.wire.noReplies = true; f.start();
            if (rpc) { f.wire.emit(hello()); until(() -> f.wire.sent.size() == 1); }
            f.time.offset = rpc ? 21000 : 46000; f.session.tick(); assertTrue(f.session.awaitClosed(2000));
            assertEquals(rpc ? "MEDIA_RPC_TIMEOUT" : "MEDIA_SESSION_TIMEOUT", f.session.snapshot().getString("reason")); unlocked(f.files);
        }
    }
    @Test public void durationLimitIsThirtyMinutesAndWallTimestampsRemainTerminal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.stream(); assertTrue(f.session.snapshot().getLong("started_at_ms") > 0);
            f.time.offset = 60000; f.session.tick(); assertTrue(f.session.snapshot().getBoolean("ready"));
            f.time.offset = CallProtocol.MAX_DURATION_MS + 1; f.session.tick(); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_CALL_DURATION_EXPIRED", f.session.snapshot().getString("reason"));
            assertTrue(f.session.snapshot().getLong("ended_at_ms") > 0); unlocked(f.files);
        }
    }
    @Test public void cleanupFailureRetainsLockAndBothReasons() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.cleanupFails = true; f.negotiate(); until(() -> f.ops.muted); f.session.stop("MEDIA_FOCUS_LOST"); assertTrue(f.session.awaitClosed(2000));
            assertEquals("MEDIA_FOCUS_LOST", f.session.snapshot().getString("reason")); assertEquals("MEDIA_CALL_ROUTE_UNRELEASED", f.session.snapshot().getString("cleanup_reason"));
            assertEquals("release_unconfirmed", f.session.snapshot().getString("state")); locked(f.files);
        }
    }
    @Test public void cancelExceptionsDoNotOverrideConfirmedResourceRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.cancelFails = true; f.wire.abortFails = true; f.negotiate(); until(() -> f.ops.muted);
            f.session.stop(); assertTrue(f.session.awaitClosed(2000)); assertTrue(f.session.snapshot().getBoolean("cleanup_complete"));
            assertEquals(1, f.ops.closes.get()); assertEquals(1, f.wire.closes.get()); unlocked(f.files);
        }
    }
    @Test public void blockedDelegateCancelCannotHoldOuterOwnerDeathOrQuery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.blockCancel = true; f.stream(); Object owner = new Object(); AppMediaController outer = outer(f, owner);
            fast(() -> outer.ownerDied(owner)); assertTrue(f.ops.cancelEntered.await(1000, TimeUnit.MILLISECONDS));
            fast(() -> { try { outer.query("call-test", owner); outer.stop("call-test"); } catch (Exception error) { throw new RuntimeException(error); } });
            locked(f.files); assertFalse(f.session.awaitClosed(20)); f.ops.releaseCancel.countDown();
            assertTrue(f.session.awaitClosed(2000)); assertFalse(outer.hasActive()); unlocked(f.files);
        }
    }
    @Test public void blockedTransportAbortHasOneOrderedCloseAndDoesNotHoldOwnerLock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.blockAbort = true; f.start(); Object owner = new Object(); AppMediaController outer = outer(f, owner);
            fast(() -> outer.ownerDied(owner)); assertTrue(f.wire.abortEntered.await(1000, TimeUnit.MILLISECONDS));
            fast(() -> { try { outer.query("call-test", owner); } catch (Exception error) { throw new RuntimeException(error); } });
            assertEquals(0, f.wire.closes.get()); locked(f.files); f.wire.releaseAbort.countDown();
            assertTrue(f.session.awaitClosed(2000)); assertEquals(Arrays.asList("abort", "close"), f.wire.cleanupOrder); unlocked(f.files);
        }
    }
    @Test public void transportBarrierTimeoutRetainsLockEvenAfterLateClose() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.blockAbort = true; f.start(); f.session.stop(); assertTrue(f.session.awaitClosed(3000));
            assertEquals("MEDIA_TRANSPORT_RELEASE_UNCONFIRMED", f.session.snapshot().getString("cleanup_reason")); locked(f.files);
            f.wire.releaseAbort.countDown(); until(() -> f.wire.closes.get() == 1);
            assertEquals("release_unconfirmed", f.session.snapshot().getString("state")); locked(f.files);
        }
    }
    @Test public void pendingCloseDoesNotReleaseMediaLockAndFailureIsHonest() throws Exception {
        for (boolean blocked : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            f.wire.blockClose = blocked; f.wire.closeFails = !blocked; f.start(); f.session.stop();
            assertTrue(f.wire.closeEntered.await(1000, TimeUnit.MILLISECONDS));
            if (blocked) { locked(f.files); assertFalse(f.session.awaitClosed(20)); f.wire.releaseClose.countDown(); }
            assertTrue(f.session.awaitClosed(2000));
            if (blocked) unlocked(f.files); else { locked(f.files); assertFalse(f.session.snapshot().getBoolean("cleanup_complete")); }
        }
    }
    @Test public void peerTimeoutKeepsLockAndDoesNotUpgradeAfterLateRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.blockClose = true; f.negotiate(); until(() -> f.ops.muted); f.session.stop();
            assertTrue(f.ops.closeEntered.await(1000, TimeUnit.MILLISECONDS)); f.time.offset += AppCallSession.CLEANUP_TIMEOUT_MS + 1; f.session.tick();
            assertTrue(f.session.awaitClosed(100)); assertEquals("MEDIA_CALL_CLEANUP_TIMEOUT", f.session.snapshot().getString("cleanup_reason"));
            f.ops.releaseClose.countDown(); assertTrue(f.ops.closeExited.await(1000, TimeUnit.MILLISECONDS)); locked(f.files);
            assertFalse(f.session.snapshot().getBoolean("cleanup_complete"));
        }
    }
    @Test public void cleanupHasRealTimeFallbackWithoutAdvancingFakeClock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.blockClose = true; f.negotiate(); until(() -> f.ops.muted); f.session.stop();
            assertTrue(f.ops.closeEntered.await(1000, TimeUnit.MILLISECONDS));
            set(f.session, "stopNanos", System.nanoTime() - TimeUnit.SECONDS.toNanos(9)); f.session.tick();
            assertTrue(f.session.awaitClosed(100)); assertEquals("MEDIA_CALL_CLEANUP_TIMEOUT", f.session.snapshot().getString("cleanup_reason")); locked(f.files);
        }
    }
    @Test public void blockedConnectCannotBeDeclaredReleasedByEarlyTransportClose() throws Exception {
        try (Fixture f = new Fixture()) {
            f.wire.blockConnect = true; f.wire.ignoreAbortConnect = true; f.start(); f.session.stop();
            until(() -> f.wire.closes.get() == 1); assertFalse(f.session.awaitClosed(20)); locked(f.files);
            f.time.offset += AppCallSession.CLEANUP_TIMEOUT_MS + 1; f.session.tick(); assertTrue(f.session.awaitClosed(100));
            assertFalse(f.session.snapshot().getBoolean("cleanup_complete")); locked(f.files);
        }
    }
    @Test public void lateFactoryAfterCleanupTimeoutStillClosesItsReturnedObject() throws Exception {
        try (Fixture f = new Fixture()) {
            f.blockFactory = true; f.negotiate(); assertTrue(f.factoryEntered.await(1000, TimeUnit.MILLISECONDS)); f.session.stop();
            f.time.offset += AppCallSession.CLEANUP_TIMEOUT_MS + 1; f.session.tick(); assertTrue(f.session.awaitClosed(100)); locked(f.files);
            f.releaseFactory.countDown(); assertTrue(f.ops.closeExited.await(1000, TimeUnit.MILLISECONDS));
            assertEquals(0, f.ops.opens.get()); assertEquals(1, f.ops.closes.get()); assertFalse(f.session.snapshot().getBoolean("cleanup_complete"));
        }
    }
    @Test public void senderAndShellShareExactlyOneTransportClose() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.senderClose = true; f.stream(); f.session.stop(); assertTrue(f.session.awaitClosed(2000));
            f.session.close(); f.ops.sender.abort(); f.ops.sender.close();
            assertEquals(1, f.wire.aborts.get()); assertEquals(1, f.wire.closes.get()); assertEquals(1, f.ops.closes.get()); unlocked(f.files);
        }
    }
    @Test public void realOuterOwnerDeathServiceExitAndRequestCancelStopSession() throws Exception {
        for (int action = 0; action < 3; action++) try (Fixture f = new Fixture()) {
            f.stream(); Object owner = new Object(); AppMediaController outer = outer(f, owner);
            outer.ownerDied(new Object()); assertTrue(outer.hasActive());
            if (action == 0) outer.ownerDied(owner); else if (action == 1) outer.serviceDestroyed(); else outer.cancelRequest("call-start");
            assertTrue(f.session.awaitClosed(2000)); assertFalse(outer.hasActive()); unlocked(f.files);
        }
    }
    @Test public void outerLeaseRenewsOnlyForOwnerAndFailedReleaseRemainsBusy() throws Exception {
        try (Fixture f = new Fixture()) {
            f.ops.cleanupFails = true; f.stream(); Object owner = new Object(); AppMediaController outer = outer(f, owner);
            f.time.offset = 14000; outer.query("call-test", owner); f.time.offset = 16000; outer.tick(); assertTrue(f.session.snapshot().getBoolean("ready"));
            outer.query("", owner); outer.query("call-test", new Object()); f.time.offset = 30000; outer.tick();
            assertTrue(f.session.awaitClosed(2000)); assertTrue(outer.hasActive()); locked(f.files);
        }
    }
    @Test public void normalTerminalThreadsExitAndSnapshotCannotBeMutated() throws Exception {
        try (Fixture f = new Fixture()) {
            f.stream(); f.session.snapshot().put("state", "forged"); assertEquals("streaming", f.session.snapshot().getString("state"));
            f.session.stop(); assertTrue(f.session.awaitClosed(2000));
            until(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("d31-call-")));
        }
    }
}
