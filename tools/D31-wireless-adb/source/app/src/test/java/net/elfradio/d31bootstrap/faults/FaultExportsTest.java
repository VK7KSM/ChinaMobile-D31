package net.elfradio.d31bootstrap.faults;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class FaultExportsTest {
    private File root, event;
    private String id;
    private FaultExports exports;
    private boolean failAckCommit, failReceiptCommit;
    private final FaultPolicy policy = new FaultPolicy(1, 1, 3, 1024, 1, 8L * 1048576, 0, 1000, 1000, 1000, 10000, 1000);
    private final FaultSources sources = new FaultSources() {
        public Scan discover(FaultPolicy policy) { throw new AssertionError("must not read device sources"); }
        public Capture capture(Candidate c, File f, FaultPolicy policy) { throw new AssertionError("must not recapture"); }
        public JSONObject context() { throw new AssertionError("must not sample"); }
        public String bootKey() { throw new AssertionError("must not query boot"); }
        public void checkPrivateRoot(File file) throws IOException { FaultArchive.checked(file); }
        public void replace(File a, File b) throws IOException {
            if (failAckCommit && b.getName().equals("archived.json")
                    || failReceiptCommit && b.getName().equals("receipt.json")) throw new IOException("模拟提交前中断");
            FaultTestFiles.replace(a, b);
        }
    };
    @Before public void setup() throws Exception {
        FaultExports.identities = FaultTestFiles::identity;
        root = Files.createTempDirectory("fault-export-").toFile();
        FaultSources.Candidate candidate = new FaultSources.Candidate("ANR", "/data/anr/traces.txt", FaultArchive.hash("source"), 1);
        id = candidate.id(); event = new File(root, id); FaultArchive.directory(event);
        FaultArchive.jsonNew(new File(event, "event.json"), new JSONObject().put("source", candidate.json())
                .put("detectedAtMs", 1).put("preWindowState", "MISSING").put("preWindow", new JSONArray()));
        FaultArchive.jsonNew(new File(event, "state.json"), new JSONObject().put("phase", "PARTIAL")
                .put("attempts", 1).put("capture", "COMPLETE").put("postWindow", "MISSING_DEADLINE"));
        File attempt = new File(event, "attempt-1"); FaultArchive.directory(attempt);
        byte[] raw = new byte[]{0, 1, 2, 13, 10, (byte) 255}; FaultArchive.writeNew(new File(attempt, "fault-source.bin"), raw);
        JSONObject item = new JSONObject().put("state", "COMPLETE").put("artifact", "fault-source.bin")
                .put("before", new JSONObject().put("size", raw.length))
                .put("storedBytes", raw.length).put("storedSha256", FaultArchive.hash(raw));
        FaultArchive.jsonNew(new File(attempt, "report.json"), new JSONObject().put("diagnostic", new JSONObject()
                .put("state", "COMPLETE").put("items", new JSONArray().put(item))));
        exports = new FaultExports(root, sources, policy);
    }
    private JSONObject archive(JSONObject receipt) throws Exception {
        return exports.archiveEvent(id, receipt.getString("sha256"), receipt.getLong("bytes"), receipt.getString("manifestSha256"));
    }
    private interface Action { void run() throws Exception; }
    private void rejects(String reason, Action action) throws Exception {
        try { action.run(); fail("expected " + reason); }
        catch (IOException expected) { assertEquals(reason, expected.getMessage()); }
    }
    @Test public void exportContainsBinaryOriginalAndManifestWithMatchingHashes() throws Exception {
        JSONObject receipt = exports.exportEvent(id);
        File bundle = new File(receipt.getString("path"));
        assertEquals(receipt.getString("sha256"), FaultExports.digest(bundle, null, FaultExports.MAX_PACKAGE_BYTES).sha256);
        try (ZipFile zip = new ZipFile(bundle)) {
            assertEquals(ZipEntry.STORED, zip.getEntry("evidence/attempt-1/fault-source.bin").getMethod());
            byte[] raw; try (InputStream in = zip.getInputStream(zip.getEntry("evidence/attempt-1/fault-source.bin"))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); int b; while ((b = in.read()) != -1) out.write(b); raw = out.toByteArray();
            }
            assertArrayEquals(new byte[]{0, 1, 2, 13, 10, (byte) 255}, raw);
            assertEquals(receipt.getInt("files") + 1, zip.size());
        }
        JSONObject manifest = FaultArchive.read(new File(bundle.getParentFile(), "manifest.json"));
        assertEquals(1, manifest.getInt("rawFiles")); assertTrue(manifest.getJSONArray("gaps").length() >= 2);
        assertEquals("ANR", manifest.getString("category")); assertFalse(FaultExports.isArchived(event));
        assertEquals(1, receipt.getInt("completeRawFiles")); assertEquals("COMPLETE", receipt.getString("captureState"));
    }
    @Test public void explicitVerifiedAckReleasesOnlySlotAndPreservesAllBytes() throws Exception {
        JSONObject receipt = exports.exportEvent(id); long before = new FaultArchive(root, sources).bytes();
        JSONObject ack = archive(receipt);
        assertTrue(ack.getBoolean("activeSlotReleased")); assertEquals(0, ack.getInt("releasedBytes"));
        assertFalse(ack.getBoolean("originalsDeleted")); assertTrue(FaultExports.isArchived(event));
        assertTrue(new FaultArchive(root, sources).bytes() > before);
        assertTrue(new File(event, "attempt-1/fault-source.bin").exists());
    }
    @Test public void retriesReuseFrozenBundleAndAck() throws Exception {
        JSONObject first = exports.exportEvent(id); byte[] original = Files.readAllBytes(new File(first.getString("path")).toPath());
        assertEquals(first.toString(), exports.exportEvent(id).toString());
        JSONObject ack = archive(first); assertEquals(ack.toString(), archive(first).toString());
        assertArrayEquals(original, Files.readAllBytes(new File(first.getString("path")).toPath()));
        assertFalse(new File(event, "exports/export-2").exists());
    }
    @Test public void interruptedAckKeepsOriginalsAndRetriesWithoutFalseSlotRelease() throws Exception {
        JSONObject receipt = exports.exportEvent(id);
        byte[] raw = Files.readAllBytes(new File(event, "attempt-1/fault-source.bin").toPath());
        failAckCommit = true;
        rejects("模拟提交前中断", () -> archive(receipt));
        assertFalse(new File(event, "archived.json").exists()); assertFalse(FaultExports.isArchived(event));
        assertTrue(new File(event, "archived.json.next").exists());
        failAckCommit = false; exports = new FaultExports(root, sources, policy);
        assertTrue(archive(receipt).getBoolean("activeSlotReleased"));
        assertArrayEquals(raw, Files.readAllBytes(new File(event, "attempt-1/fault-source.bin").toPath()));
        assertEquals(receipt.toString(), exports.exportEvent(id).toString());
    }
    @Test public void interruptedReceiptCannotBecomeCommittedAndRetryKeepsFirstPackage() throws Exception {
        failReceiptCommit = true;
        rejects("模拟提交前中断", () -> exports.exportEvent(id));
        File first = new File(event, "exports/export-1/bundle.zip");
        byte[] raw = Files.readAllBytes(first.toPath());
        assertFalse(new File(first.getParentFile(), "receipt.json").exists());
        assertEquals("INCOMPLETE_EXPORT", FaultExports.summary(event).getString("state"));
        failReceiptCommit = false; exports = new FaultExports(root, sources, policy);
        JSONObject receipt = exports.exportEvent(id); assertEquals(2, receipt.getInt("exportNumber"));
        assertArrayEquals(raw, Files.readAllBytes(first.toPath()));
        assertTrue(archive(receipt).getBoolean("activeSlotReleased"));
    }
    @Test public void unreadableReportIsPackagedWithGapAndMayBeExplicitlyArchived() throws Exception {
        File report = new File(event, "attempt-1/report.json"); byte[] partial = new byte[]{123,34,100};
        Files.write(report.toPath(), partial);
        JSONObject receipt = exports.exportEvent(id);
        assertEquals(0, receipt.getInt("completeRawFiles"));
        JSONObject manifest = FaultArchive.read(new File(new File(receipt.getString("path")).getParentFile(), "manifest.json"));
        assertTrue(manifest.getJSONArray("gaps").toString().contains("REPORT_UNREADABLE"));
        assertTrue(archive(receipt).getBoolean("activeSlotReleased"));
        assertArrayEquals(partial, Files.readAllBytes(report.toPath()));
    }
    @Test public void absentReceiptCannotArchive() throws Exception {
        rejects("EXPORT_RECEIPT_REQUIRED", () -> exports.archiveEvent(id, FaultArchive.hash("x"), 1, FaultArchive.hash("y")));
        assertFalse(new File(event, "archived.json").exists());
    }
    @Test public void wrongHashLengthOrManifestCannotArchive() throws Exception {
        JSONObject r = exports.exportEvent(id);
        rejects("EXPORT_ACK_MISMATCH", () -> exports.archiveEvent(id, FaultArchive.hash("wrong"), r.getLong("bytes"), r.getString("manifestSha256")));
        rejects("EXPORT_ACK_MISMATCH", () -> exports.archiveEvent(id, r.getString("sha256"), r.getLong("bytes") + 1, r.getString("manifestSha256")));
        rejects("EXPORT_ACK_MISMATCH", () -> exports.archiveEvent(id, r.getString("sha256"), r.getLong("bytes"), FaultArchive.hash("wrong")));
        assertFalse(FaultExports.isArchived(event));
    }
    @Test public void alteredOriginalAfterExportRejectsAck() throws Exception {
        JSONObject r = exports.exportEvent(id); Files.write(new File(event, "attempt-1/fault-source.bin").toPath(), new byte[]{9});
        rejects("EXPORT_SOURCE_CHANGED", () -> archive(r)); assertFalse(FaultExports.isArchived(event));
    }
    @Test public void corruptBundleCannotRetainArchivedSlotRelease() throws Exception {
        JSONObject r = exports.exportEvent(id); archive(r);
        File zip = new File(r.getString("path")); byte[] bytes = Files.readAllBytes(zip.toPath()); bytes[40] ^= 1; Files.write(zip.toPath(), bytes);
        assertFalse(FaultExports.isArchived(event)); rejects("EXPORT_HASH_MISMATCH", () -> archive(r));
    }
    @Test public void incompleteExportIsNeverAcceptedAndRetryIsBounded() throws Exception {
        File path = new File(event, "exports"); FaultArchive.directory(path);
        File first = new File(path, "export-1"); FaultArchive.directory(first); FaultArchive.writeNew(new File(first, "bundle.zip"), new byte[]{1});
        assertFalse(FaultExports.isArchived(event));
        JSONObject r = exports.exportEvent(id); assertEquals(2, r.getInt("exportNumber"));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(first, "bundle.zip").toPath()));
        Files.delete(new File(path, "export-2/receipt.json").toPath());
        rejects("EXPORT_ATTEMPTS_EXHAUSTED", () -> exports.exportEvent(id));
    }
    @Test public void diskQuotaIncludesFailedExportsAndOriginals() throws Exception {
        File exportsRoot = new File(event, "exports"); FaultArchive.directory(exportsRoot);
        File failed = new File(exportsRoot, "export-1"); FaultArchive.directory(failed);
        FaultArchive.writeNew(new File(failed, "bundle.zip"), new byte[1024 * 1024]);
        FaultPolicy small = new FaultPolicy(1, 1, 3, 1024, 1, 1048576, 0, 1000, 1000, 1000, 10000, 1000);
        rejects("EXPORT_CAPACITY_LIMIT", () -> new FaultExports(root, sources, small).exportEvent(id));
        assertFalse(new File(exportsRoot, "export-2").exists()); assertFalse(new File(event, "export-seal.json").exists());
    }
    @Test public void reportCannotInjectTraversalIntoPackage() throws Exception {
        File report = new File(event, "attempt-1/report.json"); JSONObject json = FaultArchive.read(report);
        json.getJSONObject("diagnostic").getJSONArray("items").getJSONObject(0).put("artifact", "../../secret");
        Files.write(report.toPath(), json.toString().getBytes("UTF-8"));
        rejects("REPORT_ARTIFACT_PATH_REJECTED", () -> exports.exportEvent(id));
        assertFalse(new File(event, "exports/export-1/bundle.zip").exists());
    }
    @Test public void unexpectedDirectoryCannotBeRecursivelyPacked() throws Exception {
        Files.createDirectory(new File(event, "attempt-1/escape").toPath());
        rejects("EXPORT_FILE_LIMIT", () -> exports.exportEvent(id));
    }
    @Test public void symlinkCannotBePacked() throws Exception {
        File target = new File(root, "outside.bin"); Files.write(target.toPath(), new byte[]{9});
        File link = new File(event, "attempt-1/linked.bin");
        try { Files.createSymbolicLink(link.toPath(), target.toPath()); }
        catch (IOException | UnsupportedOperationException noPermission) { org.junit.Assume.assumeNoException(noPermission); }
        rejects("ARCHIVE_LINK_REJECTED", () -> exports.exportEvent(id));
    }
    @Test public void missingRawAndTruncatedReceiptsRemainExplicitGaps() throws Exception {
        Files.delete(new File(event, "attempt-1/fault-source.bin").toPath());
        File report = new File(event, "attempt-1/report.json"); JSONObject json = FaultArchive.read(report);
        json.getJSONObject("diagnostic").put("state", "PARTIAL").getJSONArray("items").getJSONObject(0).put("state", "TRUNCATED");
        Files.write(report.toPath(), json.toString().getBytes("UTF-8"));
        JSONObject r = exports.exportEvent(id); JSONObject manifest = FaultArchive.read(new File(new File(r.getString("path")).getParentFile(), "manifest.json"));
        String gaps = manifest.getJSONArray("gaps").toString();
        assertTrue(gaps.contains("MISSING_RAW_ARTIFACT")); assertTrue(gaps.contains("TRUNCATED")); assertTrue(gaps.contains("NO_RAW_ARTIFACT_SAVED"));
        assertEquals("SEE_MANIFEST_GAPS", r.getString("sourceCompleteness"));
        assertEquals(0, r.getInt("completeRawFiles"));
    }
    @Test public void activeEventCannotBeSealedMidCapture() throws Exception {
        Files.write(new File(event, "state.json").toPath(), "{\"phase\":\"AWAITING_POST\"}".getBytes("UTF-8"));
        rejects("EVENT_NOT_TERMINAL", () -> exports.exportEvent(id)); assertFalse(new File(event, "export-seal.json").exists());
    }
    @Test public void collectorLockBlocksExportWithoutPartialWrites() throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(new File(root, "collector.lock"), "rw");
             java.nio.channels.FileLock lock = file.getChannel().lock()) {
            rejects("COLLECTOR_BUSY", () -> exports.exportEvent(id)); assertFalse(new File(event, "exports").exists());
        }
    }
    @Test public void externalFileRemainsUntouched() throws Exception {
        File external = new File(root, "outside.bin"); Files.write(external.toPath(), new byte[]{4, 5});
        JSONObject r = exports.exportEvent(id); archive(r);
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(external.toPath()));
        try (ZipFile zip = new ZipFile(r.getString("path"))) { assertNull(zip.getEntry("outside.bin")); }
    }
    @Test public void unchangedExportUsesStatCacheAndDetectsChangedBundle() throws Exception {
        JSONObject r = exports.exportEvent(id);
        assertEquals("FULL_HASH", FaultExports.summary(event).getJSONObject("verification").getString("mode"));
        assertEquals("STAT_CACHE", FaultExports.summary(event).getJSONObject("verification").getString("mode"));
        File bundle = new File(r.getString("path")); byte[] bytes = Files.readAllBytes(bundle.toPath()); bytes[40] ^= 1;
        long modified = bundle.lastModified(); Files.write(bundle.toPath(), bytes);
        Files.setLastModifiedTime(bundle.toPath(), java.nio.file.attribute.FileTime.fromMillis(modified + 2000));
        JSONObject result = FaultExports.summary(event);
        assertEquals("EXPORT_CORRUPT", result.getString("state"));
        assertEquals("FULL_HASH", result.getJSONObject("verification").getString("mode"));
        assertEquals("STAT_CACHE", FaultExports.summary(event).getJSONObject("verification").getString("mode"));
    }
    @Test public void archiveAndReceiptIdentityChangesInvalidateCache() throws Exception {
        JSONObject r = exports.exportEvent(id); FaultExports.summary(event); archive(r);
        assertEquals("FULL_HASH", FaultExports.summary(event).getJSONObject("verification").getString("mode"));
        File receipt = new File(new File(r.getString("path")).getParentFile(), "receipt.json");
        byte[] bytes = Files.readAllBytes(receipt.toPath());
        File replacement = new File(receipt.getParentFile(), "replacement"); Files.write(replacement.toPath(), bytes);
        FaultTestFiles.replace(replacement, receipt);
        assertEquals("FULL_HASH", FaultExports.summary(event).getJSONObject("verification").getString("mode"));
    }
    @Test public void explicitArchiveStillFullyChecksEvenWhenStatCacheMatches() throws Exception {
        JSONObject r = exports.exportEvent(id);
        FaultExports.FileIdentity real = FaultExports.identities;
        FaultExports.identities = file -> file.getAbsolutePath();
        try {
            FaultExports.summary(event);
            File bundle = new File(r.getString("path")); byte[] bytes = Files.readAllBytes(bundle.toPath()); bytes[40] ^= 1; Files.write(bundle.toPath(), bytes);
            assertEquals("STAT_CACHE", FaultExports.summary(event).getJSONObject("verification").getString("mode"));
            rejects("EXPORT_HASH_MISMATCH", () -> archive(r));
            rejects("EXPORT_HASH_MISMATCH", () -> exports.exportEvent(id));
        } finally { FaultExports.identities = real; }
    }
    @Test public void rawQueryReusesStatIdentityButExportRechecksBytes() throws Exception {
        assertEquals("FULL_HASH", FaultExports.rawSummary(event).getJSONArray("items").getJSONObject(0).getString("verificationMode"));
        assertEquals("STAT_CACHE", FaultExports.rawSummary(event).getJSONArray("items").getJSONObject(0).getString("verificationMode"));
        File raw = new File(event, "attempt-1/fault-source.bin"); long modified = raw.lastModified();
        Files.write(raw.toPath(), new byte[]{8, 8, 8, 8, 8, 8});
        Files.setLastModifiedTime(raw.toPath(), java.nio.file.attribute.FileTime.fromMillis(modified + 2000));
        JSONObject evidence = FaultExports.rawSummary(event);
        assertEquals("FULL_HASH", evidence.getJSONArray("items").getJSONObject(0).getString("verificationMode"));
        assertEquals(0, evidence.getInt("completeRawFiles"));
        assertEquals(0, exports.exportEvent(id).getInt("completeRawFiles"));
    }
}
