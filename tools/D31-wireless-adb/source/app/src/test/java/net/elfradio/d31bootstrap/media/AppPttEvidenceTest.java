package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppPttEvidenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    static final String HASH = String.join("", java.util.Collections.nCopies(64, "a"));
    final AppPttSessionTest.Time time = new AppPttSessionTest.Time();
    final AppMediaReadTrace trace = new AppMediaReadTrace();
    final byte[] flinger = new byte[] { 65, 13, 10, (byte) 255, 0 };
    final byte[] policy = "原始策略\r\n".getBytes(StandardCharsets.UTF_8);
    static class Ops implements PttSessionController.Operations {
        int proofs, closes, unmutes;
        public void open() {} public void send(JSONObject value) {}
        public JSONObject subscribe(JSONObject value) { return value; }
        public void answerAcknowledged() {} public void prepareMuted() {}
        public boolean playbackFrames() { return true; }
        public int outputProof() throws Exception { proofs++; throw new IOException("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED"); }
        public void unmute() { unmutes++; } public void cancel() {} public void close() { closes++; }
    }
    JSONObject identity() throws Exception {
        return new JSONObject().put("pid", 123).put("audio_session", 42).put("sample_rate", 48000)
                .put("channels", 1).put("audio_format", 2).put("stream_type", 3);
    }
    AudioCaptureObservation.Sample read(boolean fail) throws Exception {
        long at = time.elapsed(); trace.stage("FLINGER", at);
        trace.dump("media.audio_flinger", flinger, true, at, at);
        if (fail) throw new IOException("MEDIA_INPUT_DUMP_TIMEOUT");
        trace.stage("POLICY", at); trace.dump("media.audio_policy", policy, true, at, at);
        JSONObject external = new JSONObject().put("test_external", true); trace.external(external, at, at);
        return new AudioCaptureObservation.Sample(new AudioInputOwnership.Dump(new String(flinger, StandardCharsets.UTF_8), true, at, at),
                new AudioInputOwnership.Dump(new String(policy, StandardCharsets.UTF_8), true, at, at), external, at, at);
    }
    JSONObject saved(File root, int number) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(root, "ptt-test/sample-" + number + ".json").toPath()), StandardCharsets.UTF_8));
    }
    AppPttEvidence evidence(Ops ops, File root, AudioCaptureObservation.Reader reader) {
        return new AppPttEvidence(ops, this::identity, reader, trace,
                new AppMediaRtcDiagnostics(root, "ptt-test", HASH), time, null);
    }
    @Test public void rejectionPreservesFullIdentityAndRawPairBeforePeerClose() throws Exception {
        File root = temporary.newFolder(); Ops ops = new Ops(); AppPttEvidence value = evidence(ops, root, cancel -> read(false));
        try {
            try { value.outputProof(); fail(); } catch (IOException expected) { assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", expected.getMessage()); }
            assertEquals(0, ops.closes); assertEquals(0, ops.unmutes); assertEquals(2, value.snapshot().getInt("complete_pairs"));
            for (int number = 1; number <= 2; number++) {
                JSONObject sample = saved(root, number);
                assertEquals(identity().toString(), sample.getJSONObject("decision").getJSONObject("actual_playback_identity").toString());
                assertEquals("410d0aff00", sample.getJSONObject("read_trace").getJSONObject("flinger").getString("raw_hex"));
                assertEquals(flinger.length, sample.getJSONObject("read_trace").getJSONObject("flinger").getInt("bytes"));
                assertEquals(new String(policy, StandardCharsets.UTF_8), sample.getJSONObject("policy").getString("text"));
                assertTrue(sample.getBoolean("sample_returned")); assertFalse(sample.getBoolean("contains_audio"));
            }
            assertEquals("OUTPUT_REJECTED_BEFORE_CLOSE", saved(root, 2).getJSONObject("decision").getString("phase"));
            assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", saved(root, 2).getJSONObject("decision").getString("output_rejection"));
            assertFalse(value.snapshot().toString().contains("audio_session"));
        } finally { value.close(); }
        assertEquals(1, ops.closes);
    }
    @Test public void partialReadPersistsAvailableBytesAndReportsEvidenceGap() throws Exception {
        File root = temporary.newFolder(); Ops ops = new Ops(); AppPttEvidence value = evidence(ops, root, cancel -> read(true));
        try {
            try { value.outputProof(); fail(); } catch (IOException expected) { assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", expected.getMessage()); }
            JSONObject sample = saved(root, 1); assertFalse(sample.getBoolean("sample_returned"));
            assertEquals("410d0aff00", sample.getJSONObject("read_trace").getJSONObject("flinger").getString("raw_hex"));
            assertEquals("MEDIA_INPUT_DUMP_TIMEOUT", sample.getString("read_error"));
            assertEquals(identity().toString(), sample.getJSONObject("decision").getJSONObject("actual_playback_identity").toString());
            assertEquals(1, ops.proofs);
        } finally {
            value.close();
        }
        assertEquals("MEDIA_PTT_EVIDENCE_READ_INCOMPLETE", value.snapshot().getString("evidence_error"));
        assertEquals(1, ops.closes);
    }
    @Test public void storageFullStillRunsGuardAndPreservesItsRejection() throws Exception {
        File root = temporary.newFolder();
        for (int n = 0; n < AppMediaRtcDiagnostics.MAX_SESSIONS; n++) assertTrue(new File(root, "prior-" + n).mkdir());
        Ops ops = new Ops(); AppPttEvidence value = evidence(ops, root, cancel -> read(false));
        try {
            try { value.outputProof(); fail(); } catch (IOException expected) { assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", expected.getMessage()); }
            assertEquals("MEDIA_DIAGNOSTIC_STORAGE_LIMIT", value.snapshot().getString("storage_error"));
            assertEquals(0, value.snapshot().getInt("saved_samples")); assertEquals(4, root.list().length); assertEquals(1, ops.proofs);
        } finally { value.close(); }
        assertEquals("MEDIA_PTT_EVIDENCE_WRITE_UNCONFIRMED", value.snapshot().getString("evidence_error"));
        assertEquals(1, ops.closes); assertEquals(0, ops.unmutes);
    }
    @Test public void delayedReadCannotPassProofBeforeRawFilesExist() throws Exception {
        File root = temporary.newFolder(); Ops ops = new Ops();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AppPttEvidence value = evidence(ops, root, cancel -> { entered.countDown(); release.await(); return read(false); });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> action = worker.submit(() -> { try { value.outputProof(); fail(); } catch (Exception expected) {} });
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(0, ops.proofs); assertFalse(action.isDone());
            release.countDown(); action.get(2, TimeUnit.SECONDS);
            assertEquals(1, ops.proofs); assertEquals(2, value.snapshot().getInt("saved_samples"));
        } finally { release.countDown(); value.close(); worker.shutdownNow(); }
    }
    @Test public void readTimeoutSavesPartialBytesAndKeepsGapEvenAfterLateCompletion() throws Exception {
        File root = temporary.newFolder(); Ops ops = new Ops();
        AppPttEvidence value = evidence(ops, root, cancel -> {
            trace.dump("media.audio_flinger", flinger, false, time.elapsed(), time.elapsed());
            new CountDownLatch(1).await(); return null;
        });
        try {
            try { value.outputProof(); fail(); } catch (IOException expected) { assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", expected.getMessage()); }
            assertEquals(1, ops.proofs);
        } finally { value.close(); }
        assertFalse(value.snapshot().getString("evidence_error").isEmpty());
        assertEquals("410d0aff00", saved(root, 1).getJSONObject("read_trace").getJSONObject("flinger").getString("raw_hex"));
        assertFalse(saved(root, 1).getBoolean("sample_returned")); assertEquals(1, ops.closes);
    }

    final class EvidenceSession implements AutoCloseable {
        final File files = temporary.newFolder();
        final AppPttSessionTest.Wire wire = new AppPttSessionTest.Wire();
        final AppPttSessionTest.Ops ops = new AppPttSessionTest.Ops();
        final AppPttSession session;
        final CountDownLatch releaseRead = new CountDownLatch(1);
        volatile AppPttEvidence evidence;
        EvidenceSession(File root, boolean blocked) throws Exception {
            ops.proof = -1;
            session = new AppPttSession(files, AppPttSessionTest.offer(time, "ptt"), time, wire, (sender, signals) -> {
                ops.sender = sender; ops.signals = signals;
                evidence = new AppPttEvidence(ops, AppPttEvidenceTest.this::identity, cancel -> {
                    // 模拟Binder或文件系统不响应中断；只有测试最后显式释放才退出。
                    while (blocked && releaseRead.getCount() > 0) {
                        try { releaseRead.await(); } catch (InterruptedException ignored) {}
                    }
                    return read(false);
                }, trace, new AppMediaRtcDiagnostics(root, "ptt-test", HASH), time, signals::evidence);
                return evidence;
            });
        }
        void start() throws Exception {
            session.start(); assertTrue(wire.entered.await(1, TimeUnit.SECONDS));
            wire.emit(AppPttSessionTest.tracks()); wire.emit(AppPttSessionTest.hello());
        }
        public void close() throws Exception {
            releaseRead.countDown(); session.close(); assertTrue(session.awaitClosed(2500));
            if (evidence != null) {
                java.lang.reflect.Field field = AppPttEvidence.class.getDeclaredField("writer"); field.setAccessible(true);
                assertTrue(((ExecutorService) field.get(evidence)).awaitTermination(2000, TimeUnit.MILLISECONDS));
            }
            // 只回收本测试故意保留的文件锁；生产没有强制解锁路径。
            java.lang.reflect.Field field = AppPttSession.class.getDeclaredField("lease"); field.setAccessible(true);
            MediaFiles.Lease retained = (MediaFiles.Lease) field.get(session);
            if (retained != null) retained.close();
        }
    }
    @Test public void storageFailureWithReleasedResourcesClosesSessionAndUnlocksMedia() throws Exception {
        File root = temporary.newFolder();
        for (int n = 0; n < AppMediaRtcDiagnostics.MAX_SESSIONS; n++) assertTrue(new File(root, "prior-" + n).mkdir());
        try (EvidenceSession run = new EvidenceSession(root, false)) {
            run.start(); assertTrue(run.session.awaitClosed(2500));
            JSONObject value = run.session.snapshot();
            assertEquals("closed", value.getString("state")); assertTrue(value.getBoolean("cleanup_complete"));
            assertEquals("", value.getString("cleanup_reason"));
            assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED", value.getString("reason"));
            assertEquals("MEDIA_PTT_EVIDENCE_WRITE_UNCONFIRMED", value.getJSONObject("ptt_evidence").getString("evidence_error"));
            assertEquals("MEDIA_DIAGNOSTIC_STORAGE_LIMIT", value.getJSONObject("ptt_evidence").getString("storage_error"));
            assertEquals(1, run.ops.closes.get()); assertEquals(0, run.ops.unmutes.get());
            assertTrue(run.ops.proofs.get() > 0);
            assertEquals(1, run.wire.closes.get()); AppPttSessionTest.unlocked(run.files);
        }
    }
    @Test public void writerStillRunningKeepsSessionUnconfirmedAndMediaLocked() throws Exception {
        try (EvidenceSession run = new EvidenceSession(temporary.newFolder(), true)) {
            run.start(); assertTrue(run.session.awaitClosed(7000));
            JSONObject value = run.session.snapshot();
            assertEquals("release_unconfirmed", value.getString("state")); assertFalse(value.getBoolean("cleanup_complete"));
            assertEquals("MEDIA_PTT_EVIDENCE_RELEASE_UNCONFIRMED", value.getString("cleanup_reason"));
            assertEquals("MEDIA_PTT_EVIDENCE_WAIT_UNCONFIRMED", value.getJSONObject("ptt_evidence").getString("evidence_error"));
            assertEquals(1, run.ops.closes.get()); assertEquals(1, run.wire.closes.get()); assertEquals(0, run.ops.unmutes.get());
            assertEquals(1, run.releaseRead.getCount()); AppPttSessionTest.locked(run.files);
        }
    }
    @Test public void fifthSessionCanStreamWhenDelegateProvesOwnershipWithoutOverwritingEvidence() throws Exception {
        File root = temporary.newFolder();
        byte[] original = "不可覆盖的既有诊断原件".getBytes(StandardCharsets.UTF_8);
        for (int n = 0; n < AppMediaRtcDiagnostics.MAX_SESSIONS; n++) {
            File directory = new File(root, "prior-" + n); assertTrue(directory.mkdir());
            Files.write(new File(directory, "sample-1.json").toPath(), original);
        }
        try (EvidenceSession run = new EvidenceSession(root, false)) {
            // 仅离线替身模拟底层严格守卫已通过；生产仍调用AndroidPttOperations的真实守卫。
            run.ops.proof = 1; run.start();
            AppPttSessionTest.until(() -> "streaming".equals(run.session.snapshot().getString("state")));
            JSONObject value = run.session.snapshot();
            assertTrue(value.getBoolean("ready")); assertTrue(value.getBoolean("output_verified"));
            assertTrue(run.ops.proofs.get() > 0); assertEquals(1, run.ops.unmutes.get());
            assertEquals("MEDIA_PTT_EVIDENCE_WRITE_UNCONFIRMED", value.getJSONObject("ptt_evidence").getString("evidence_error"));
            assertEquals("MEDIA_DIAGNOSTIC_STORAGE_LIMIT", value.getJSONObject("ptt_evidence").getString("storage_error"));
            assertEquals(4, root.list().length);
            for (int n = 0; n < AppMediaRtcDiagnostics.MAX_SESSIONS; n++)
                assertArrayEquals(original, Files.readAllBytes(new File(root, "prior-" + n + "/sample-1.json").toPath()));
            run.session.stop(); assertTrue(run.session.awaitClosed(2500));
            assertTrue(run.session.snapshot().getBoolean("cleanup_complete")); AppPttSessionTest.unlocked(run.files);
        }
    }
}
