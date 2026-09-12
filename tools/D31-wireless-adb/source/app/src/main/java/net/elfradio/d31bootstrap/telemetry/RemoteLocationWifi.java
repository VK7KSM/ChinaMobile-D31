package net.elfradio.d31bootstrap.telemetry;

import java.util.*;

/** 有界扫描状态机；Android适配器仅提供原始观测和单调时钟，便于离线回放。 */
final class RemoteLocationWifi {
    interface Access {
        List<Scan> results();
        boolean enabled();
        boolean start();
        long nowNanos();
        void sleep(long ms) throws InterruptedException;
    }
    static final class Scan {
        final String mac;
        final int level;
        final long timestampMicros;
        Scan(String mac, int level, long timestampMicros) {
            this.mac = mac; this.level = level; this.timestampMicros = timestampMicros;
        }
    }
    static final class Result {
        final List<Scan> valid;
        final int rawCount, validCount, initialRawCount, initialValidCount;
        final String scanResult;
        final long waitMs;
        final Long newestAgeMs;
        final boolean enabled;
        Result(List<Scan> raw, long now, String result, long waitMs, boolean enabled, int initialRaw, int initialValid) {
            rawCount = raw == null ? 0 : raw.size();
            valid = valid(raw, now); validCount = valid.size();
            scanResult = result; this.waitMs = waitMs; this.enabled = enabled;
            initialRawCount = initialRaw; initialValidCount = initialValid;
            long newest = 0;
            if (raw != null) for (Scan scan : raw)
                if (scan != null && scan.timestampMicros > 0 && scan.timestampMicros <= Long.MAX_VALUE / 1000
                        && scan.timestampMicros * 1000L <= now) newest = Math.max(newest, scan.timestampMicros * 1000L);
            newestAgeMs = newest == 0 ? null : (now - newest) / 1000000L;
        }
    }

    static Result capture(Access access) throws InterruptedException {
        return capture(access, true);
    }

    static Result capture(Access access, boolean allowScan) throws InterruptedException {
        List<Scan> scans = access.results();
        int initialRaw = scans == null ? 0 : scans.size(), initialValid = valid(scans, access.nowNanos()).size();
        boolean enabled = access.enabled();
        if (!allowScan) return new Result(scans, access.nowNanos(), "passive_cache", 0, enabled, initialRaw, initialValid);
        String result = "cached_results";
        long waited = 0;
        if (initialValid < 2) {
            if (!enabled) result = "wifi_disabled";
            else {
                long requested = access.nowNanos();
                if (!access.start()) result = "request_rejected";
                else {
                    result = "no_fresh_results_before_deadline";
                    // 保持138原行为：最多四次500毫秒，至少两个有效AP才提前结束。
                    for (int poll = 0; poll < 4; poll++) {
                        access.sleep(500);
                        scans = access.results();
                        long now = access.nowNanos();
                        if (updated(scans, requested, now)) result = "results_updated";
                        if (valid(scans, now).size() >= 2) {
                            if (!"results_updated".equals(result)) result = "fresh_cache_available";
                            break;
                        }
                    }
                }
                waited = Math.max(0, (access.nowNanos() - requested) / 1000000L);
            }
        }
        return new Result(scans, access.nowNanos(), result, waited, enabled, initialRaw, initialValid);
    }

    private static boolean recent(Scan scan, long now) {
        return scan != null && scan.timestampMicros > 0 && scan.timestampMicros <= Long.MAX_VALUE / 1000
                && RemoteLocationRadio.recent(scan.timestampMicros * 1000L, now);
    }
    private static boolean updated(List<Scan> scans, long requested, long now) {
        if (scans != null) for (Scan scan : scans)
            if (recent(scan, now) && scan.timestampMicros * 1000L >= requested) return true;
        return false;
    }
    private static List<Scan> valid(List<Scan> scans, long now) {
        List<Scan> sorted = new ArrayList<Scan>();
        if (scans != null) for (Scan scan : scans)
            if (recent(scan, now) && RemoteLocationRadio.usableMac(scan.mac) && scan.level >= -127 && scan.level < 0)
                sorted.add(scan);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.level, a.level));
        Set<String> seen = new HashSet<String>();
        List<Scan> result = new ArrayList<Scan>();
        for (Scan scan : sorted) if (seen.add(scan.mac.toLowerCase(Locale.US))) result.add(scan);
        return result;
    }
    private RemoteLocationWifi() { }
}
