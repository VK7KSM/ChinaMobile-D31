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
        final List<Candidate> capturedOrder = new ArrayList<Candidate>();
        int captures; boolean fail, nonRetry, missingContext, failCommit;
        String boot = FaultArchive.hash("boot-a");
        CountDownLatch entered, release;
        JSONObject lastContinuation, nextContinuation = new JSONObject();
        Candidate add(String name, String raw) throws Exception {
            return add("ANR", name, raw, clock.wall);
        }
        Candidate add(String category, String name, String raw, long time) throws Exception {
            String path = "/data/anr/" + name + ".txt"; access.write(path, raw.getBytes("UTF-8"));
            Candidate c = new Candidate(category, path, FaultArchive.hash(raw), time); candidates.add(c); return c;
        }
        public Scan discover(FaultPolicy p) throws Exception {
            if (entered != null) { entered.countDown(); release.await(3, TimeUnit.SECONDS); }
            return new Scan(candidates, new JSONObject().put("state", "CHECKED"));
        }
        public Scan discover(FaultPolicy p, JSONObject continuation) throws Exception {
            lastContinuation = new JSONObject(continuation.toString());
            Scan result = discover(p); return new Scan(result.candidates, result.coverage, nextContinuation);
        }
        public Capture capture(Candidate c, File dir, FaultPolicy p) throws Exception {
            captures++; capturedOrder.add(c);
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
        await(5);
    }
    private void await(int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
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
    @Test public void newestUnseenSourceIsCapturedBeforeOldRetries() throws Exception {
        source.fail = true;
        for (int i = 0; i < 4; i++) source.add("old-" + i, "old-" + i);
        tick(); assertEquals(4, source.captures);
        source.add("ANR", "older-new", "older-new", 1);
        FaultSources.Candidate newest = source.add("ANR", "newest", "newest", clock.wall + 1000);
        int before = source.captures; tick();
        assertEquals(newest.id(), source.capturedOrder.get(before).id());
        assertTrue(source.captures <= before + policy.maxCandidates);
        assertTrue(source.capturedOrder.subList(before + 2, source.captures).stream().anyMatch(c -> c.path.contains("old-")));
    }
    @Test public void oneSlotBudgetRotatesAllSourcesAndStillRetriesAcrossRestarts() throws Exception {
        monitor.close(); policy = new FaultPolicy(32, 1, 3, 1024, 0, 64L * 1048576, 0, 1000, 1000, 0, 10000, 1000);
        monitor = new FaultMonitor(evidence, source, clock, policy); source.fail = true;
        for (int i = 0; i < 12; i++) source.add("ANR", "anr-" + i, "raw-" + i, clock.wall + i);
        for (String category : new String[]{"TOMBSTONE", "DROPBOX", "BOOT"}) source.add(category, category, category, 1);
        for (int i = 0; i < 8; i++) { tick(); reopen(); }
        java.util.Set<String> categories = new java.util.HashSet<String>(), ids = new java.util.HashSet<String>();
        for (FaultSources.Candidate c : source.capturedOrder) { categories.add(c.category); ids.add(c.id()); }
        assertEquals(4, categories.size()); assertTrue(source.captures > ids.size());
        assertTrue(source.captures <= 8);
    }
    @Test public void oldSightingsDoNotConsumeNewCategoryTurns() throws Exception {
        for (int i = 0; i < 4; i++) source.add("ANR", "old-" + i, "old", clock.wall + i);
        tick(); tick();
        source.add("ANR", "fresh-anr", "new", 1);
        FaultSources.Candidate box = source.add("DROPBOX", "fresh-crash", "crash", 1);
        int before = source.captures; tick();
        assertEquals(box.id(), source.capturedOrder.get(before).id());
    }
    @Test public void discoveryCursorPersistsWithoutAddingArchiveRootFiles() throws Exception {
        source.nextContinuation = new JSONObject().put("DROPBOX", "data_app_crash@123.txt"); tick(); reopen(); tick();
        assertEquals(source.nextContinuation.toString(), source.lastContinuation.toString());
        assertEquals(3, evidence.listFiles().length);
    }
    @Test public void pendingPagesAll128RecordsWithinTransportLimitWithoutHashingEvidence() throws Exception {
        for (int i = 0; i < 128; i++) {
            FaultSources.Candidate c = source.add("DROPBOX", "pending-" + i, "private-payload", clock.wall);
            File event = new File(evidence, c.id()); FaultArchive.directory(event);
            FaultArchive.jsonNew(new File(event, "event.json"), new JSONObject().put("source", c.json()));
            FaultArchive.jsonNew(new File(event, "state.json"), new JSONObject().put("phase", "CAPACITY_BLOCKED").put("capture", "PARTIAL"));
            File exports = new File(event, "exports"); FaultArchive.directory(exports);
            File output = new File(exports, "export-1"); FaultArchive.directory(output);
            FaultArchive.jsonNew(new File(output, "receipt.json"), new JSONObject().put("eventId", c.id()).put("state", "EXPORTED"));
            FaultArchive.jsonNew(new File(event, "archived.json"), new JSONObject().put("eventId", c.id()).put("state", "ARCHIVED"));
        }
        FaultExports.identities = file -> { throw new AssertionError("pending must not verify raw or ZIP contents"); };
        java.util.Set<String> ids = new java.util.HashSet<String>(); String cursor = ""; int pages = 0;
        while (true) {
            JSONObject page = FaultMonitor.readPending(evidence, 16, cursor); pages++;
            FaultArchive.jsonNew(new File(root, "pending-page-" + pages + ".json"), page);
            assertTrue(page.toString().getBytes("UTF-8").length <= 8000);
            assertTrue(new JSONObject().put("stdout", page.toString()).toString().length() < 16000);
            assertFalse(page.toString().contains("private-payload")); assertFalse(page.toString().contains("/data/"));
            for (int i = 0; i < page.getJSONArray("events").length(); i++) {
                JSONObject item = page.getJSONArray("events").getJSONObject(i);
                assertTrue(ids.add(item.getString("eventId")));
                assertEquals("ACK_RECORDED_UNVERIFIED", item.getString("archiveState"));
                assertEquals("RECEIPT_RECORDED_UNVERIFIED", item.getString("exportState"));
            }
            if (!page.getBoolean("hasMore")) break;
            cursor = page.getString("nextAfter"); assertTrue(pages < 9);
        }
        assertEquals(128, ids.size()); assertEquals(8, pages);
        assertEquals("METADATA_ONLY", FaultMonitor.readPending(evidence, 1, "").getString("verificationScope"));
    }
    @Test public void pendingKeepsDamagedRecordsVisibleAndRejectsInvalidPaging() throws Exception {
        FaultSources.Candidate c = source.add("traces", "raw"); tick();
        Files.write(new File(evidence, c.id() + "/state.json").toPath(), new byte[]{0});
        JSONObject result = FaultMonitor.readPending(evidence, 1, "");
        assertEquals("INDEX_CORRUPT", result.getJSONArray("events").getJSONObject(0).getString("phase"));
        assertFalse(result.getBoolean("hasMore"));
        for (int limit : new int[]{0, 17, Integer.MAX_VALUE}) {
            try { FaultMonitor.readPending(evidence, limit, ""); fail(); }
            catch (IllegalArgumentException expected) { assertEquals("INVALID_PENDING_LIMIT", expected.getMessage()); }
        }
        try { FaultMonitor.readPending(evidence, 1, "../escape"); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("INVALID_EVENT_CURSOR", expected.getMessage()); }
        assertEquals(0, FaultMonitor.readPending(evidence, 1, c.id()).getJSONArray("events").length());
    }
    @Test public void pendingShowsActualArchiveAndLastScanCapacityWithoutClaimingLiveVerification() throws Exception {
        monitor.close(); policy = policy(1, 3, 0); monitor = new FaultMonitor(evidence, source, clock, policy);
        FaultSources.Candidate c = source.add("traces", "raw"); tick(); tick();
        JSONObject full = FaultMonitor.readPending(evidence, 1, "");
        assertEquals("ACTIVE_EVENT_LIMIT", full.getJSONObject("capacity").getJSONArray("admissionBlockedBy").getString(0));
        FaultExports exports = new FaultExports(evidence, source, policy); JSONObject r = exports.exportEvent(c.id());
        exports.archiveEvent(c.id(), r.getString("sha256"), r.getLong("bytes"), r.getString("manifestSha256"));
        JSONObject pending = FaultMonitor.readPending(evidence, 1, "");
        assertEquals("ACK_RECORDED_UNVERIFIED", pending.getJSONArray("events").getJSONObject(0).getString("archiveState"));
        assertEquals(1, pending.getJSONObject("capacity").getInt("activeEvents"));
        assertEquals("LAST_SCAN_NOT_LIVE", pending.getJSONObject("capacity").getString("scope"));
        tick(); assertEquals(0, FaultMonitor.readPending(evidence, 1, "").getJSONObject("capacity").getInt("activeEvents"));
    }
    @Test public void default32ActiveLimitLeaves33rdSourceAndOriginalsUntouched() throws Exception {
        monitor.close(); policy = FaultPolicy.defaults(); monitor = new FaultMonitor(evidence, source, clock, policy);
        for (int i = 0; i < 33; i++) source.add("source-" + i, "original-" + i);
        for (int i = 0; i < 10; i++) {
            assertEquals(FaultMonitor.Submission.ACCEPTED, monitor.tick());
            await(30); assertEquals("NONE", monitor.lastFailure()); clock.add(61000);
        }
        assertEquals(32, source.captures);
        JSONObject capacity = FaultMonitor.readPending(evidence, 1, "").getJSONObject("capacity");
        assertEquals(32, capacity.getInt("activeEvents")); assertEquals(32, capacity.getInt("maxActiveEvents"));
        assertEquals(128, capacity.getInt("maxRetainedEvents")); assertEquals(67108864, capacity.getLong("maxArchiveBytes"));
        assertEquals("ACTIVE_EVENT_LIMIT", capacity.getJSONArray("admissionBlockedBy").getString(0));
        assertEquals(33, source.access.resolve("/data/anr").toFile().listFiles().length);
    }
    @Test public void totalDiskLimitPreservesExistingBytesAndDoesNotCreateNewEvent() throws Exception {
        monitor.close(); policy = FaultPolicy.defaults(); monitor = new FaultMonitor(evidence, source, clock, policy);
        File kept = new File(evidence, "retained-evidence.bin");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(kept, "rw")) {
            file.write(new byte[]{9, 8, 7}); file.setLength(policy.maxArchiveBytes - 65536);
        }
        FaultSources.Candidate c = source.add("traces", "new"); tick();
        assertFalse(new File(evidence, c.id()).exists()); assertEquals(0, source.captures);
        assertEquals(policy.maxArchiveBytes - 65536, kept.length());
        try (java.io.FileInputStream in = new java.io.FileInputStream(kept)) { assertEquals(9, in.read()); }
        JSONObject capacity = FaultMonitor.readPending(evidence, 1, "").getJSONObject("capacity");
        assertTrue(capacity.getJSONArray("admissionBlockedBy").toString().contains("ARCHIVE_BYTE_LIMIT"));
    }
}
