package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 在归属拒绝传播到关闭前保存静音现场；原件仅进入APP私有目录，公开快照只有计数和错误。 */
final class AppPttEvidence implements PttSessionController.Operations {
    interface Identity { JSONObject read() throws Exception; }
    interface Changed { void changed(JSONObject storage); }
    private final PttSessionController.Operations delegate;
    private final Identity identity;
    private final AudioCaptureObservation.Reader reader;
    private final AppMediaReadTrace trace;
    private final AppMediaRtcDiagnostics diagnostics;
    private final MediaCapture.Clock clock;
    private final Changed changed;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(job -> new Thread(job, "d31-ptt-evidence"));
    private final Cancellation cancellation = new Cancellation();
    private volatile String error = "";
    private volatile int completed;
    private boolean first, rejected;

    AppPttEvidence(PttSessionController.Operations delegate, Identity identity, AudioCaptureObservation.Reader reader,
            AppMediaReadTrace trace, AppMediaRtcDiagnostics diagnostics, MediaCapture.Clock clock, Changed changed) {
        this.delegate = delegate; this.identity = identity; this.reader = reader;
        this.trace = trace; this.diagnostics = diagnostics; this.clock = clock; this.changed = changed;
    }
    public void open() throws Exception { delegate.open(); }
    public void send(JSONObject value) throws Exception { delegate.send(value); }
    public JSONObject subscribe(JSONObject value) throws Exception { return delegate.subscribe(value); }
    public void answerAcknowledged() throws Exception { delegate.answerAcknowledged(); }
    public void prepareMuted() throws Exception { delegate.prepareMuted(); }
    public boolean playbackFrames() { return delegate.playbackFrames(); }
    public void unmute() throws Exception { delegate.unmute(); }

    public int outputProof() throws Exception {
        JSONObject actual = new JSONObject();
        try {
            actual = identity.read();
            if (actual.optInt("audio_session", 0) > 0 && !first) {
                first = true; capture(actual, "MUTED_IDENTITY_OBSERVED", "");
            }
        } catch (Exception evidenceFailure) {
            if (error.isEmpty()) error = "MEDIA_PTT_EVIDENCE_READ_INCOMPLETE";
            publish();
        }
        // 取证是旁路，存储与读取错误不能替代实际输出守卫的裁定。
        try { return delegate.outputProof(); }
        catch (Exception failure) {
            // 此时仅取消资格尚未发生，实际AudioTrack仍由串行工作线程持有。
            if (!rejected) {
                rejected = true;
                try { capture(actual, "OUTPUT_REJECTED_BEFORE_CLOSE", code(failure, "MEDIA_OUTPUT_UNKNOWN")); }
                catch (Exception evidenceFailure) { /* 独立保留证据错误，不覆盖业务拒绝原因。 */ }
            }
            throw failure;
        }
    }
    private void capture(JSONObject actual, String phase, String rejection) throws Exception {
        final JSONObject frozen = new JSONObject(actual.toString());
        Future<?> task = writer.submit(() -> save(frozen, phase, rejection));
        try { task.get(1750, TimeUnit.MILLISECONDS); }
        catch (Exception failure) {
            task.cancel(true); error = "MEDIA_PTT_EVIDENCE_WAIT_UNCONFIRMED"; publish();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException(error);
        }
        if (!error.isEmpty()) throw new IOException(error);
    }
    private void save(JSONObject actual, String phase, String rejection) {
        trace.reset(); AudioCaptureObservation.Sample sample = null; String readError = "";
        try { sample = reader.read(cancellation); }
        catch (Exception | LinkageError failure) { readError = code(failure, "MEDIA_OUTPUT_READ_FAILED"); }
        try {
            int before = diagnostics.snapshot().getInt("saved_samples");
            diagnostics.save(sample, readError, actual.optInt("pid", -1), actual.optInt("audio_session", 0), clock.elapsed(),
                    new JSONObject().put("scope", "PTT_OUTPUT_MUTED_OBSERVATION").put("phase", phase)
                            .put("actual_playback_identity", actual).put("output_rejection", rejection)
                            .put("identity_observed", actual.optInt("audio_session", 0) > 0), trace.snapshot());
            JSONObject storage = diagnostics.snapshot();
            if (!storage.optString("storage_error").isEmpty() || storage.getInt("saved_samples") != before + 1)
                error = "MEDIA_PTT_EVIDENCE_WRITE_UNCONFIRMED";
            else if (sample == null || !sample.flinger.complete || !sample.policy.complete || !readError.isEmpty())
                error = "MEDIA_PTT_EVIDENCE_READ_INCOMPLETE";
            else completed++;
        } catch (Exception | LinkageError failure) { error = "MEDIA_PTT_EVIDENCE_WRITE_UNCONFIRMED"; }
        publish();
    }
    JSONObject snapshot() throws Exception {
        return diagnostics.snapshot().put("complete_pairs", completed).put("evidence_error", error);
    }
    private void publish() {
        if (changed != null) try { changed.changed(snapshot()); } catch (Exception ignored) {}
    }
    public void cancel() {
        cancellation.cancel();
        delegate.cancel();
    }
    public void close() throws Exception {
        cancellation.cancel(); writer.shutdown();
        try {
            if (!writer.awaitTermination(1750, TimeUnit.MILLISECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            writer.shutdownNow(); Thread.currentThread().interrupt();
        }
        // 证据错误仍在快照中；只有资源未释放才阻止关闭，不用日志失败占住媒体锁。
        try { delegate.close(); }
        finally { publish(); }
        if (!writer.isTerminated()) throw new IOException("MEDIA_PTT_EVIDENCE_RELEASE_UNCONFIRMED");
    }
    private static String code(Throwable failure, String fallback) {
        String value = failure.getMessage();
        return value != null && value.matches("MEDIA_[A-Z0-9_]{1,80}") ? value : fallback;
    }
}
