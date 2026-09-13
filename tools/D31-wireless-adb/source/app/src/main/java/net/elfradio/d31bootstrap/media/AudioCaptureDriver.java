package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** 现有有界诊断使用的真实Recorder包装；生命周期驱动原生start/stop/release和缓存占用门。 */
final class AudioCaptureDriver implements LocalAudioCapture.Recorder {
    private final LocalAudioCapture.Recorder record;
    private final AudioCaptureLifecycle.Identity identity;
    private final AudioCaptureLifecycle.Clock clock;
    private final AudioCaptureLifecycle core;
    private final AudioCaptureLifecycleSession session;
    private final CountDownLatch stopped = new CountDownLatch(1), readerFinished = new CountDownLatch(1);
    private volatile boolean attempted, stopReturned, releaseReturned, failedStart;
    AudioCaptureDriver(LocalAudioCapture.Recorder record, AudioCaptureLifecycle.Identity identity, String requestId,
            String diagnosticId, AudioCaptureLifecycle.Clock clock, AudioCaptureLifecycleSession.Source observation,
            Cancellation requestCancellation) {
        this.record = record; this.identity = identity; this.clock = clock;
        core = new AudioCaptureLifecycle(identity, requestId, diagnosticId, clock);
        session = new AudioCaptureLifecycleSession(core, observation, new AudioCaptureLifecycleSession.Driver() {
            public void start(Cancellation cancellation) throws Exception {
                cancellation.check(); attempted = true;
                try {
                    requireIdentity();
                    AudioCaptureDriver.this.record.start(cancellation);
                    if (AudioCaptureDriver.this.record.recordingState() != 3) throw new IOException("MEDIA_LOCAL_AUDIO_START_FAILED");
                } catch (Exception | LinkageError failure) { failedStart = true; throw failure; }
            }
            public AudioCaptureLifecycle.Release stopAndRelease() throws Exception {
                Exception stopFailure = null;
                try {
                    if (attempted) AudioCaptureDriver.this.record.stop();
                    stopReturned = true;
                } catch (Exception | LinkageError failure) { stopFailure = new IOException("MEDIA_INPUT_STOP_FAILED", failure); }
                finally { stopped.countDown(); }
                // 非阻塞read调用者先离开读取循环；绝不与仍在执行的read并发release。
                readerFinished.await();
                AudioCaptureDriver.this.record.release(); releaseReturned = true;
                if (stopFailure != null) throw stopFailure;
                return new AudioCaptureLifecycle.Release(identity, true, true, true,
                        AudioCaptureDriver.this.record.state(), AudioCaptureDriver.this.record.recordingState(), clock.elapsed());
            }
        }, requestCancellation);
    }
    public int state() { return record.state(); }
    public int recordingState() { return record.recordingState(); }
    public int sessionId() { return record.sessionId(); }
    public void start(Cancellation cancellation) throws Exception {
        session.start();
        // 只有诊断工作线程等待；VERIFYING不读取或计算采音通过。
        for (;;) {
            cancellation.check(); session.tick(); AudioCaptureLifecycle.Snapshot value = core.snapshot();
            if (value.continuationEligible) return;
            if (value.stopRequired || value.releaseConfirmed) throw new IOException("MEDIA_INPUT_OWNERSHIP_NOT_CONFIRMED");
            Thread.sleep(5);
        }
    }
    public int read(short[] samples, int count) throws Exception {
        try {
            requireIdentity(); session.requireContinuation(); int read = record.read(samples, count);
            requireIdentity(); session.requireContinuation(); return read;
        } catch (Exception | LinkageError failure) { session.stop(); throw failure; }
    }
    private void requireIdentity() throws IOException {
        if (record.sessionId() != identity.audioSession || record.state() != 1)
            throw new IOException("MEDIA_INPUT_RECORDER_IDENTITY_CHANGED");
    }
    public void stop() throws Exception {
        session.stop();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750);
        try {
            while (stopped.getCount() != 0 && System.nanoTime() < deadline) {
                try { stopped.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException cancellation) { interrupted = true; }
            }
            if (stopped.getCount() != 0 || !stopReturned) throw new IOException("MEDIA_INPUT_STOP_UNCONFIRMED");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public void release() throws Exception {
        readerFinished.countDown(); session.stop();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750);
        boolean interrupted = false;
        try {
            while (!core.snapshot().releaseConfirmed && System.nanoTime() < deadline) {
                try { Thread.sleep(5); } catch (InterruptedException cancellation) { interrupted = true; }
            }
            if (!core.snapshot().releaseConfirmed) throw new IOException("MEDIA_INPUT_RELEASE_UNCONFIRMED");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public void tick() { session.tick(); }
    public boolean releasePending() { return !core.snapshot().releaseConfirmed; }
    public boolean completionAllowed() {
        AudioCaptureLifecycle.Snapshot value = core.snapshot();
        return value.releaseConfirmed && value.ioBound && "STOP_REQUESTED".equals(value.stopReason);
    }
    public JSONObject lifecycleSnapshot() throws Exception {
        AudioCaptureLifecycle.Snapshot value = core.snapshot();
        return session.snapshot().put("input_io_handle", value.inputIoHandle)
                .put("ownership_observed", value.ioBound).put("native_start_attempted", attempted)
                .put("completion_allowed", completionAllowed())
                .put("native_start_failed", failedStart).put("native_stop_returned", stopReturned)
                .put("native_release_returned", releaseReturned).put("reader_finished", readerFinished.getCount() == 0)
                .put("scope", "BOUNDED_LOCAL_CAPTURE_PAIRED_INPUT_AND_EXTERNAL_STATE_NOT_ATOMIC");
    }
}
