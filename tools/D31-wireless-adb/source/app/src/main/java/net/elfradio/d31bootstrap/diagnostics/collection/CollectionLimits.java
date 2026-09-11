package net.elfradio.d31bootstrap.diagnostics.collection;

import net.elfradio.d31bootstrap.diagnostics.DiagnosticContract;

/** 所有预算必须由调用方明确指定；没有默认扫描范围或无限预算。 */
public final class CollectionLimits {
    public final int maxEntries, maxDepth, maxManifestChars;
    public final long maxReadBytes, maxFileBytes, durationMs, validForMs;

    public CollectionLimits(int maxEntries, long maxReadBytes, long maxFileBytes,
                            long durationMs, int maxDepth, int maxManifestChars, long validForMs) {
        if (maxEntries < 1 || maxEntries > DiagnosticContract.MAX_ENTRIES || maxReadBytes < 0
                || maxReadBytes > 1024L * 1024 * 1024 || maxFileBytes < 0 || maxFileBytes > maxReadBytes
                || durationMs < 1 || durationMs > 300000 || maxDepth < 0 || maxDepth > 64
                || maxManifestChars < 16384 || maxManifestChars > DiagnosticContract.MAX_TEXT_CHARS - 65536
                || validForMs < 1 || validForMs > 86400000L) throw new IllegalArgumentException("INVALID_LIMITS");
        this.maxEntries = maxEntries; this.maxReadBytes = maxReadBytes; this.maxFileBytes = maxFileBytes;
        this.durationMs = durationMs; this.maxDepth = maxDepth; this.maxManifestChars = maxManifestChars;
        this.validForMs = validForMs;
    }
}
