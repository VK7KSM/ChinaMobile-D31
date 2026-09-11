package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

public class FaultMonitorTest {
    private File root, evidence;
    private FaultTestFiles.Clock clock;
    private Source source;
    private FaultMonitor monitor;
    private FaultPolicy policy;
    @Before public void setup() throws Exception {
        FaultExports.identities = FaultTestFiles::identity;
        root = Files.createTempDirectory("fault-monitor-").toFile(); evidence = new File(root, "archive");
        clock = new FaultTestFiles.Clock(); source = new Source(); policy = policy(8, 3, 0);
        monitor = new FaultMonitor(evidence, source, clock, policy);
    }
    @After public void stop() { if (monitor != null) monitor.close(); }
    private FaultPolicy policy(int max, int attempts, long minFree) {
        return new FaultPolicy(max, 4, attempts, 1024, 2, 8L * 1048576, minFree, 1000, 1000, 1000, 10000, 1000);
    }
    private final class Source implements FaultSources {
        final FaultTestFiles.Access access = new FaultTestFiles.Access(new File(root, "sources"));
        final List<Candidate> candidates = new ArrayList<Candidate>();
        int captures; boolean fail, nonRetry, missingContext, failCommit;
        String boot = FaultArchive.hash("boot-a");
        CountDownLatch entered, release;
        Candidate add(String name, String raw) throws Exception {
            String path = "/data/anr/" + name + ".txt"; access.write(path, raw.getBytes("UTF-8"));
            Candidate c = new Candidate("ANR", path, FaultArchive.hash(raw), clock.wall); candidates.add(c); return c;
        }
        public Scan discover(FaultPolicy p) throws Exception {
            if (entered != null) { entered.countDown(); release.await(3, TimeUnit.SECONDS); }
            return new Scan(candidates, new JSONObject().put("state", "CHECKED"));
        }
        public Capture capture(Candidate c, File dir, FaultPolicy p) throws Exception {
            captures++;
            if (fail) {
                FaultArchive.writeNew(new File(dir, "partial.bin"), new byte[]{1, 2, 3});
                throw new CollectionAccess.Failure("READ_ERROR");
            }
            if (nonRetry) return new Capture(new JSONObject().put("state", "PARTIAL").put("reason", "SOURCE_CHANGED"), false);
            JSONObject result = new FaultEvidenceCollector(access, clock).collect("fault",
                    java.util.Collections.singletonList(new FaultEvidenceCollector.Source("source", "ANR", c.path)),
                    new CollectionLimits(1, p.maxFileBytes, p.maxFileBytes, p.scanMs, 0, 65536, 10000), FaultTestFiles.store(dir));
            return new Capture(result, false);
        }
        public JSONObject context() throws Exception { return missingContext ? new JSONObject().put("state", "UNAVAILABLE") : FaultTestFiles.context(clock.wall, clock.elapsed); }
        public String bootKey() { return boot; }
        public void checkPrivateRoot(File path) throws IOException { FaultArchive.checked(path); }
        public void replace(File from, File to) throws IOException {
            if (failCommit && to.getName().equals("state.json")) throw new IOException("disk account secret");
            FaultTestFiles.replace(from, to);
        }
    }
    private void tick() throws Exception {
        assertEquals(FaultMonitor.Submission.ACCEPTED, monitor.tick()); await();
        assertEquals("NONE", monitor.lastFailure()); clock.add(1000);
    }
    private void await() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (monitor.isBusy() && System.nanoTime() < end) Thread.sleep(5);
        assertFalse("worker did not finish", monitor.isBusy());
    }
    private JSONObject state(String id) throws Exception { return monitor.query(id).getJSONObject("state"); }
    private void reopen() throws Exception { monitor.close(); monitor = new FaultMonitor(evidence, source, clock, policy); }

    @Test public void freezesOriginalAndUsesRealDiagnosticReceipt() throws Exception {
        tick(); FaultSources.Candidate c = source.add("traces", "private payload\n"); tick(); tick();
        assertEquals("COMPLETE", state(c.id()).getString("phase"));
        File raw = new File(evidence, c.id() + "/attempt-1/fault-source.bin");
        assertEquals("private payload\n", new String(Files.readAllBytes(raw.toPath()), "UTF-8"));
        source.access.write(c.path, "later changed".getBytes("UTF-8")); tick();
        assertEquals("private payload\n", new String(Files.readAllBytes(raw.toPath()), "UTF-8"));
        JSONObject diagnostic = FaultArchive.read(new File(raw.getParentFile(), "report.json")).getJSONObject("diagnostic");
        assertEquals(1, diagnostic.getInt("schemaVersion"));
        assertEquals(FaultArchive.hash("private payload\n"), diagnostic.getJSONArray("items").getJSONObject(0).getString("storedSha256"));
    }
    @Test public void duplicateSightingsNeverMeanMultipleCrashes() throws Exception {
        FaultSources.Candidate c = source.add("traces", "one"); tick(); tick(); reopen(); tick();
        assertEquals(1, source.captures); assertEquals(1, monitor.index(8).getInt("total"));
        assertEquals(3, state(c.id()).getInt("observations"));
        assertEquals("POLL_SIGHTINGS_NOT_CRASH_COUNT", state(c.id()).getString("observationMeaning"));
    }
    @Test public void firstDetectionHasExplicitMissingPreWindow() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick(); tick();
        JSONObject result = monitor.query(c.id());
        assertEquals("MISSING", result.getString("preWindowState"));
        assertEquals("PARTIAL", state(c.id()).getString("phase"));
        assertEquals("UNAVAILABLE", result.getString("preFaultLogcat"));
    }
    @Test public void pendingPostNeverSleepsInWorker() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick();
        assertEquals("AWAITING_POST", state(c.id()).getString("phase"));
        assertFalse(new File(evidence, c.id() + "/post.json").exists());
    }
    @Test public void missedPostDeadlineIsNotFilledByLateLog() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick(); clock.add(11000); tick();
        assertEquals("MISSING_DEADLINE", state(c.id()).getString("postWindow"));
        assertFalse(new File(evidence, c.id() + "/post.json").exists());
    }
    @Test public void rebootDoesNotReusePreviousBootWindow() throws Exception {
        tick(); FaultSources.Candidate c = source.add("traces", "raw"); tick();
        source.boot = FaultArchive.hash("boot-b"); clock.elapsed = 100; reopen(); tick();
        assertEquals("MISSING_REBOOT", state(c.id()).getString("postWindow"));
    }
    @Test public void restartReusesFinishedAttemptWithoutReplay() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick();
        Files.delete(new File(evidence, c.id() + "/state.json").toPath()); reopen(); tick();
        assertEquals(1, source.captures); assertEquals(1, state(c.id()).getInt("attempts"));
    }
    @Test public void interruptedAttemptPreservesPartialAndConsumesRetry() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick();
        File attempt = new File(evidence, c.id() + "/attempt-1");
        Files.delete(new File(attempt, "report.json").toPath());
        byte[] original = Files.readAllBytes(new File(attempt, "fault-source.bin").toPath());
        reopen(); tick();
        assertEquals(2, source.captures);
        assertArrayEquals(original, Files.readAllBytes(new File(attempt, "fault-source.bin").toPath()));
        assertEquals("INTERRUPTED", monitor.query(c.id()).getJSONArray("attempts").getJSONObject(0).getString("state"));
    }
    @Test public void failedReadsStopAtThreeAndKeepAllOriginals() throws Exception {
        source.fail = true; FaultSources.Candidate c = source.add("traces", "raw");
        for (int i = 0; i < 8; i++) tick();
        assertEquals(3, source.captures); assertEquals("PARTIAL", state(c.id()).getString("phase"));
        for (int i = 1; i <= 3; i++) assertEquals(3, new File(evidence, c.id() + "/attempt-" + i + "/partial.bin").length());
    }
    @Test public void sourceReplacementIsTerminalAndNotRetried() throws Exception {
        source.nonRetry = true; FaultSources.Candidate c = source.add("traces", "raw"); tick(); tick(); tick();
        assertEquals(1, source.captures); assertEquals("PARTIAL", state(c.id()).getString("phase"));
    }
    @Test public void capacityPreservesOldEvidenceAndDefersNewEvent() throws Exception {
        monitor.close(); policy = policy(1, 3, 0); monitor = new FaultMonitor(evidence, source, clock, policy);
        FaultSources.Candidate a = source.add("a", "original"); tick(); source.add("b", "new"); tick();
        assertEquals(1, monitor.index(8).getInt("total"));
        assertEquals("CAPACITY_LIMIT", monitor.index(8).getJSONObject("scan").getString("state"));
        assertEquals("original", new String(Files.readAllBytes(new File(evidence, a.id() + "/attempt-1/fault-source.bin").toPath()), "UTF-8"));
    }
    @Test public void diskReserveRefusesBeforeCreatingEvent() throws Exception {
        monitor.close(); policy = policy(8, 3, 1024L * 1024 * 1024 * 1024);
        monitor = new FaultMonitor(evidence, source, clock, policy); source.add("a", "raw"); tick();
        assertEquals(0, source.captures); assertEquals(0, monitor.index(8).getInt("total"));
    }
    @Test public void busyWorkerHasNoUnboundedQueueAndQueryStillWorks() throws Exception {
        source.entered = new CountDownLatch(1); source.release = new CountDownLatch(1);
        assertEquals(FaultMonitor.Submission.ACCEPTED, monitor.tick()); assertTrue(source.entered.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 100; i++) assertEquals(FaultMonitor.Submission.BUSY, monitor.collect());
        assertEquals(0, monitor.index(8).getInt("total")); source.release.countDown(); await();
    }
    @Test public void throttlePersistsAcrossNewCommandProcesses() throws Exception {
        tick(); clock.add(-1000); reopen();
        assertEquals(FaultMonitor.Submission.ACCEPTED, monitor.collect()); await();
        assertEquals("THROTTLED", monitor.lastOutcome());
    }
    @Test public void localThrottleAndClosedStateAreImmediate() throws Exception {
        tick(); clock.add(-1000); assertEquals(FaultMonitor.Submission.THROTTLED, monitor.collect());
        monitor.close(); assertEquals(FaultMonitor.Submission.CLOSED, monitor.tick());
    }
    @Test public void querySurvivesMonitorClosureAndDoesNotScan() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick(); monitor.close();
        assertEquals(c.id(), FaultMonitor.readQuery(evidence, c.id()).getString("eventId")); assertEquals(1, source.captures);
    }
    @Test public void summaryNeverCopiesSourceTextOrPath() throws Exception {
        FaultSources.Candidate c = source.add("private-account-name", "account-secret-value"); tick(); tick();
        String summary = monitor.index(8).toString();
        assertFalse(summary.contains("private-account-name")); assertFalse(summary.contains("account-secret-value"));
        assertFalse(summary.contains("attemptBoot")); assertTrue(summary.contains(c.id()));
    }
    @Test public void missingContextIsVisibleAndCannotBeComplete() throws Exception {
        source.missingContext = true; tick(); FaultSources.Candidate c = source.add("traces", "raw"); tick(); tick();
        assertEquals("UNAVAILABLE", state(c.id()).getString("postWindow")); assertEquals("PARTIAL", state(c.id()).getString("phase"));
    }
    @Test public void samePassDuplicatesOnlyCountOnce() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); source.candidates.add(c); tick();
        assertEquals(1, state(c.id()).getInt("observations")); assertEquals(1, source.captures);
    }
    @Test public void stateCommitFailureCannotLeakErrorOrReportSuccess() throws Exception {
        source.failCommit = true; source.add("traces", "raw");
        assertEquals(FaultMonitor.Submission.ACCEPTED, monitor.tick()); await();
        assertEquals("COLLECTION_FAILED", monitor.lastFailure());
        assertFalse(monitor.index(8).toString().contains("disk account secret"));
    }
    @Test public void truncatedRawIsPreservedAndMarkedPartial() throws Exception {
        StringBuilder text = new StringBuilder(); for (int i = 0; i < 2048; i++) text.append('x');
        FaultSources.Candidate c = source.add("traces", text.toString()); tick(); tick();
        assertEquals(1024, new File(evidence, c.id() + "/attempt-1/fault-source.bin").length());
        assertEquals("PARTIAL", state(c.id()).getString("phase"));
    }
    @Test public void nonTargetSourceFileIsNeverModified() throws Exception {
        source.access.write("/untouched.dat", new byte[]{9, 8, 7}); source.add("traces", "raw"); tick(); tick();
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(source.access.resolve("/untouched.dat")));
    }
    @Test public void readOnlyQueryDoesNotCreateMissingArchive() throws Exception {
        File missing = new File(root, "missing");
        try { FaultMonitor.readIndex(missing, 8); fail(); } catch (IOException expected) { assertFalse(missing.exists()); }
    }
    @Test public void unusablePreContextCannotBecomeCompleteWhenPostWorks() throws Exception {
        source.missingContext = true; tick(); source.missingContext = false;
        FaultSources.Candidate c = source.add("traces", "raw"); tick(); tick();
        assertEquals("PARTIAL", monitor.query(c.id()).getString("preWindowState"));
        assertEquals("PARTIAL", state(c.id()).getString("phase"));
    }
    @Test public void oneCorruptReportDoesNotBlockOtherEvidence() throws Exception {
        FaultSources.Candidate first = source.add("first", "raw"); tick();
        Files.write(new File(evidence, first.id() + "/attempt-1/report.json").toPath(), new byte[]{0});
        FaultSources.Candidate second = source.add("second", "new"); tick();
        assertEquals(2, source.captures); assertEquals(second.id(), monitor.query(second.id()).getString("eventId"));
        assertTrue(monitor.index(8).getJSONObject("scan").getInt("eventIndexErrors") > 0);
    }
    @Test public void queryRejectsTraversal() throws Exception {
        try { monitor.query("../escape"); fail(); } catch (IOException expected) { assertEquals("INVALID_EVENT_ID", expected.getMessage()); }
    }
    @Test public void verifiedArchiveReleasesActiveSlotButNotTotalBytes() throws Exception {
        monitor.close(); policy = policy(1, 3, 0); monitor = new FaultMonitor(evidence, source, clock, policy);
        FaultSources.Candidate first = source.add("first", "raw"); tick(); tick();
        FaultExports exports = new FaultExports(evidence, source, policy);
        JSONObject r = exports.exportEvent(first.id()); long bytes = new FaultArchive(evidence, source).bytes();
        FaultSources.Candidate second = source.add("second", "second"); tick();
        assertEquals(1, source.captures);
        exports.archiveEvent(first.id(), r.getString("sha256"), r.getLong("bytes"), r.getString("manifestSha256")); tick();
        assertEquals(2, source.captures); assertTrue(new FaultArchive(evidence, source).bytes() > bytes);
        assertEquals(1, monitor.index(8).getJSONObject("scan").getInt("activeEvents"));
        assertEquals(1, monitor.index(8).getJSONObject("scan").getInt("archivedEvents"));
        assertEquals(second.id(), monitor.query(second.id()).getString("eventId"));
        reopen(); tick(); tick();
        assertEquals(2, source.captures); assertEquals(2, monitor.index(8).getInt("total"));
    }
    @Test public void exportSealPreventsCounterDriftBeforeDownloadAck() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick(); tick();
        JSONObject r = new FaultExports(evidence, source, policy).exportEvent(c.id());
        byte[] before = Files.readAllBytes(new File(evidence, c.id() + "/state.json").toPath()); tick();
        assertArrayEquals(before, Files.readAllBytes(new File(evidence, c.id() + "/state.json").toPath()));
        new FaultExports(evidence, source, policy).archiveEvent(c.id(), r.getString("sha256"), r.getLong("bytes"), r.getString("manifestSha256"));
    }
    @Test public void cursorCanReachEveryRetainedEvent() throws Exception {
        source.add("first", "raw"); source.add("second", "other"); tick();
        JSONObject page = FaultMonitor.readIndex(evidence, 1, "");
        assertTrue(page.getBoolean("hasMore"));
        JSONObject next = FaultMonitor.readIndex(evidence, 1, page.getString("nextAfter"));
        assertFalse(next.getBoolean("hasMore")); assertEquals(1, next.getJSONArray("events").length());
        assertNotEquals(page.getString("nextAfter"), next.getString("nextAfter"));
    }
}
