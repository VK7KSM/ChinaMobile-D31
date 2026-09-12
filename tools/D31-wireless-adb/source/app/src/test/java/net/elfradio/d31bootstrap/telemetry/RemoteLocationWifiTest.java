package net.elfradio.d31bootstrap.telemetry;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 虚拟单调时钟回放；不访问设备或Android框架。 */
public class RemoteLocationWifiTest {
    private static final long START = 1000000000000L;
    private static class Access implements RemoteLocationWifi.Access {
        long now = START, completeMs = Long.MAX_VALUE;
        int starts, sleeps;
        boolean enabled = true, accepted = true, cancel;
        List<RemoteLocationWifi.Scan> initial = Collections.emptyList(), next = Collections.emptyList();
        public List<RemoteLocationWifi.Scan> results() { return (now - START) / 1000000 >= completeMs ? next : initial; }
        public boolean enabled() { return enabled; }
        public boolean start() { starts++; return accepted; }
        public long nowNanos() { return now; }
        public void sleep(long ms) throws InterruptedException { sleeps++; if (cancel) throw new InterruptedException(); now += ms * 1000000L; }
    }
    private static RemoteLocationWifi.Scan ap(int index, long at) {
        return new RemoteLocationWifi.Scan(String.format(java.util.Locale.US, "00:11:22:33:44:%02x", index), -40 - index, at / 1000L);
    }
    private static List<RemoteLocationWifi.Scan> pair(long at) { return Arrays.asList(ap(1, at), ap(2, at)); }
    @Test public void freshCacheNeedsNoScanOrSleep() throws Exception {
        Access a = new Access(); a.initial = pair(START - 1000000000L);
        RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
        assertEquals("cached_results", r.scanResult); assertEquals(2, r.rawCount); assertEquals(2, r.validCount);
        assertEquals(Long.valueOf(1000), r.newestAgeMs); assertEquals(0, a.starts); assertEquals(0, a.sleeps);
    }
    @Test public void realLateScanTimingsRemainOutsideUnchangedTwoSecondWindow() throws Exception {
        for (long duration : new long[]{3922, 3928, 3919, 3895, 3922, 3928}) {
            Access a = new Access(); a.initial = pair(START - 213480000000L);
            a.completeMs = duration; a.next = pair(START + duration * 1000000L);
            RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
            assertEquals(1, a.starts); assertEquals(4, a.sleeps); assertEquals(2000, r.waitMs);
            assertEquals(2, r.rawCount); assertEquals(0, r.validCount);
            assertEquals("no_fresh_results_before_deadline", r.scanResult); assertEquals(Long.valueOf(215480), r.newestAgeMs);
        }
    }
    @Test public void newPairReturnsAtFirstPollAndKeepsOriginalTime() throws Exception {
        Access a = new Access(); a.completeMs = 370; a.next = pair(START + 370000000L);
        RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
        assertEquals(500, r.waitMs); assertEquals(1, a.sleeps); assertEquals(2, r.validCount);
        assertEquals("results_updated", r.scanResult); assertEquals(Long.valueOf(130), r.newestAgeMs);
        assertEquals((START + 370000000L) / 1000, r.valid.get(0).timestampMicros);
        assertEquals(0, r.initialRawCount); assertEquals(0, r.initialValidCount);
    }
    @Test public void oneFreshPointIsVisibleAndDoesNotShortenOriginalWindow() throws Exception {
        Access a = new Access(); a.completeMs = 370; a.next = Collections.singletonList(ap(1, START + 370000000L));
        RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
        assertEquals(2000, r.waitMs); assertEquals(4, a.sleeps); assertEquals("results_updated", r.scanResult);
        assertEquals(1, r.validCount); assertEquals(1, r.rawCount); assertEquals(Long.valueOf(1630), r.newestAgeMs);
    }
    @Test public void unchangedSinglePointDoesNotBecomeNewScan() throws Exception {
        Access a = new Access(); a.initial = Collections.singletonList(ap(1, START - 1000000000L));
        RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
        assertEquals(1, r.initialRawCount); assertEquals(1, r.initialValidCount); assertEquals(1, r.validCount);
        assertEquals("no_fresh_results_before_deadline", r.scanResult);
    }
    @Test public void disabledAndRejectedDoNotSleepOrChangeSettings() throws Exception {
        Access a = new Access(); a.enabled = false;
        assertEquals("wifi_disabled", RemoteLocationWifi.capture(a).scanResult); assertEquals(0, a.starts); assertEquals(0, a.sleeps);
        a = new Access(); a.accepted = false;
        assertEquals("request_rejected", RemoteLocationWifi.capture(a).scanResult); assertEquals(1, a.starts); assertEquals(0, a.sleeps);
    }
    @Test public void finalPollIncludesExactlyTwoSecondResultButNotLater() throws Exception {
        for (long at : new long[]{2000, 2001}) {
            Access a = new Access(); a.completeMs = at; a.next = pair(START + at * 1000000L);
            RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
            assertEquals(4, a.sleeps); assertEquals(2000, r.waitMs); assertEquals(at == 2000 ? 2 : 0, r.validCount);
        }
    }
    @Test public void rawInvalidDuplicateStaleAndFutureCountsRemainDistinct() throws Exception {
        Access a = new Access(); a.enabled = false;
        a.initial = Arrays.asList(ap(1, START), ap(1, START), ap(2, START), null,
                new RemoteLocationWifi.Scan("02:11:22:33:44:03", -50, START / 1000),
                new RemoteLocationWifi.Scan("00:11:22:33:44:04", 0, START / 1000),
                ap(5, START - 120001000000L), ap(6, START + 1000000));
        RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a);
        assertEquals(8, r.rawCount); assertEquals(2, r.validCount); assertEquals(Long.valueOf(0), r.newestAgeMs);
    }
    @Test public void freshnessAndMissingTimesStayStrict() throws Exception {
        Access a = new Access(); a.enabled = false;
        a.initial = Arrays.asList(ap(1, START - 120000000000L), ap(2, START - 120001000000L),
                new RemoteLocationWifi.Scan("00:11:22:33:44:03", -50, 0),
                new RemoteLocationWifi.Scan("00:11:22:33:44:04", -50, Long.MAX_VALUE));
        assertEquals(1, RemoteLocationWifi.capture(a).validCount); a.initial = null;
        RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a); assertEquals(0, r.rawCount); assertNull(r.newestAgeMs);
    }
    @Test public void passiveCacheReadsNeverRequestOrWaitForScanEvenWithOneOrStalePoint() throws Exception {
        for (long age : new long[]{0, 1000, 120000, 120001}) {
            Access a = new Access(); a.initial = Collections.singletonList(ap(1, START - age * 1000000L));
            RemoteLocationWifi.Result r = RemoteLocationWifi.capture(a, false);
            assertEquals("passive_cache", r.scanResult); assertEquals(0, a.starts); assertEquals(0, a.sleeps);
            assertEquals(0, r.waitMs); assertEquals(1, r.rawCount); assertEquals(age <= 120000 ? 1 : 0, r.validCount);
        }
    }
    @Test public void cancellationPropagatesWithoutAnotherScan() throws Exception {
        Access a = new Access(); a.cancel = true;
        try { RemoteLocationWifi.capture(a); fail(); } catch (InterruptedException expected) { }
        assertEquals(1, a.starts); assertEquals(1, a.sleeps);
    }
}
