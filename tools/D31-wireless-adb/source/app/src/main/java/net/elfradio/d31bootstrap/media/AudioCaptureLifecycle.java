package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.Set;

/** 单次录音的只读证据状态机；不访问设备、不创建采音、不改变公开媒体能力。 */
public final class AudioCaptureLifecycle {
    public static final String PACKAGE = "net.elfradio.d31bootstrap";
    public static final long START_LIMIT_MS = 1500;
    public static final long RELEASE_LIMIT_MS = 1500;
    public enum Phase { NEW, STARTING, VERIFYING, ACTIVE, STOPPING, RELEASE_UNCONFIRMED, CLOSED }
    public enum External { IDLE, BUSY, UNKNOWN }
    public interface Clock { long elapsed(); }

    /** 由可信APP/PackageManager及本次AudioRecord实读；不接受Web自报身份。 */
    public static final class Identity {
        public final String packageName, apkSha256, bootId;
        public final int uid, pid, audioSession;
        public Identity(String packageName, String apkSha256, String bootId, int uid, int pid, int audioSession) {
            if (!PACKAGE.equals(packageName) || apkSha256 == null || !apkSha256.matches("[a-f0-9]{64}")
                    || bootId == null || !bootId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                    || uid < 10000 || pid <= 0 || audioSession <= 0) throw new IllegalArgumentException("录音身份无效");
            this.packageName = packageName; this.apkSha256 = apkSha256; this.bootId = bootId;
            this.uid = uid; this.pid = pid; this.audioSession = audioSession;
        }
        boolean same(Identity other) {
            return other != null && packageName.equals(other.packageName) && apkSha256.equals(other.apkSha256)
                    && bootId.equals(other.bootId) && uid == other.uid && pid == other.pid && audioSession == other.audioSession;
        }
    }

    /** external只包括蜂窝/SIP/播放/焦点等非本次输入因素；未知不能写成IDLE。 */
    public static final class Evidence {
        public final Identity identity;
        public final String requestId, sessionId;
        public final AudioInputOwnership.Dump flinger, policy;
        public final External external;
        public final long externalBegan, externalFinished;
        public Evidence(Identity identity, String requestId, String sessionId, AudioInputOwnership.Dump flinger,
                AudioInputOwnership.Dump policy, External external, long externalBegan, long externalFinished) {
            this.identity = identity; this.requestId = requestId; this.sessionId = sessionId;
            this.flinger = flinger; this.policy = policy; this.external = external;
            this.externalBegan = externalBegan; this.externalFinished = externalFinished;
        }
    }

    /** stop/release返回与真实状态回读必须同时满足；不能用命令成功替代释放。 */
    public static final class Release {
        public final Identity identity;
        public final boolean stopReturned, releaseReturned, captureWorkerFinished;
        public final int recordState, recordingState;
        public final long finished;
        public Release(Identity identity, boolean stopReturned, boolean releaseReturned, boolean captureWorkerFinished,
                int recordState, int recordingState, long finished) {
            this.identity = identity; this.stopReturned = stopReturned; this.releaseReturned = releaseReturned;
            this.captureWorkerFinished = captureWorkerFinished; this.recordState = recordState;
            this.recordingState = recordingState; this.finished = finished;
        }
    }

    public static final class Snapshot {
        public final Phase phase;
        public final String reason;
        public final String stopReason;
        public final boolean continuationEligible, stopRequired, releaseConfirmed;
        public final boolean managedMedia = false, atomicReservation = false;
        public final boolean ioBound;
        public final int inputIoHandle;
        private Snapshot(Phase phase, String reason, String stopReason, int inputIoHandle) {
            this.phase = phase; this.reason = reason; this.stopReason = stopReason;
            this.inputIoHandle = inputIoHandle; this.ioBound = inputIoHandle > 0;
            continuationEligible = phase == Phase.ACTIVE;
            stopRequired = phase == Phase.STOPPING || phase == Phase.RELEASE_UNCONFIRMED;
            releaseConfirmed = phase == Phase.CLOSED;
        }
    }

    private final Identity identity;
    private final String requestId, sessionId;
    private final Clock clock;
    private long lastNow, began = -1, started = -1, stopping = -1, validFrom = -1;
    private long lastFlinger = -1, lastPolicy = -1, lastExternal = -1;
    private int inputHandle;
    private boolean startInFlight, clockInvalid;
    private Phase phase = Phase.NEW;
    private String reason = "NOT_STARTED";
    private String stopReason = "";

    public AudioCaptureLifecycle(Identity identity, String requestId, String sessionId, Clock clock) {
        if (identity == null || clock == null || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")
                || sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException("生命周期参数无效");
        this.identity = identity; this.requestId = requestId; this.sessionId = sessionId; this.clock = clock;
        lastNow = clock.elapsed();
        if (lastNow < 0) throw new IllegalArgumentException("单调时间无效");
    }

    public String sessionId() { return sessionId; }

    /** 开始前必须无任何输入；即使已存在同PID/session的输入也不允许接管。 */
    public synchronized void begin(Evidence evidence) throws IOException {
        if (phase != Phase.NEW) throw new IOException("MEDIA_LIFECYCLE_NOT_REUSABLE");
        long now = time();
        Analysis checked = inspect(evidence, now);
        if (checked.failure != null || checked.ownership != AudioInputOwnership.State.NO_ACTIVE_INPUT) {
            stop(checked.failure == null ? "INPUT_PRESENT_BEFORE_START" : checked.failure, now);
            throw new IOException("MEDIA_LIFECYCLE_START_REJECTED");
        }
        if (phase != Phase.NEW) throw new IOException("MEDIA_LIFECYCLE_CLOCK_INVALID");
        began = now; phase = Phase.STARTING; reason = "START_IN_FLIGHT"; startInFlight = true;
    }

    public synchronized void started() {
        long now = time(); startInFlight = false;
        if (phase != Phase.STARTING) return;
        if (now - began > START_LIMIT_MS) { stop("START_TOO_SLOW", now); return; }
        started = now; phase = Phase.VERIFYING; reason = "AWAITING_OWNERSHIP";
    }

    public synchronized void startFailed() {
        startInFlight = false; stop("START_FAILED", time());
    }

    /** 首份有效自身轨道只绑定一次IO；已关闭、停止或证据失败后不能用新快照复活。 */
    public synchronized Snapshot observe(Evidence evidence) {
        long now = time(); expire(now);
        if (phase != Phase.STARTING && phase != Phase.VERIFYING && phase != Phase.ACTIVE) return snapshotValue();
        Analysis checked = inspect(evidence, now);
        if (checked.failure != null) { stop(checked.failure, now); return snapshotValue(); }
        // 启动尚未返回也必须接受否决；好证据不提前激活或绑定IO。
        if (phase == Phase.STARTING) {
            if (checked.ownership == AudioInputOwnership.State.OTHER_ACTIVE) stop("OTHER_ACTIVE", now);
            return snapshotValue();
        }
        if (checked.ownership != AudioInputOwnership.State.SELF_ONLY || checked.handle <= 0) {
            stop(checked.ownership == AudioInputOwnership.State.OTHER_ACTIVE ? "OTHER_ACTIVE" : "OWN_INPUT_NOT_CONFIRMED", now);
            return snapshotValue();
        }
        if (evidence.flinger.startedElapsedMs < started || evidence.policy.startedElapsedMs < started) {
            stop("PRE_START_INPUT_EVIDENCE", now); return snapshotValue();
        }
        if (evidence.flinger.startedElapsedMs < lastFlinger || evidence.policy.startedElapsedMs < lastPolicy
                || evidence.externalBegan < lastExternal) { stop("EVIDENCE_REORDERED", now); return snapshotValue(); }
        if (inputHandle != 0 && inputHandle != checked.handle) { stop("INPUT_HANDLE_CHANGED", now); return snapshotValue(); }
        inputHandle = checked.handle;
        lastFlinger = evidence.flinger.startedElapsedMs; lastPolicy = evidence.policy.startedElapsedMs;
        lastExternal = evidence.externalBegan; validFrom = checked.first;
        phase = Phase.ACTIVE; reason = "BOUND_SELF_INPUT_OBSERVED";
        return snapshotValue();
    }

    public synchronized Snapshot snapshot() { long now = time(); expire(now); return snapshotValue(); }

    public synchronized void requestStop() { stop("STOP_REQUESTED", time()); }

    public synchronized boolean cancelRequest(String id) {
        if (!requestId.equals(id)) return false;
        stop("CANCELLED", time()); return true;
    }

    public synchronized void releaseFailed() {
        stop("RELEASE_FAILED", time());
        if (phase != Phase.CLOSED) { phase = Phase.RELEASE_UNCONFIRMED; reason = "RELEASE_FAILED"; }
    }

    public synchronized boolean confirmRelease(Release release) {
        long now = time(); expire(now);
        if (phase == Phase.CLOSED) return true;
        if (phase != Phase.STOPPING && phase != Phase.RELEASE_UNCONFIRMED) return false;
        if (clockInvalid || startInFlight || release == null || !identity.same(release.identity) || !release.stopReturned
                || !release.releaseReturned || !release.captureWorkerFinished || release.recordState != 0
                || release.recordingState != 1 || release.finished < stopping || release.finished > now) {
            phase = Phase.RELEASE_UNCONFIRMED; reason = "RELEASE_NOT_PROVEN"; return false;
        }
        phase = Phase.CLOSED; reason = "RELEASE_CONFIRMED"; return true;
    }

    private long time() {
        long now = clock.elapsed();
        if (now < lastNow || now < 0) {
            clockInvalid = true;
            stop("MONOTONIC_CLOCK_REGRESSED", lastNow);
            return lastNow;
        }
        lastNow = now; return now;
    }
    private void expire(long now) {
        if ((phase == Phase.STARTING && now - began > START_LIMIT_MS)
                || (phase == Phase.VERIFYING && now - started > START_LIMIT_MS)) stop("START_OR_OWNERSHIP_TIMEOUT", now);
        if (phase == Phase.ACTIVE && now - validFrom > AudioInputOwnership.MAX_AGE_MS) stop("EVIDENCE_STALE", now);
        if (phase == Phase.STOPPING && now - stopping > RELEASE_LIMIT_MS) {
            phase = Phase.RELEASE_UNCONFIRMED; reason = "RELEASE_TIMEOUT";
        }
    }
    private void stop(String why, long now) {
        if (phase == Phase.CLOSED || phase == Phase.STOPPING || phase == Phase.RELEASE_UNCONFIRMED) return;
        phase = Phase.STOPPING; reason = why; stopReason = why; stopping = now; validFrom = -1;
    }
    private Snapshot snapshotValue() { return new Snapshot(phase, reason, stopReason, inputHandle); }

    private Analysis inspect(Evidence evidence, long now) {
        if (evidence == null || !identity.same(evidence.identity) || !requestId.equals(evidence.requestId)
                || !sessionId.equals(evidence.sessionId)) return Analysis.bad("CAPTURE_IDENTITY_CHANGED");
        if (evidence.external != External.IDLE) return Analysis.bad(evidence.external == External.BUSY ? "EXTERNAL_BUSY" : "EXTERNAL_UNKNOWN");
        if (evidence.externalBegan < 0 || evidence.externalFinished < evidence.externalBegan
                || evidence.externalFinished > now || now - evidence.externalBegan > AudioInputOwnership.MAX_AGE_MS
                || evidence.externalFinished - evidence.externalBegan > AudioInputOwnership.MAX_SAMPLE_MS) {
            return Analysis.bad("EXTERNAL_EVIDENCE_STALE_OR_INVALID");
        }
        AudioInputOwnership.Result result = AudioInputOwnership.evaluate(evidence.flinger, evidence.policy, identity.pid, identity.audioSession, now);
        if (result.state == AudioInputOwnership.State.UNKNOWN) return Analysis.bad(result.reason);
        long first = Math.min(evidence.externalBegan, Math.min(evidence.flinger.startedElapsedMs, evidence.policy.startedElapsedMs));
        long end = Math.max(evidence.externalFinished, Math.max(evidence.flinger.finishedElapsedMs, evidence.policy.finishedElapsedMs));
        if (first < 0 || end - first > AudioInputOwnership.MAX_SAMPLE_MS) return Analysis.bad("COMBINED_EVIDENCE_WINDOW_INVALID");
        int handle = 0;
        if (result.state == AudioInputOwnership.State.SELF_ONLY) {
            if (result.activeInputs != 1) return Analysis.bad("MULTIPLE_OWN_INPUTS");
            String[] lines = evidence.policy.text.replace('\r', '\n').split("\n", -1);
            int from = -1, to = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().equals("Inputs dump:")) from = i + 1;
                if (lines[i].trim().equals("Streams dump:")) to = i;
            }
            try {
                // 原解析器已验证完整结构；复用Records取得handle，不另造一套列解析。
                Set<Integer> handles = AudioInputOwnershipRecords.policy(lines, from, to);
                if (handles.size() != 1) return Analysis.bad("MULTIPLE_INPUT_HANDLES");
                handle = handles.iterator().next();
            } catch (AudioInputOwnership.Invalid invalid) { return Analysis.bad("INPUT_HANDLE_UNVERIFIED"); }
        }
        return new Analysis(result.state, handle, first, null);
    }
    private static final class Analysis {
        final AudioInputOwnership.State ownership;
        final int handle;
        final long first;
        final String failure;
        Analysis(AudioInputOwnership.State ownership, int handle, long first, String failure) {
            this.ownership = ownership; this.handle = handle; this.first = first; this.failure = failure;
        }
        static Analysis bad(String reason) { return new Analysis(AudioInputOwnership.State.UNKNOWN, 0, -1, reason); }
    }
}
