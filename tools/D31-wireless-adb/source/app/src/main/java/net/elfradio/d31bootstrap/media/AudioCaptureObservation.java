package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 单会话可信观察缓存；一次有界读取，固定延迟刷新，读失败立即撤销而非续期旧证据。 */
final class AudioCaptureObservation implements AudioCaptureLifecycleSession.Source {
    static final long REFRESH_MS = 2000;
    interface Reader { Sample read(Cancellation cancellation) throws Exception; }
    static final class Sample {
        final AudioInputOwnership.Dump flinger, policy;
        final String externalJson;
        final long began, finished;
        Sample(AudioInputOwnership.Dump flinger, AudioInputOwnership.Dump policy, JSONObject external,
                long began, long finished) {
            this.flinger = flinger; this.policy = policy;
            externalJson = external == null ? null : external.toString();
            this.began = began; this.finished = finished;
        }
    }
    private final AudioCaptureLifecycle.Identity identity;
    private final String request, session;
    private final AudioCaptureLifecycle.Clock clock;
    private final Reader reader;
    private final Cancellation cancellation = new Cancellation();
    private final ScheduledExecutorService worker;
    private volatile AudioCaptureLifecycle.Evidence latest;
    private volatile boolean closed;
    private boolean started;
    AudioCaptureObservation(AudioCaptureLifecycle.Identity identity, String request, String session,
            AudioCaptureLifecycle.Clock clock, Reader reader, Sample initial) {
        this.identity = identity; this.request = request; this.session = session; this.clock = clock; this.reader = reader;
        latest = evidence(initial);
        worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable action) {
                Thread thread = new Thread(action, "d31-audio-input-observer"); thread.setDaemon(true); return thread;
            }
        });
    }
    static void requireEmpty(Sample sample, int pid, long now) throws IOException {
        // 尚未创建AudioRecord，只判无输入，不用占位session作任何自身归属授权。
        if (sample == null || AudioInputOwnership.evaluate(sample.flinger, sample.policy, pid, 1, now).state
                != AudioInputOwnership.State.NO_ACTIVE_INPUT || external(sample, pid, 1, now) != AudioCaptureLifecycle.External.IDLE)
            throw new IOException("MEDIA_INPUT_PREFLIGHT_UNKNOWN_OR_BUSY");
        long first = Math.min(sample.began, Math.min(sample.flinger.startedElapsedMs, sample.policy.startedElapsedMs));
        long last = Math.max(sample.finished, Math.max(sample.flinger.finishedElapsedMs, sample.policy.finishedElapsedMs));
        if (first < 0 || last - first > AudioInputOwnership.MAX_SAMPLE_MS)
            throw new IOException("MEDIA_INPUT_PREFLIGHT_WINDOW");
    }
    public AudioCaptureLifecycle.Evidence cached() { return closed ? null : latest; }
    public synchronized void start(final AudioCaptureLifecycleSession target) throws IOException {
        if (closed || started) throw new IOException("MEDIA_INPUT_OBSERVER_NOT_REUSABLE");
        started = true;
        // 正常start返回后再取得首轮；启动若阻塞，首轮坏证据仍能否决STARTING。
        worker.scheduleWithFixedDelay(new Runnable() { public void run() { refresh(target); } }, 100, REFRESH_MS, TimeUnit.MILLISECONDS);
    }
    void refresh(AudioCaptureLifecycleSession target) {
        AudioCaptureLifecycle.Evidence value = null;
        try { if (!closed) value = evidence(reader.read(cancellation)); }
        catch (Exception | LinkageError failure) { /* null明确表示整轮读取失败。 */ }
        if (!closed) { latest = value; target.observe(value); }
    }
    private AudioCaptureLifecycle.Evidence evidence(Sample sample) {
        if (sample == null) return null;
        return new AudioCaptureLifecycle.Evidence(identity, request, session, sample.flinger, sample.policy,
                external(sample, identity.pid, identity.audioSession, clock.elapsed()), sample.began, sample.finished);
    }
    static AudioCaptureLifecycle.External external(Sample sample, int pid, int session, long now) {
        if (sample == null || sample.externalJson == null || sample.began < 0 || sample.finished < sample.began
                || sample.finished > now || now - sample.began > AudioInputOwnership.MAX_AGE_MS
                || sample.finished - sample.began > AudioInputOwnership.MAX_SAMPLE_MS) return AudioCaptureLifecycle.External.UNKNOWN;
        AudioInputOwnership.Result inputs = AudioInputOwnership.evaluate(sample.flinger, sample.policy, pid, session, now);
        if (inputs.state == AudioInputOwnership.State.UNKNOWN) return AudioCaptureLifecycle.External.UNKNOWN;
        if (inputs.state == AudioInputOwnership.State.OTHER_ACTIVE) return AudioCaptureLifecycle.External.BUSY;
        try {
            JSONObject raw = new JSONObject(sample.externalJson), audio = raw.optJSONObject("audio");
            JSONArray sources = audio == null ? null : audio.optJSONArray("sources");
            if (sources == null || sources.length() != 9) return AudioCaptureLifecycle.External.UNKNOWN;
            boolean own = inputs.state == AudioInputOwnership.State.SELF_ONLY && inputs.activeInputs == 1;
            for (int i = 0; i < 9; i++) {
                JSONObject row = sources.optJSONObject(i);
                if (row == null || !(row.opt("source") instanceof Integer) || row.getInt("source") != i
                        || !(row.opt("active") instanceof Boolean)) return AudioCaptureLifecycle.External.UNKNOWN;
                if (i == 1 && own) {
                    if (!row.getBoolean("active")) return AudioCaptureLifecycle.External.UNKNOWN;
                    // 仅去除已由两源及当前PID/session证明的MIC；原缓存字符串不变。
                    row.put("active", false);
                } else if (row.getBoolean("active")) return AudioCaptureLifecycle.External.BUSY;
            }
            AndroidAudioOccupancy.State state = AndroidAudioOccupancy.evaluate(raw, sample.began, sample.finished).overall();
            return state == AndroidAudioOccupancy.State.IDLE ? AudioCaptureLifecycle.External.IDLE
                    : state == AndroidAudioOccupancy.State.BUSY ? AudioCaptureLifecycle.External.BUSY : AudioCaptureLifecycle.External.UNKNOWN;
        } catch (Exception malformed) { return AudioCaptureLifecycle.External.UNKNOWN; }
    }
    public void close() throws Exception {
        closed = true; latest = null; cancellation.cancel(); worker.shutdownNow();
        if (!worker.awaitTermination(750, TimeUnit.MILLISECONDS)) throw new IOException("MEDIA_INPUT_OBSERVER_RELEASE_UNCONFIRMED");
    }
}
