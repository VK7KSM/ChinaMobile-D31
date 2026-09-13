package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.media.CallProtocolTest.*;

public class CallSessionControllerTest {
    private static final class Ops implements CallSessionController.Operations {
        volatile CallSessionController owner;
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> rpc = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch cancelEntered = new CountDownLatch(1), cancelRelease = new CountDownLatch(1);
        volatile String blocking = "", failure = "", closeError = "";
        volatile boolean blockCancel, cancelThrows, cancelUnblocks = true, cancelled, noAnswer;
        volatile boolean bindingRejected;
        volatile Object sfuError = JSONObject.NULL;
        volatile boolean capture = true, playback = true;
        volatile int input = 1, output = 1;
        volatile CallProtocol.Route route;
        final AtomicInteger closeCount = new AtomicInteger(), cancelCount = new AtomicInteger(), readyCount = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        private void step(String name) throws Exception {
            threads.add(Thread.currentThread().getName()); events.add(name);
            int count = active.incrementAndGet(); maxActive.accumulateAndGet(count, Math::max);
            try {
                if (name.equals(blocking)) { blocked.countDown(); if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("MEDIA_TEST_BLOCK_TIMEOUT"); }
                if (name.equals(failure)) throw new IOException("MEDIA_TEST_OPERATION_FAILED");
            } finally { active.decrementAndGet(); }
        }
        public void open(CallProtocol.Route route) throws Exception { this.route = route; step("open"); owner.ice(true); }
        public void send(JSONObject message) throws Exception {
            if ("ready".equals(message.getString("type"))) { step("ready"); readyCount.incrementAndGet(); return; }
            String action = message.getString("action"); step("send_" + action); rpc.add(action);
            JSONObject result = new JSONObject();
            if ("new".equals(action)) result.put("sessionId", "local");
            if ("publish".equals(action)) result = sdp("answer").put("opaque", 5).put("errorCode", sfuError);
            if ("subscribe".equals(action)) result = (noAnswer ? new JSONObject() : sdp("offer"))
                    .put("errorCode", sfuError).put("tracks", new org.json.JSONArray().put(new JSONObject()
                            .put("trackName", "audio").put("mid", "1").put("errorCode", sfuError)));
            // 在send尚未返回时直接送回RPC，覆盖回执与动作执行器的重入边界。
            owner.receive(reply(message, result).toString());
        }
        public JSONObject createPublish(JSONObject body) throws Exception {
            assertEquals("local", body.getString("sessionId")); step("create_publish"); return offer();
        }
        public void applyPublish(JSONObject body) throws Exception { assertEquals(5, body.getInt("opaque")); step("apply_publish"); }
        public CallProtocol.SubscriptionResult subscribe(JSONObject body) throws Exception {
            step("subscribe");
            assertFalse(body.getJSONArray("tracks").getJSONObject(0).has("location"));
            if (bindingRejected) throw new IOException("MEDIA_DOWNLINK_TRACKS_INVALID");
            return noAnswer ? CallProtocol.SubscriptionResult.completed() : CallProtocol.SubscriptionResult.answer(sdp("answer"));
        }
        public void negotiationComplete() throws Exception { step("negotiated"); }
        public void prepareMuted() throws Exception { step("prepare"); }
        public int inputProof() throws Exception { step("input"); return input; }
        public int outputProof() throws Exception { step("output"); return output; }
        public boolean captureFrames() { return capture; }
        public boolean playbackFrames() { return playback; }
        public void unmute() throws Exception {
            step("unmute");
            if (cancelled) throw new IOException("MEDIA_CANCELLED");
        }
        public void cancel() {
            cancelCount.incrementAndGet(); cancelled = true; cancelEntered.countDown();
            if (cancelUnblocks) release.countDown();
            if (blockCancel) try { cancelRelease.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (cancelThrows) throw new IllegalStateException("MEDIA_TEST_CANCEL_FAILED");
        }
        public void close() throws Exception {
            closeCount.incrementAndGet(); step("close");
            if (!closeError.isEmpty()) throw new IOException(closeError);
        }
    }
    private static CallSessionController create(Clock c, Ops ops, Runnable changed) throws Exception {
        CallSessionController controller = new CallSessionController(c, c.wall + 45000, ops, changed); ops.owner = controller; return controller;
    }
    private static void start(CallSessionController c) { c.receive(tracks("remote").toString()); c.receive(hello().toString()); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(2);
        assertTrue("未在限定时间内达到状态", condition.getAsBoolean());
    }
    private static void finish(CallSessionController c, Ops ops) throws Exception {
        ops.release.countDown(); ops.cancelRelease.countDown(); c.close(); assertTrue(c.awaitClosed(3000));
    }
    private static void nonBlocking(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Thread caller = new Thread(() -> { try { task.run(); } finally { done.countDown(); } }, "test-caller");
        caller.start(); assertTrue("调用者被媒体操作阻塞", done.await(500, TimeUnit.MILLISECONDS)); caller.join(500);
    }

    @Test public void inlineRepliesUseOneSerialWorkerAndAllFiveRpcs() throws Exception {
        Clock clock = new Clock(); Ops ops = new Ops(); CallSessionController c = create(clock, ops, null);
        try {
            start(c); waitFor(() -> ops.readyCount.get() == 1);
            assertEquals(Arrays.asList("new", "publish", "published", "subscribe", "answer"), ops.rpc);
            assertEquals(CallProtocol.Route.SPEAKER, ops.route); assertEquals(1, ops.maxActive.get());
            for (String thread : ops.threads) assertEquals("d31-call-session", thread);
            List<String> expected = Arrays.asList("open", "send_new", "create_publish", "send_publish", "apply_publish", "send_published",
                    "send_subscribe", "subscribe", "send_answer", "negotiated", "prepare", "input", "output", "unmute", "ready");
            assertEquals(expected, new ArrayList<>(ops.events).subList(0, expected.size()));
            assertTrue(c.snapshot().getBoolean("ready"));
        } finally { finish(c, ops); }
        assertEquals(1, ops.closeCount.get()); assertEquals(1, ops.cancelCount.get()); assertTrue(c.snapshot().getBoolean("cleanup_complete"));
    }
    @Test public void explicitNoAnswerPathDoesNotInventAnswerRpc() throws Exception {
        Ops ops = new Ops(); ops.noAnswer = true; CallSessionController c = create(new Clock(), ops, null);
        try { start(c); waitFor(() -> ops.readyCount.get() == 1); assertEquals(Arrays.asList("new", "publish", "published", "subscribe"), ops.rpc); }
        finally { finish(c, ops); }
    }
    @Test public void emptySfuErrorAndOmittedLocationReachActualAdapterGate() throws Exception {
        Ops ops = new Ops(); ops.sfuError = ""; CallSessionController c = create(new Clock(), ops, null);
        try { start(c); waitFor(() -> ops.readyCount.get() == 1); assertTrue(ops.events.contains("subscribe")); }
        finally { finish(c, ops); }
    }
    @Test public void successfulSfuShapeCannotOverrideBindingRejection() throws Exception {
        Ops ops = new Ops(); ops.bindingRejected = true; CallSessionController c = create(new Clock(), ops, null);
        try {
            start(c); assertTrue(c.awaitClosed(2000));
            assertEquals("MEDIA_DOWNLINK_TRACKS_INVALID", c.snapshot().getString("reason"));
            assertFalse(ops.events.contains("unmute")); assertEquals(0, ops.readyCount.get());
            assertTrue(c.snapshot().getBoolean("cleanup_complete"));
        } finally { finish(c, ops); }
    }
    @Test public void blockedOpenDoesNotBlockStopSnapshotOrReceive() throws Exception {
        Ops ops = new Ops(); ops.blocking = "open"; CallSessionController c = create(new Clock(), ops, null);
        try {
            start(c); assertTrue(ops.blocked.await(1000, TimeUnit.MILLISECONDS));
            nonBlocking(() -> { c.snapshot(); c.tick(); c.receive(tracks("remote").toString()); c.stop("MEDIA_OWNER_DIED"); });
            assertTrue(c.awaitClosed(2000)); assertTrue(ops.rpc.isEmpty()); assertEquals(0, ops.readyCount.get());
            assertEquals("MEDIA_OWNER_DIED", c.snapshot().getString("reason"));
        } finally { finish(c, ops); }
    }
    @Test public void blockingCancelIsOffCallerAndPreventsFalseCleanup() throws Exception {
        Clock clock = new Clock(); Ops ops = new Ops(); ops.blockCancel = true; CallSessionController c = create(clock, ops, null);
        try {
            nonBlocking(() -> { c.stop("MEDIA_OWNER_DIED"); c.snapshot(); c.stop("MEDIA_HOST_CLOSED"); });
            assertTrue(ops.cancelEntered.await(1000, TimeUnit.MILLISECONDS)); waitFor(() -> ops.closeCount.get() == 1);
            assertFalse(c.awaitClosed(10)); assertFalse(c.snapshot().getBoolean("cleanup_complete"));
            clock.elapsed += CallProtocol.CLEANUP_TIMEOUT_MS; nonBlocking(c::tick);
            assertEquals("MEDIA_CALL_CLEANUP_TIMEOUT", c.snapshot().getString("cleanup_reason"));
            ops.cancelRelease.countDown(); assertTrue(c.awaitClosed(2000));
            assertFalse(c.snapshot().getBoolean("cleanup_complete")); assertEquals(1, ops.cancelCount.get());
        } finally { finish(c, ops); }
    }
    @Test public void cancelExceptionCannotSkipSuccessfulClose() throws Exception {
        Ops ops = new Ops(); ops.cancelThrows = true; CallSessionController c = create(new Clock(), ops, null);
        try { c.stop("MEDIA_OWNER_DIED"); assertTrue(c.awaitClosed(2000)); assertTrue(c.snapshot().getBoolean("cleanup_complete")); assertEquals(1, ops.closeCount.get()); }
        finally { finish(c, ops); }
    }
    @Test public void physicalCloseFailureCannotBeUpgradedByRepeatedClose() throws Exception {
        Ops ops = new Ops(); ops.closeError = "MEDIA_ROUTE_NOT_RELEASED"; CallSessionController c = create(new Clock(), ops, null);
        try {
            c.stop("MEDIA_OWNER_DIED"); assertTrue(c.awaitClosed(2000));
            for (int i = 0; i < 100; i++) c.close();
            assertFalse(c.snapshot().getBoolean("cleanup_complete")); assertEquals("MEDIA_ROUTE_NOT_RELEASED", c.snapshot().getString("cleanup_reason"));
            assertEquals("MEDIA_OWNER_DIED", c.snapshot().getString("reason")); assertEquals(1, ops.closeCount.get());
        } finally { finish(c, ops); }
    }
    @Test public void blockedCloseHasBoundedHonestSnapshot() throws Exception {
        Clock clock = new Clock(); Ops ops = new Ops(); ops.blocking = "close"; ops.cancelUnblocks = false;
        CallSessionController c = create(clock, ops, null);
        try {
            c.close(); assertTrue(ops.blocked.await(1000, TimeUnit.MILLISECONDS));
            clock.elapsed += CallProtocol.CLEANUP_TIMEOUT_MS; nonBlocking(() -> { c.tick(); c.snapshot(); c.close(); });
            assertFalse(c.awaitClosed(10)); assertFalse(c.snapshot().getBoolean("cleanup_complete"));
            ops.release.countDown(); assertTrue(c.awaitClosed(2000));
            assertEquals("MEDIA_CALL_CLEANUP_TIMEOUT", c.snapshot().getString("cleanup_reason"));
        } finally { finish(c, ops); }
    }
    @Test public void lateBlockedNegotiationCompletionCannotReviveStoppedCall() throws Exception {
        for (String phase : new String[]{"create_publish", "apply_publish", "subscribe", "negotiated", "prepare", "unmute"}) {
            Ops ops = new Ops(); ops.blocking = phase; ops.cancelUnblocks = false;
            CallSessionController c = create(new Clock(), ops, null);
            try {
                start(c); assertTrue(phase, ops.blocked.await(1000, TimeUnit.MILLISECONDS));
                nonBlocking(() -> c.stop("MEDIA_OWNER_DIED"));
                int sent = ops.rpc.size(); ops.release.countDown(); assertTrue(c.awaitClosed(2000));
                assertEquals(phase, sent, ops.rpc.size()); assertEquals(0, ops.readyCount.get()); assertEquals(1, ops.closeCount.get());
            } finally { finish(c, ops); }
        }
    }
    @Test public void stopDuringInputProofSkipsLaterOutputObservation() throws Exception {
        Ops ops = new Ops(); ops.blocking = "input"; ops.cancelUnblocks = false;
        CallSessionController c = create(new Clock(), ops, null);
        try {
            start(c); assertTrue(ops.blocked.await(1000, TimeUnit.MILLISECONDS)); c.stop("MEDIA_OWNER_DIED");
            ops.release.countDown(); assertTrue(c.awaitClosed(2000));
            assertFalse(ops.events.contains("output")); assertFalse(ops.events.contains("unmute"));
        } finally { finish(c, ops); }
    }
    @Test public void everyOperationFailureClosesExactlyOnce() throws Exception {
        for (String phase : new String[]{"open", "send_new", "create_publish", "send_publish", "apply_publish", "send_published",
                "send_subscribe", "subscribe", "send_answer", "negotiated", "prepare", "input", "output", "unmute", "ready"}) {
            Ops ops = new Ops(); ops.failure = phase; CallSessionController c = create(new Clock(), ops, null);
            try {
                start(c); assertTrue(phase, c.awaitClosed(2000)); assertEquals(phase, 1, ops.closeCount.get());
                assertEquals("MEDIA_TEST_OPERATION_FAILED", c.snapshot().getString("reason"));
                assertTrue(c.snapshot().getBoolean("cleanup_complete")); assertFalse(c.snapshot().getBoolean("ready"));
            } finally { finish(c, ops); }
        }
    }
    @Test public void realProofRejectionIsNotReplacedByCallbackFrames() throws Exception {
        Ops ops = new Ops(); ops.output = -1; CallSessionController c = create(new Clock(), ops, null);
        try { start(c); assertTrue(c.awaitClosed(2000)); assertFalse(ops.events.contains("unmute")); assertEquals("MEDIA_CALL_PROOF_INVALID", c.snapshot().getString("reason")); }
        finally { finish(c, ops); }
    }
    @Test public void waitingInputAndOutputProofsMustBothResolve() throws Exception {
        Ops ops = new Ops(); ops.input = 0; ops.output = 0; CallSessionController c = create(new Clock(), ops, null);
        try {
            start(c); waitFor(() -> ops.events.contains("output")); assertEquals(0, ops.readyCount.get());
            ops.input = 1; c.mediaChanged(); waitFor(() -> jsonBoolean(c.snapshot(), "input_verified")); assertEquals(0, ops.readyCount.get());
            ops.output = 1; c.mediaChanged(); waitFor(() -> ops.readyCount.get() == 1);
        } finally { finish(c, ops); }
    }
    @Test public void bothPhysicalDirectionsNeedCallbacksEvenWithProofs() throws Exception {
        Ops ops = new Ops(); ops.capture = false; ops.playback = false; CallSessionController c = create(new Clock(), ops, null);
        try {
            start(c); waitFor(() -> jsonBoolean(c.snapshot(), "output_verified")); assertEquals(0, ops.readyCount.get());
            ops.capture = true; c.mediaChanged(); waitFor(() -> jsonBoolean(c.snapshot(), "capture_frames_seen")); assertEquals(0, ops.readyCount.get());
            ops.playback = true; c.mediaChanged(); waitFor(() -> ops.readyCount.get() == 1);
        } finally { finish(c, ops); }
    }
    @Test public void activeGuardLossCloses() throws Exception {
        Ops ops = new Ops(); CallSessionController c = create(new Clock(), ops, null);
        try {
            start(c); waitFor(() -> ops.readyCount.get() == 1); ops.output = 0; c.mediaChanged();
            assertTrue(c.awaitClosed(2000)); assertEquals("MEDIA_CALL_OWNERSHIP_LOST", c.snapshot().getString("reason"));
        } finally { finish(c, ops); }
    }
    @Test public void malformedInputStartsCleanupWithoutOpening() throws Exception {
        for (String raw : new String[]{null, "[]", "not json", new String(new char[96001])}) {
            Ops ops = new Ops(); CallSessionController c = create(new Clock(), ops, null);
            try { c.receive(raw); assertTrue(c.awaitClosed(2000)); assertFalse(ops.events.contains("open")); assertEquals("MEDIA_CALL_MESSAGE_INVALID", c.snapshot().getString("reason")); }
            finally { finish(c, ops); }
        }
    }
    @Test public void floodWhileBlockedIsCoalescedAndStopDiscardsActions() throws Exception {
        Ops ops = new Ops(); ops.blocking = "open"; ops.cancelUnblocks = false; AtomicInteger notifications = new AtomicInteger();
        CallSessionController c = create(new Clock(), ops, notifications::incrementAndGet);
        try {
            start(c); assertTrue(ops.blocked.await(1000, TimeUnit.MILLISECONDS));
            for (int i = 0; i < 5000; i++) { c.mediaChanged(); c.tick(); c.receive(tracks("remote").toString()); }
            c.stop("MEDIA_OWNER_DIED"); ops.release.countDown(); assertTrue(c.awaitClosed(2000));
            assertTrue(ops.rpc.isEmpty()); assertTrue(notifications.get() < 10); assertEquals(1, ops.closeCount.get());
        } finally { finish(c, ops); }
    }
    @Test public void listenerExceptionsDoNotBreakCleanup() throws Exception {
        Ops ops = new Ops(); CallSessionController c = create(new Clock(), ops, () -> { throw new LinkageError("listener"); });
        try { start(c); waitFor(() -> ops.readyCount.get() == 1); }
        finally { finish(c, ops); }
        assertTrue(c.snapshot().getBoolean("cleanup_complete"));
    }
    @Test public void lateCancelCompletionPublishesFinalCleanupState() throws Exception {
        Ops ops = new Ops(); ops.blockCancel = true; CountDownLatch notification = new CountDownLatch(1);
        CallSessionController c = create(new Clock(), ops, () -> {
            if (jsonBoolean(ops.owner.snapshot(), "cleanup_complete")) notification.countDown();
        });
        try {
            c.close(); assertTrue(ops.cancelEntered.await(1000, TimeUnit.MILLISECONDS)); waitFor(() -> ops.closeCount.get() == 1);
            ops.cancelRelease.countDown(); assertTrue(c.awaitClosed(2000)); assertTrue(notification.await(2000, TimeUnit.MILLISECONDS));
        } finally { finish(c, ops); }
    }
    @Test public void inviteTimeoutCancelsBlockedOperationWithoutLateSend() throws Exception {
        Clock clock = new Clock(); Ops ops = new Ops(); ops.blocking = "open"; CallSessionController c = create(clock, ops, null);
        try {
            start(c); assertTrue(ops.blocked.await(1000, TimeUnit.MILLISECONDS)); clock.elapsed += 45000; c.tick();
            assertTrue(c.awaitClosed(2000)); assertTrue(ops.rpc.isEmpty()); assertEquals("MEDIA_SESSION_TIMEOUT", c.snapshot().getString("reason"));
        } finally { finish(c, ops); }
    }
    @Test public void durationTimeoutUsesThirtyMinutesNotPttMinute() throws Exception {
        Clock clock = new Clock(); Ops ops = new Ops(); CallSessionController c = create(clock, ops, null);
        try {
            start(c); waitFor(() -> ops.readyCount.get() == 1); clock.elapsed += 60000; c.tick(); assertTrue(c.snapshot().getBoolean("ready"));
            clock.elapsed += CallProtocol.MAX_DURATION_MS - 60000; c.tick(); assertTrue(c.awaitClosed(2000));
            assertEquals("MEDIA_CALL_DURATION_EXPIRED", c.snapshot().getString("reason"));
        } finally { finish(c, ops); }
    }
    @Test public void explicitHandsetRouteReachesAdapter() throws Exception {
        Clock clock = new Clock(); Ops ops = new Ops();
        CallSessionController c = new CallSessionController(clock, clock.wall + 45000, ops, null, CallProtocol.Route.HANDSET); ops.owner = c;
        try { start(c); waitFor(() -> ops.readyCount.get() == 1); assertEquals(CallProtocol.Route.HANDSET, ops.route); }
        finally { finish(c, ops); }
    }
    @Test public void repeatedCloseAndLateMessagesRemainTerminal() throws Exception {
        Ops ops = new Ops(); CallSessionController c = create(new Clock(), ops, null);
        try {
            c.close(); assertTrue(c.awaitClosed(2000));
            for (int i = 0; i < 100; i++) { start(c); c.ice(true); c.mediaChanged(); c.tick(); c.close(); }
            assertTrue(ops.rpc.isEmpty()); assertEquals(1, ops.closeCount.get()); assertEquals(1, ops.cancelCount.get());
            assertEquals("closed", c.snapshot().getString("state"));
        } finally { finish(c, ops); }
    }
    @Test public void ownedThreadsEventuallyExitAfterSuccessfulClose() throws Exception {
        Ops ops = new Ops(); CallSessionController c = create(new Clock(), ops, null);
        try { start(c); waitFor(() -> ops.readyCount.get() == 1); }
        finally { finish(c, ops); }
        waitFor(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("d31-call-")));
    }
}
