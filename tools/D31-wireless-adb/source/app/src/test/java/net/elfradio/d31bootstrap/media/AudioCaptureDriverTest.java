package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.media.AudioCaptureLifecycleTest.*;

/** 实际LocalAudioCapture与Controller执行包装驱动，不另写一套会话模型。 */
public final class AudioCaptureDriverTest {
    static final MediaCapture.Clock CLOCK = LocalAudioCaptureTest.CLOCK;
    static class Record extends LocalAudioCaptureTest.Recorder {
        volatile boolean inRead;
        volatile int actualSession = 42;
        public int sessionId() { return actualSession; }
        public int read(short[] samples, int count) throws Exception {
            inRead = true;
            try { Thread.sleep(5); return super.read(samples, count); } finally { inRead = false; }
        }
        public void release() throws Exception { assertFalse("禁止与read并发release", inRead); super.release(); }
    }
    static class Fixture implements AutoCloseable {
        final Record record;
        final Cancellation cancel = new Cancellation();
        final AudioCaptureDriver driver;
        final AudioCaptureObservation cache;
        final LocalAudioCapture capture;
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean bad, fail, sampleActive = true;
        Fixture(Record record, int duration) throws Exception {
            this.record = record;
            AudioCaptureLifecycle.Clock clock = () -> CLOCK.elapsed();
            long from = clock.elapsed() - 40;
            AudioCaptureObservation.Sample initial = AudioCaptureObservationTest.sample(AudioInputOwnershipTest.FLINGER,
                    AudioInputOwnershipTest.POLICY, AndroidAudioOccupancyTest.idle(), from);
            cache = new AudioCaptureObservation(ID, "request-A", "diag-1", clock, cancellation -> {
                reads.incrementAndGet(); cancellation.check();
                if (fail) throw new SecurityException("私有权限错误详情");
                JSONObject raw = AudioCaptureObservationTest.ownRaw();
                if (bad) raw.getJSONObject("audio").put("focus_gain", 1);
                return AudioCaptureObservationTest.sample(sampleActive ? F : AudioInputOwnershipTest.FLINGER,
                        sampleActive ? P : AudioInputOwnershipTest.POLICY, raw, clock.elapsed() - 40);
            }, initial);
            driver = new AudioCaptureDriver(record, ID, "request-A", "diag-1", clock, cache, cancel);
            capture = new LocalAudioCapture("diag-1", duration, 555, 10001, c -> driver, cancel, CLOCK);
        }
        public void close() throws Exception { capture.cancel(); if (record.unblock != null) record.unblock.countDown(); cache.close(); }
    }
    @Test public void actualCaptureRequiresOwnershipBeforeFirstReadAndReturnsProof() throws Exception {
        try (Fixture f = new Fixture(new Record(), 2500)) {
            JSONObject result = LocalAudioCaptureTest.run(f.capture), proof = result.getJSONObject("input_lifecycle");
            assertEquals(result.toString(), "COMPLETED", result.getString("state"));
            assertTrue(result.getLong("bytes_read") > 0); assertTrue(result.getBoolean("release_verified"));
            assertTrue(proof.getBoolean("ownership_observed")); assertEquals(18, proof.getInt("input_io_handle"));
            assertEquals("closed", proof.getString("state")); assertTrue(proof.getBoolean("release_confirmed"));
            assertTrue(proof.getBoolean("completion_allowed")); assertEquals("STOP_REQUESTED", proof.getString("stop_reason"));
            assertTrue(proof.getBoolean("reader_finished")); assertFalse(f.capture.active());
            assertEquals(1, f.record.starts); assertEquals(1, f.record.stops); assertEquals(1, f.record.releases);
            assertTrue(f.reads.get() >= 1); for (short value : f.record.buffer) assertEquals(0, value);
            assertFalse(result.getBoolean("managed_media")); assertFalse(result.getBoolean("network_started"));
            assertFalse(result.toString().contains("yes 555"));
        }
    }
    @Test public void sourcePermissionFailureOrExternalBusyStopsBeforeReading() throws Exception {
        for (boolean failure : new boolean[]{false, true}) try (Fixture f = new Fixture(new Record(), 2500)) {
            f.fail = failure; f.bad = !failure; JSONObject result = LocalAudioCaptureTest.run(f.capture);
            assertEquals("FAILED", result.getString("state")); assertEquals(0, result.getLong("bytes_read"));
            assertEquals(1, f.record.releases); assertFalse(result.getJSONObject("input_lifecycle").getBoolean("ownership_observed"));
            assertFalse(result.toString().contains("私有权限错误详情"));
        }
    }
    @Test public void nativeStartFailureStillCleansPartialAllocation() throws Exception {
        Record record = new Record(); record.startFails = true;
        try (Fixture f = new Fixture(record, 2500)) {
            JSONObject result = LocalAudioCaptureTest.run(f.capture); assertEquals("FAILED", result.getString("state"));
            assertTrue(result.getBoolean("release_verified")); assertEquals(1, record.releases);
            assertTrue(result.getJSONObject("input_lifecycle").getBoolean("native_start_failed"));
        }
    }
    @Test public void laterBadEvidenceCannotBecomeSuccessJustBecauseReleaseSucceeds() throws Exception {
        try (Fixture f = new Fixture(new Record(), 2500)) {
            f.driver.start(f.cancel); assertTrue(f.driver.lifecycleSnapshot().getBoolean("ownership_observed"));
            f.bad = true;
            // 使用实际缓存读取路径，避免等待周期而改变测试采音时长。
            java.lang.reflect.Field field = AudioCaptureDriver.class.getDeclaredField("session"); field.setAccessible(true);
            AudioCaptureLifecycleSession session = (AudioCaptureLifecycleSession) field.get(f.driver);
            f.cache.refresh(session);
            try { f.driver.read(new short[320], 320); fail(); } catch (IOException expected) { }
            f.driver.stop(); f.driver.release(); JSONObject proof = f.driver.lifecycleSnapshot();
            assertTrue(proof.getBoolean("ownership_observed")); assertTrue(proof.getBoolean("release_confirmed"));
            assertEquals("EXTERNAL_BUSY", proof.getString("stop_reason")); assertFalse(proof.getBoolean("completion_allowed"));
        }
    }
    @Test public void recorderSessionChangeRevokesBeforeFurtherReading() throws Exception {
        Record record = new Record() { public int read(short[] samples, int count) throws Exception {
            int n = super.read(samples, count); actualSession = 43; return n;
        }};
        try (Fixture f = new Fixture(record, 2500)) {
            JSONObject result = LocalAudioCaptureTest.run(f.capture); assertEquals("FAILED", result.getString("state"));
            assertEquals("MEDIA_INPUT_RECORDER_IDENTITY_CHANGED", result.getString("error_code"));
            assertEquals(0, result.getLong("bytes_read")); assertEquals(1, record.releases);
        }
    }
    @Test public void failedReleaseKeepsActualControllerBusyAndRequestIdentityReachesFactory() throws Exception {
        Record record = new Record(); record.releaseFails = true;
        try (Fixture f = new Fixture(record, 500)) {
            AtomicInteger creations = new AtomicInteger();
            AppMediaController controller = new AppMediaController(new AppMediaController.Backend() {
                public URI origin() { return URI.create("https://control.example"); }
                public JSONObject prepare(String hash, Cancellation cancel) { return new JSONObject(); }
                public AppMediaController.Session create(String hash, RtcOffer offer, Cancellation cancel) { throw new AssertionError(); }
                public LocalAudioCapture localCapture(String hash, String id, int ms, Cancellation cancel, String request) {
                    assertEquals("request-A", request); creations.incrementAndGet(); return f.capture;
                }
            }, CLOCK);
            JSONObject command = new JSONObject().put("operation", "local_audio_capture").put("apk_sha256", HASH)
                    .put("diagnostic_id", "diag-1").put("duration_ms", 500);
            JSONObject result = controller.execute("request-A", command, this, f.cancel);
            assertEquals("RELEASE_UNCONFIRMED", result.getString("state")); assertTrue(controller.hasActive());
            try { controller.execute("request-B", command, this, new Cancellation()); fail(); }
            catch (IOException expected) { assertEquals("MEDIA_APP_BUSY", expected.getMessage()); }
            assertEquals(1, creations.get()); assertFalse(result.getJSONObject("input_lifecycle").getBoolean("release_confirmed"));
        }
    }
    @Test public void cancellationWhileReadingStopsNativeBeforeReleaseAndEndsObserver() throws Exception {
        Record record = new Record(); record.reading = new CountDownLatch(1); record.unblock = new CountDownLatch(1);
        ExecutorService control = Executors.newSingleThreadExecutor();
        try (Fixture f = new Fixture(record, 2500)) {
            Future<JSONObject> result = control.submit(() -> LocalAudioCaptureTest.run(f.capture));
            assertTrue(record.reading.await(2, TimeUnit.SECONDS)); f.capture.cancel();
            JSONObject value = result.get(2, TimeUnit.SECONDS); assertEquals(value.toString(), "CANCELLED", value.getString("state"));
            assertTrue(value.getJSONObject("input_lifecycle").getBoolean("release_confirmed"));
            assertEquals(1, record.stops); assertEquals(1, record.releases); assertNull(f.cache.cached());
        } finally { control.shutdownNow(); }
    }
    @Test public void cacheReadFailureAfterOwnershipRevokesAndCannotRenewOldSample() throws Exception {
        try (Fixture f = new Fixture(new Record(), 2500)) {
            f.driver.start(f.cancel); f.fail = true;
            java.lang.reflect.Field field = AudioCaptureDriver.class.getDeclaredField("session"); field.setAccessible(true);
            f.cache.refresh((AudioCaptureLifecycleSession) field.get(f.driver));
            assertNull(f.cache.cached());
            try { f.driver.read(new short[320], 320); fail(); } catch (IOException expected) { }
            f.driver.stop(); f.driver.release(); assertFalse(f.driver.completionAllowed());
        }
    }
    @Test public void alreadyInterruptedCleanupStillWaitsForProofAndPreservesInterrupt() throws Exception {
        try (Fixture f = new Fixture(new Record(), 2500)) {
            f.driver.start(f.cancel);
            try {
                Thread.currentThread().interrupt(); f.driver.stop();
                assertTrue(Thread.currentThread().isInterrupted()); f.driver.release();
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(f.driver.lifecycleSnapshot().getBoolean("release_confirmed"));
            } finally { Thread.interrupted(); }
        }
    }
    @Test public void unfinishedObserverCannotClaimReleaseConfirmed() throws Exception {
        Record record = new Record(); AudioCaptureLifecycle.Clock clock = () -> CLOCK.elapsed();
        AudioCaptureLifecycleSession.Source observer = new AudioCaptureLifecycleSession.Source() {
            public AudioCaptureLifecycle.Evidence cached() {
                long from = clock.elapsed() - 40;
                return new AudioCaptureLifecycle.Evidence(ID, "request-A", "diag-1",
                        new AudioInputOwnership.Dump(AudioInputOwnershipTest.FLINGER, true, from, from + 10),
                        new AudioInputOwnership.Dump(AudioInputOwnershipTest.POLICY, true, from + 20, from + 30),
                        AudioCaptureLifecycle.External.IDLE, from, from + 5);
            }
            public void close() throws IOException { throw new IOException("MEDIA_INPUT_OBSERVER_RELEASE_UNCONFIRMED"); }
        };
        AudioCaptureDriver driver = new AudioCaptureDriver(record, ID, "request-A", "diag-1", clock, observer, new Cancellation());
        driver.stop();
        try { driver.release(); fail(); } catch (IOException expected) { }
        assertTrue(driver.releasePending()); assertFalse(driver.lifecycleSnapshot().getBoolean("release_confirmed"));
        assertEquals(1, record.releases);
    }
    @Test public void actualDiagnosticRejectsCompletionWhenLifecycleWasRevokedAfterFinalRead() throws Exception {
        Record record = new Record() { public boolean completionAllowed() { return false; } };
        JSONObject result = LocalAudioCaptureTest.run(LocalAudioCaptureTest.capture(record, 50, new Cancellation()));
        assertEquals("FAILED", result.getString("state")); assertEquals("MEDIA_INPUT_LIFECYCLE_REVOKED", result.getString("error_code"));
        assertTrue(result.getLong("bytes_read") > 0); assertTrue(result.getBoolean("release_verified"));
    }
    @Test public void realControllerHeartbeatForwardsSessionTickWithoutQuery() throws Exception {
        AtomicInteger ticks = new AtomicInteger(); AudioCaptureLifecycleTest.Time time = new AudioCaptureLifecycleTest.Time();
        AppMediaController controller = new AppMediaController(new AppMediaController.Backend() {
            public URI origin() { return URI.create("https://control.example"); }
            public JSONObject prepare(String hash, Cancellation cancel) { return new JSONObject(); }
            public AppMediaController.Session create(String hash, RtcOffer offer, Cancellation cancel) {
                return new AppMediaController.Session() {
                    public void start() { } public void stop() { } public void tick() { ticks.incrementAndGet(); }
                    public JSONObject snapshot() throws Exception { return new JSONObject().put("state", "verifying"); }
                };
            }
        }, time);
        controller.execute("request-A", new JSONObject().put("operation", "start").put("apk_sha256", HASH).put("offer", RtcOfferTest.offer()), this, new Cancellation());
        controller.tick(); assertEquals(1, ticks.get());
    }
    @Test public void partialFactoryFailureCannotLoseUnconfirmedResourceBeforeRecorderHandoff() throws Exception {
        LocalAudioCapture.Factory factory = new LocalAudioCapture.Factory() {
            public LocalAudioCapture.Recorder open(Cancellation c) throws IOException {
                throw new IOException("MEDIA_INPUT_RELEASE_UNCONFIRMED");
            }
            public boolean releasePending() { return true; }
        };
        LocalAudioCapture capture = new LocalAudioCapture("diag", 2500, 555, 10001, factory, new Cancellation(), CLOCK);
        AppMediaController controller = new AppMediaController(new AppMediaController.Backend() {
            public URI origin() { return URI.create("https://control.example"); }
            public JSONObject prepare(String hash, Cancellation c) { return new JSONObject(); }
            public AppMediaController.Session create(String hash, RtcOffer offer, Cancellation c) { throw new AssertionError(); }
            public LocalAudioCapture localCapture(String hash, String id, int duration, Cancellation c) { return capture; }
        }, CLOCK);
        JSONObject command = new JSONObject().put("operation", "local_audio_capture").put("apk_sha256", HASH)
                .put("diagnostic_id", "diag").put("duration_ms", 2500);
        JSONObject result = controller.execute("request-A", command, this, new Cancellation());
        assertEquals("RELEASE_UNCONFIRMED", result.getString("state")); assertFalse(result.getBoolean("audio_record_created"));
        assertTrue(result.getBoolean("factory_release_pending")); assertTrue(controller.hasActive());
        try { controller.execute("request-B", command, this, new Cancellation()); fail(); }
        catch (IOException expected) { assertEquals("MEDIA_APP_BUSY", expected.getMessage()); }
    }
}
