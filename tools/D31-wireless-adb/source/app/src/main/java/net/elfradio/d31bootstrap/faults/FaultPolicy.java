package net.elfradio.d31bootstrap.faults;

/** Budgets cover local evidence only; no network retries or automatic deletion. */
public final class FaultPolicy {
    public final int maxEvents, maxCandidates, maxAttempts, maxFileBytes, preSamples;
    public final long maxArchiveBytes, minFreeBytes, tickMs, retryMs, postMs, windowMs, scanMs;

    public FaultPolicy(int maxEvents, int maxCandidates, int maxAttempts, int maxFileBytes,
                       int preSamples, long maxArchiveBytes, long minFreeBytes, long tickMs,
                       long retryMs, long postMs, long windowMs, long scanMs) {
        if (maxEvents < 1 || maxEvents > 64 || maxCandidates < 1 || maxCandidates > 16
                || maxAttempts < 1 || maxAttempts > 3 || maxFileBytes < 1 || maxFileBytes > 1048576
                || preSamples < 0 || preSamples > 4 || maxArchiveBytes < 1048576
                || maxArchiveBytes > 256L * 1048576 || minFreeBytes < 0 || minFreeBytes > 1024L * 1024 * 1024 * 1024
                || tickMs < 1000 || tickMs > 3600000 || retryMs < tickMs || retryMs > 3600000
                || postMs < 0 || windowMs < postMs || windowMs > 3600000
                || scanMs < 1 || scanMs > 30000) throw new IllegalArgumentException("INVALID_FAULT_POLICY");
        this.maxEvents = maxEvents; this.maxCandidates = maxCandidates; this.maxAttempts = maxAttempts;
        this.maxFileBytes = maxFileBytes; this.preSamples = preSamples; this.maxArchiveBytes = maxArchiveBytes;
        this.minFreeBytes = minFreeBytes; this.tickMs = tickMs; this.retryMs = retryMs;
        this.postMs = postMs; this.windowMs = windowMs; this.scanMs = scanMs;
    }

    public static FaultPolicy defaults() {
        return new FaultPolicy(32, 8, 3, 262144, 4, 64L * 1048576, 32L * 1048576,
                60000, 60000, 30000, 300000, 5000);
    }

    long reservationBytes() {
        return maxAttempts * (maxFileBytes + 65536L) + (preSamples + 1L) * 65536 + 65536;
    }
}
