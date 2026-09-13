package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static net.elfradio.d31bootstrap.media.AudioCaptureLifecycleTest.*;
import static org.junit.Assert.*;

/** 实际Controller加阻塞假驱动，验证控制响应、串行清理与释放未证实的槽位保留。 */
public final class AudioCaptureLifecycleSessionTest {
    private static final class Driver implements AudioCaptureLifecycleSession.Driver {
        final Time time;
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean returned, failStart, failRelease, linkage;
        volatile Cancellation cancellation;
        Driver(Time time) { this.time = time; }
        public void start(Cancellation cancellation) throws Exception {
            this.cancellation = cancellation; entered.countDown();
            if (!proceed.await(2, TimeUnit.SECONDS)) throw new IOException("测试启动门超时");
            returned = true;
            if (linkage) throw new NoSuchMethodError("测试API不可用");
            if (failStart) throw new IOException("测试部分启动失败");
        }
        public AudioCaptureLifecycle.Release stopAndRelease() throws Exception {
            stops.incrementAndGet(); cleaned.countDown();
            if (failRelease) throw new IOException("测试释放失败");
            return released(time);
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Time time = new Time();
        final AudioCaptureLifecycle core = core(time);
        final Driver driver = new Driver(time);
        final Cancellation cancel = new Cancellation();
        final AudioCaptureLifecycleSession session = new AudioCaptureLifecycleSession(core,
                new AudioCaptureLifecycleSession.Source() { public AudioCaptureLifecycle.Evidence cached() { return idle(); } }, driver, cancel);
        public void close() throws Exception { driver.proceed.countDown(); session.close(); awaitPhase(core, driver.failRelease
                ? AudioCaptureLifecycle.Phase.RELEASE_UNCONFIRMED : AudioCaptureLifecycle.Phase.CLOSED); }
    }
    private static void awaitPhase(AudioCaptureLifecycle core, AudioCaptureLifecycle.Phase expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (core.snapshot().phase != expected && System.nanoTime() < deadline) Thread.sleep(2);
        assertEquals(expected, core.snapshot().phase);
    }
    @Test public void cancellationReturnsBeforeBlockedStartAndReleasesOnlyAfterItReturns() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.start(); assertTrue(f.driver.entered.await(1, TimeUnit.SECONDS));
            long before = System.nanoTime(); f.session.stop();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500);
            assertTrue(f.driver.cancellation.isCancelled()); assertEquals(0, f.driver.stops.get());
            f.time.now += 1501; f.session.tick();
            assertEquals(AudioCaptureLifecycle.Phase.RELEASE_UNCONFIRMED, f.core.snapshot().phase);
            f.driver.proceed.countDown(); awaitPhase(f.core, AudioCaptureLifecycle.Phase.CLOSED);
            assertTrue(f.driver.returned); assertEquals(1, f.driver.stops.get());
            f.session.stop(); f.session.tick(); assertEquals(1, f.driver.stops.get());
            assertFalse(f.session.snapshot().getBoolean("continuation_eligible"));
        }
    }
    @Test public void requestCancellationIsForwardedByExistingTick() throws Exception {
        try (Fixture f = new Fixture()) {
            f.session.start(); assertTrue(f.driver.entered.await(1, TimeUnit.SECONDS));
            f.cancel.cancel(); f.session.tick(); assertTrue(f.driver.cancellation.isCancelled());
            f.driver.proceed.countDown(); awaitPhase(f.core, AudioCaptureLifecycle.Phase.CLOSED);
        }
    }
    @Test public void badEvidenceDuringBlockedStartCancelsAndLateStartNeverActivates() throws Exception {
        AudioCaptureLifecycle.Evidence[] bad = {
            evidence(ID, "request-A", "session-1", F, P, 1100, AudioCaptureLifecycle.External.BUSY),
            evidence(ID, "request-A", "session-1", F, P, 1100, AudioCaptureLifecycle.External.UNKNOWN),
            evidence(F, AudioInputOwnershipTest.POLICY, 1100),
            evidence(F, P.replace("Input 18", "Input 19"), 1100),
            evidence(F.replace("555", "556"), P, 1100), null
        };
        for (AudioCaptureLifecycle.Evidence e : bad) try (Fixture f = new Fixture()) {
            f.session.start(); assertTrue(f.driver.entered.await(1, TimeUnit.SECONDS));
            f.time.now = 1140; f.session.observe(e);
            assertEquals(AudioCaptureLifecycle.Phase.STOPPING, f.core.snapshot().phase);
            assertTrue(f.driver.cancellation.isCancelled()); assertEquals(0, f.driver.stops.get());
            f.session.observe(evidence(F, P, 1100)); assertFalse(f.core.snapshot().continuationEligible);
            f.driver.proceed.countDown(); awaitPhase(f.core, AudioCaptureLifecycle.Phase.CLOSED);
            assertTrue(f.driver.returned); assertEquals(1, f.driver.stops.get());
            f.time.now = 1200; f.session.observe(evidence(F, P, 1150));
            assertEquals(AudioCaptureLifecycle.Phase.CLOSED, f.core.snapshot().phase);
            assertFalse(f.core.snapshot().continuationEligible); assertFalse(f.core.snapshot().ioBound);
        }
    }
    @Test public void partialStartExceptionsAndApiLinkageFailureAlwaysClean() throws Exception {
        for (boolean linkage : new boolean[] {false, true}) try (Fixture f = new Fixture()) {
            f.driver.failStart = true; f.driver.linkage = linkage; f.driver.proceed.countDown();
            f.session.start(); awaitPhase(f.core, AudioCaptureLifecycle.Phase.CLOSED);
            assertEquals(1, f.driver.stops.get()); assertFalse(f.core.snapshot().continuationEligible);
        }
    }
    @Test public void outputGateRejectsBeforeProofAndTriggersCleanupWhenEvidenceExpires() throws Exception {
        try (Fixture f = new Fixture()) {
            f.driver.proceed.countDown(); f.session.start(); awaitPhase(f.core, AudioCaptureLifecycle.Phase.VERIFYING);
            try { f.session.requireContinuation(); fail(); } catch (IOException expected) { }
            f.time.now = 1140; f.session.observe(evidence(F, P, 1100)); f.session.requireContinuation();
            f.time.now = 5101;
            try { f.session.requireContinuation(); fail(); } catch (IOException expected) { }
            awaitPhase(f.core, AudioCaptureLifecycle.Phase.CLOSED);
        }
    }
    private static AppMediaController controller(final Fixture f, final AtomicInteger creates) {
        return new AppMediaController(new AppMediaController.Backend() {
            public JSONObject prepare(String hash, Cancellation cancel) { return new JSONObject(); }
            public AppMediaController.Session create(String hash, RtcOffer offer, Cancellation cancel) {
                creates.incrementAndGet(); return f.session;
            }
            public URI origin() throws Exception { return new URI("https://control.example"); }
        }, f.time);
    }
    private static JSONObject command(String id) throws Exception {
        return new JSONObject().put("operation", "start").put("apk_sha256", HASH).put("offer",
                new JSONObject().put("mode", "microphone").put("session_id", id)
                .put("token", "abcdefghijklmnop").put("expires_at", 140000)
                .put("url", "wss://control.example/api/elfremote/media/device?session_id=" + id));
    }
    @Test public void actualControllerRoutesCancelDeathDestroyAndStopWithoutWaitingForNativeStart() throws Exception {
        for (int action = 0; action < 4; action++) try (Fixture f = new Fixture()) {
            AtomicInteger creates = new AtomicInteger(); AppMediaController controller = controller(f, creates);
            Object owner = new Object(); controller.execute("request-A", command("session-1"), owner, f.cancel);
            assertTrue(f.driver.entered.await(1, TimeUnit.SECONDS));
            controller.cancelRequest("request-old"); controller.ownerDied(new Object());
            assertFalse(f.driver.cancellation.isCancelled());
            if (action == 0) controller.cancelRequest("request-A");
            if (action == 1) controller.ownerDied(owner);
            if (action == 2) controller.serviceDestroyed();
            if (action == 3) controller.stop("session-1");
            assertTrue(f.driver.cancellation.isCancelled()); assertTrue(controller.hasActive());
            try { controller.execute("request-B", command("session-2"), owner, new Cancellation()); fail(); }
            catch (IOException expected) { assertEquals("MEDIA_APP_BUSY", expected.getMessage()); }
            assertEquals(1, creates.get()); f.driver.proceed.countDown();
            awaitPhase(f.core, AudioCaptureLifecycle.Phase.CLOSED); assertFalse(controller.hasActive());
        }
    }
    @Test public void actualControllerKeepsBusySlotAfterReleaseFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.driver.failRelease = true; f.driver.proceed.countDown(); AtomicInteger creates = new AtomicInteger();
            AppMediaController controller = controller(f, creates);
            controller.execute("request-A", command("session-1"), this, f.cancel);
            awaitPhase(f.core, AudioCaptureLifecycle.Phase.VERIFYING); controller.stop("session-1");
            awaitPhase(f.core, AudioCaptureLifecycle.Phase.RELEASE_UNCONFIRMED);
            assertTrue(controller.hasActive());
            try { controller.execute("request-B", command("session-2"), this, new Cancellation()); fail(); }
            catch (IOException expected) { assertEquals("MEDIA_APP_BUSY", expected.getMessage()); }
            assertEquals(1, creates.get()); assertFalse(f.session.snapshot().getBoolean("release_confirmed"));
        }
    }
    @Test public void controllerLeaseExpiryUsesTheSameCancellationPath() throws Exception {
        try (Fixture f = new Fixture()) {
            AppMediaController controller = controller(f, new AtomicInteger());
            controller.execute("request-A", command("session-1"), this, f.cancel);
            assertTrue(f.driver.entered.await(1, TimeUnit.SECONDS)); f.time.now += AppMediaContract.LEASE_MS;
            controller.tick(); assertTrue(f.driver.cancellation.isCancelled());
        }
    }
}
