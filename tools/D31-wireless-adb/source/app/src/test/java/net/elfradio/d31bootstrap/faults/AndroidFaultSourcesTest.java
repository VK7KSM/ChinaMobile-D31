package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess;

public class AndroidFaultSourcesTest {
    private FaultTestFiles.Access access;
    private FaultTestFiles.Clock clock;
    private AndroidFaultSources sources;
    @Before public void setup() throws Exception {
        File root = Files.createTempDirectory("android-fault-adapter-").toFile();
        access = new FaultTestFiles.Access(root); clock = new FaultTestFiles.Clock(); sources = new AndroidFaultSources(access, clock, access);
    }
    @Test public void actualAndroid6FileNamesAreSelectedAndOthersExcluded() throws Exception {
        access.write("/data/anr/traces.txt", new byte[]{1});
        access.write("/data/tombstones/tombstone_03", new byte[]{2});
        access.write("/data/system/dropbox/data_app_anr@1789000000000.txt.gz", new byte[]{3});
        access.write("/data/system/dropbox/SYSTEM_TOMBSTONE@1789000000001.txt", new byte[]{4});
        access.write("/data/system/dropbox/unrelated_private_tag@1789000000002.txt", new byte[]{5});
        access.write("/data/local/d31-remote/supervisor-launch-12345.log", new byte[]{6});
        access.write("/data/local/d31-remote/state.json", new byte[]{7});
        FaultSources.Scan scan = sources.discover(FaultPolicy.defaults());
        assertEquals(5, scan.candidates.size());
        for (FaultSources.Candidate c : scan.candidates) assertFalse(c.path.contains("private_tag"));
    }
    @Test public void deniedAndAbsentSourcesAreNotReportedAsNoFaults() throws Exception {
        FaultSources.Scan missing = sources.discover(FaultPolicy.defaults());
        assertEquals("NOT_FOUND", missing.coverage.getJSONArray("sources").getJSONObject(0).getString("reason"));
        access.deny = true;
        FaultSources.Scan denied = sources.discover(FaultPolicy.defaults());
        assertEquals("ACCESS_DENIED", denied.coverage.getJSONArray("sources").getJSONObject(0).getString("reason"));
        assertEquals(0, denied.candidates.size());
    }
    @Test public void sameSizeSameTimestampChangeIsDetectedWithinFingerprintPrefix() throws Exception {
        String path = "/data/anr/traces.txt"; access.write(path, new byte[]{1, 2});
        FileTime time = Files.getLastModifiedTime(access.resolve(path));
        String first = sources.discover(FaultPolicy.defaults()).candidates.get(0).id();
        access.write(path, new byte[]{3, 4}); Files.setLastModifiedTime(access.resolve(path), time);
        assertNotEquals(first, sources.discover(FaultPolicy.defaults()).candidates.get(0).id());
    }
    @Test public void unchangedSourceHasStableIdentityAcrossPolls() throws Exception {
        access.write("/data/anr/traces.txt", new byte[]{1});
        String first = sources.discover(FaultPolicy.defaults()).candidates.get(0).id(); clock.add(60000);
        assertEquals(first, sources.discover(FaultPolicy.defaults()).candidates.get(0).id());
    }
    @Test public void changedSourceIsRejectedBeforeArtifactStore() throws Exception {
        String path = "/data/anr/traces.txt"; access.write(path, new byte[]{1});
        FaultSources.Candidate candidate = sources.discover(FaultPolicy.defaults()).candidates.get(0);
        access.write(path, new byte[]{2, 3});
        File output = Files.createTempDirectory("changed-fault-").toFile();
        FaultSources.Capture capture = sources.capture(candidate, output, FaultPolicy.defaults());
        assertFalse(capture.retryable); assertEquals("SOURCE_CHANGED", capture.diagnostic.getString("reason"));
        assertEquals(0, output.list().length);
    }
    @Test public void lostMarkersKeepBytesButNeverClaimOriginalCrashWasCaptured() throws Exception {
        for (String suffix : new String[]{".lost", ".lost.gz"}) {
            String path = "/data/system/dropbox/data_app_crash@123" + suffix;
            access.write(path, new byte[]{1, 2, 3});
            FaultSources.Candidate candidate = null;
            for (FaultSources.Candidate c : sources.discover(FaultPolicy.defaults()).candidates) if (c.path.equals(path)) candidate = c;
            assertNotNull(candidate); File output = Files.createTempDirectory("lost-marker-").toFile();
            FaultSources.Capture capture = sources.capture(candidate, output, FaultPolicy.defaults(), FaultTestFiles.store(output));
            assertEquals("PARTIAL", capture.diagnostic.getString("state"));
            assertEquals("DROPBOX_ENTRY_LOST", capture.diagnostic.getString("reason"));
            assertEquals(0, capture.diagnostic.getJSONObject("counts").getInt("complete"));
            assertEquals("UNAVAILABLE", capture.diagnostic.getJSONArray("items").getJSONObject(0).getString("state"));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(output, "fault-source.bin").toPath()));
        }
    }
    @Test public void sourceReplacementBetweenSignatureAndCaptureCannotLeaveCompleteItem() throws Exception {
        String path = "/data/anr/traces.txt"; access.write(path, new byte[]{1, 2});
        FaultSources.Candidate candidate = sources.discover(FaultPolicy.defaults()).candidates.get(0);
        final FileTime timestamp = Files.getLastModifiedTime(access.resolve(path));
        CollectionAccess changing = new CollectionAccess() {
            int opens;
            public Stat lstat(String name) throws IOException { return access.lstat(name); }
            public String readLink(String name) throws IOException { return access.readLink(name); }
            public Listing list(String name, Stat expected, int max, long bytes, long timeout) throws IOException {
                return access.list(name, expected, max, bytes, timeout);
            }
            public Handle openRegular(String name, Stat expected) throws IOException {
                Handle input = access.openRegular(name, expected); final boolean change = ++opens == 1;
                return new Handle() {
                    public Stat stat() throws IOException { return input.stat(); }
                    public int read(byte[] bytes, int offset, int length) throws IOException { return input.read(bytes, offset, length); }
                    public void close() throws IOException {
                        input.close();
                        if (change) { access.write(path, new byte[]{3, 4}); Files.setLastModifiedTime(access.resolve(path), timestamp); }
                    }
                };
            }
        };
        File output = Files.createTempDirectory("changed-after-signature-").toFile();
        FaultSources.Capture capture = new AndroidFaultSources(changing, clock, access)
                .capture(candidate, output, FaultPolicy.defaults(), FaultTestFiles.store(output));
        assertEquals("SOURCE_CHANGED", capture.diagnostic.getString("reason")); assertFalse(capture.retryable);
        assertEquals("UNSTABLE", capture.diagnostic.getJSONArray("items").getJSONObject(0).getString("state"));
        assertEquals(0, capture.diagnostic.getJSONObject("counts").getInt("complete"));
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(new File(output, "fault-source.bin").toPath()));
    }
    @Test public void enumerationOverflowIsExplicit() throws Exception {
        for (int i = 0; i < 65; i++) access.write("/data/tombstones/tombstone_" + String.format(java.util.Locale.ROOT, "%02d", i), new byte[]{1});
        FaultSources.Scan scan = sources.discover(FaultPolicy.defaults());
        assertTrue(scan.candidates.size() <= 64);
        assertEquals(65, scan.coverage.getJSONArray("sources").getJSONObject(1).getInt("enumerated"));
        assertTrue(scan.coverage.getJSONArray("sources").getJSONObject(1).getBoolean("enumerationComplete"));
        assertEquals("PARTIAL", scan.coverage.getJSONArray("sources").getJSONObject(1).getString("state"));
    }
    @Test public void unstableSourceIsNotFrozenUnderFalseIdentity() throws Exception {
        access.write("/data/anr/traces.txt", new byte[]{1}); access.unstable = true;
        FaultSources.Scan scan = sources.discover(FaultPolicy.defaults());
        assertEquals(0, scan.candidates.size());
        assertEquals(1, scan.coverage.getJSONArray("sources").getJSONObject(0).getInt("unreadable"));
    }
    @Test public void contextUsesOnlyFixedProcSourcesAndHashesBootIdentifier() throws Exception {
        access.write("/proc/meminfo", "MemTotal: 10 kB\n".getBytes("US-ASCII"));
        access.write("/proc/loadavg", "0 0 0 1/3 5\n".getBytes("US-ASCII"));
        String boot = "00000000-1111-2222-3333-444444444444";
        access.write("/proc/sys/kernel/random/boot_id", (boot + "\n").getBytes("US-ASCII"));
        JSONObject context = sources.context();
        assertEquals("CAPTURED", context.getJSONObject("meminfo").getString("state"));
        assertEquals("NOT_COLLECTED", context.getString("logcat"));
        assertEquals(FaultArchive.hash(boot), sources.bootKey()); assertFalse(context.toString().contains(boot));
    }
    @Test public void unavailableContextCannotInventData() throws Exception {
        JSONObject result = sources.context();
        assertEquals("UNAVAILABLE", result.getJSONObject("meminfo").getString("state"));
        assertFalse(result.getJSONObject("meminfo").has("text"));
    }
    @Test public void tagAndPathValidationCannotIntroduceShellOrArbitraryInput() {
        assertFalse(AndroidFaultSources.accepts(2, "data_app_anr@1.txt;id"));
        assertFalse(AndroidFaultSources.accepts(2, "../data_app_anr@1.txt"));
        assertFalse(AndroidFaultSources.accepts(3, "identity.json"));
        assertTrue(AndroidFaultSources.accepts(2, "data_app_crash@123.lost"));
        try { new FaultSources.Candidate("ANR", "/data/anr/../account", FaultArchive.hash("x"), 0); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("INVALID_FAULT_SOURCE", expected.getMessage()); }
    }
    @Test public void mixedDirectoryBeyond64NamesFindsLatestAndPagesAllBacklogAcrossRestarts() throws Exception {
        for (int i = 0; i < 180; i++) access.write("/data/system/dropbox/aaa_private_" + i + ".txt", new byte[]{1});
        java.util.Set<String> expected = new java.util.HashSet<String>();
        String latest = "";
        for (int i = 0; i < 160; i++) {
            String path = "/data/system/dropbox/data_app_crash@" + (10000 + i) + ".txt.gz";
            access.write(path, new byte[]{(byte) i});
            Files.setLastModifiedTime(access.resolve(path), FileTime.fromMillis(1600000000000L + i * 1000));
            expected.add(path); latest = path;
        }
        JSONObject continuation = new JSONObject(); java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int cycle = 0; cycle < 6; cycle++) {
            sources = new AndroidFaultSources(access, clock, access);
            FaultSources.Scan scan = sources.discover(FaultPolicy.defaults(), continuation);
            assertTrue(scan.candidates.size() <= 64);
            assertEquals(latest, scan.candidates.get(0).path);
            JSONObject coverage = scan.coverage.getJSONArray("sources").getJSONObject(2);
            assertEquals(340, coverage.getInt("enumerated")); assertEquals(160, coverage.getInt("matched"));
            assertTrue(coverage.getBoolean("enumerationComplete"));
            for (FaultSources.Candidate candidate : scan.candidates) seen.add(candidate.path);
            continuation = new JSONObject(scan.continuation.toString()); clock.add(60000);
        }
        assertEquals(expected, seen);
        String newest = "/data/system/dropbox/data_app_crash@99999.txt";
        access.write(newest, new byte[]{7});
        assertEquals(newest, sources.discover(FaultPolicy.defaults(), continuation).candidates.get(0).path);
    }
    @Test public void oneSlowDirectoryCannotConsumeOtherSourceTimeShares() throws Exception {
        access.write("/data/anr/traces.txt", new byte[]{1});
        access.write("/data/tombstones/tombstone_00", new byte[]{2});
        access.write("/data/system/dropbox/data_app_crash@123.txt", new byte[]{3});
        access.write("/data/local/d31-remote/supervisor-launch-test.log", new byte[]{4});
        FaultDirectoryWalker slow = (path, stat, c, deadline, visitor) -> {
            if (path.equals("/data/anr")) { clock.add(deadline - clock.elapsed); return "SCAN_TIME_LIMIT"; }
            return access.walk(path, stat, c, deadline, visitor);
        };
        FaultSources.Scan result = new AndroidFaultSources(access, clock, slow).discover(FaultPolicy.defaults());
        assertEquals(3, result.candidates.size());
        assertEquals("SCAN_TIME_LIMIT", result.coverage.getJSONArray("sources").getJSONObject(0).getString("reason"));
        for (int i = 1; i < 4; i++) assertEquals("CHECKED", result.coverage.getJSONArray("sources").getJSONObject(i).getString("state"));
    }
    @Test public void partialEnumerationNeverClaimsGloballyNewestOrCompleteCoverage() throws Exception {
        access.write("/data/system/dropbox/data_app_crash@123.txt", new byte[]{1}); access.partialList = true;
        JSONObject coverage = sources.discover(FaultPolicy.defaults()).coverage.getJSONArray("sources").getJSONObject(2);
        assertFalse(coverage.getBoolean("enumerationComplete")); assertEquals("PARTIAL", coverage.getString("state"));
        assertEquals("SCAN_BYTE_LIMIT", coverage.getString("reason"));
    }
    @Test public void selectionDoesNotDependOnDirectoryEnumerationOrder() throws Exception {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            String name = "data_app_crash@" + (1000 + i) + ".txt"; names.add(name);
            access.write("/data/system/dropbox/" + name, new byte[]{(byte) i});
            Files.setLastModifiedTime(access.resolve("/data/system/dropbox/" + name), FileTime.fromMillis(1600000000000L + i * 1000));
        }
        FaultDirectoryWalker ordered = (path, stat, c, deadline, visitor) -> {
            for (String name : names) visitor.name(name, child -> access.lstat(path + "/" + child)); return "ENUMERATION_FINISHED";
        };
        FaultSources.Scan first = new AndroidFaultSources(access, clock, ordered).discover(FaultPolicy.defaults());
        java.util.Collections.reverse(names);
        FaultSources.Scan second = new AndroidFaultSources(access, clock, ordered).discover(FaultPolicy.defaults());
        assertEquals(first.candidates.size(), second.candidates.size());
        for (int i = 0; i < first.candidates.size(); i++) assertEquals(first.candidates.get(i).id(), second.candidates.get(i).id());
        assertEquals(first.continuation.toString(), second.continuation.toString());
    }
    @Test public void productionFindRecordParserRequiresActualPinnedPrefixAndSingleChild() throws Exception {
        String root = "/proc/123/fd/7/.";
        assertEquals("data_app_crash@1.txt", FaultDirectoryWalker.Android.child(root, (root + "/data_app_crash@1.txt").getBytes("UTF-8")));
        for (String record : new String[]{"/data/system/dropbox/data_app_crash@1.txt", "/proc/123/fd/70/./data_app_crash@1.txt",
                root + "/../data_app_crash@1.txt", root + "//data_app_crash@1.txt", root + "/..", root + "/"}) {
            try { FaultDirectoryWalker.Android.child(root, record.getBytes("UTF-8")); fail(record); }
            catch (java.io.IOException expected) { }
        }
        try { FaultDirectoryWalker.Android.child(root, new byte[]{(byte) 0xff}); fail(); }
        catch (java.io.IOException expected) { }
    }
    @Test public void readOnlyDiscoveryProbeIsCompactAndDoesNotCaptureOrExposeNames() throws Exception {
        for (int i = 0; i < 100; i++) access.write("/data/system/dropbox/data_app_crash@" + (1000 + i) + ".txt", new byte[]{1});
        JSONObject probe = sources.discoveryProbe();
        assertEquals("FAULT_DISCOVERY_PROBE", probe.getString("kind"));
        assertFalse(probe.getBoolean("archiveModified")); assertEquals(4, probe.getJSONArray("sample").length());
        assertTrue(probe.toString().getBytes("UTF-8").length <= 8000);
        assertFalse(probe.toString().contains("data_app_crash@")); assertFalse(probe.toString().contains("/data/"));
        assertEquals(100, probe.getJSONObject("coverage").getJSONArray("sources").getJSONObject(2).getInt("matched"));
        assertTrue(probe.getJSONObject("coverage").getJSONArray("sources").getJSONObject(2).getBoolean("enumerationComplete"));
        assertEquals(100, access.resolve("/data/system/dropbox").toFile().listFiles().length);
        assertFalse(access.resolve("/data/local/d31-remote/faults").toFile().exists());
    }
    @Test public void androidLstatPermissionsMatchRawFstatDirectoryMode() {
        for (int permissions : new int[]{0700, 0751, 0771, 01770, 02770, 04700}) {
            net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat expected =
                    new net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat(
                            "directory", 253, 12345, 4096, 1789000000, 1789000001, permissions, 1000, 1000);
            assertTrue(FaultDirectoryWalker.Android.sameDirectory(expected, 253, 12345, 4096,
                    1789000000, 1789000001, 0040000 | permissions, 1000, 1000));
            assertFalse(expected.mode == (0040000 | permissions));
        }
    }
    @Test public void rawFstatMappingRejectsEveryDirectoryIdentityChangeIncludingSpecialModeBits() {
        net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat expected =
                new net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat(
                        "directory", 253, 12345, 4096, 1789000000, 1789000001, 02770, 1000, 1001);
        long[] original = {253, 12345, 4096, 1789000000, 1789000001, 0042770, 1000, 1001};
        for (int field = 0; field < original.length; field++) {
            long[] changed = original.clone(); changed[field]++;
            assertFalse("field " + field, FaultDirectoryWalker.Android.sameDirectory(expected,
                    changed[0], changed[1], changed[2], changed[3], changed[4], (int) changed[5], (int) changed[6], (int) changed[7]));
        }
        assertFalse(FaultDirectoryWalker.Android.sameDirectory(expected, 253, 12345, 4096, 1789000000, 1789000001, 0040770, 1000, 1001));
        for (int type : new int[]{0, 0100000, 0120000, 0060000, 0020000, 0010000, 0140000})
            assertFalse(FaultDirectoryWalker.Android.sameDirectory(expected, 253, 12345, 4096, 1789000000, 1789000001, type | 02770, 1000, 1001));
        net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat wrongExpectedType =
                new net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat(
                        "file", 253, 12345, 4096, 1789000000, 1789000001, 02770, 1000, 1001);
        assertFalse(FaultDirectoryWalker.Android.sameDirectory(wrongExpectedType, 253, 12345, 4096, 1789000000, 1789000001, 0042770, 1000, 1001));
    }
    @Test public void fstatUnsignedOwnersAndNonnegativeSizeFollowSharedLstatMapping() {
        net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat expected =
                new net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat(
                        "directory", 253, 12345, 0, 1789000000, 1789000001, 0700, 4294967295L, 2147483648L);
        assertTrue(FaultDirectoryWalker.Android.sameDirectory(expected, 253, 12345, -1,
                1789000000, 1789000001, 0040700, -1, Integer.MIN_VALUE));
        assertFalse(FaultDirectoryWalker.Android.sameDirectory(expected, 253, 12345, -1,
                1789000000, 1789000001, 0040700, -2, Integer.MIN_VALUE));
    }
    @Test public void mixed828FileDirectoryUsesScopedMetadataRatherThanFullPathForEveryMatch() throws Exception {
        for (int i = 0; i < 228; i++) access.write("/data/system/dropbox/ignored-tag@" + i + ".txt", new byte[]{0});
        String latest = "";
        for (int i = 0; i < 600; i++) {
            latest = "/data/system/dropbox/data_app_crash@" + (10000 + i) + ".txt";
            access.write(latest, new byte[]{(byte) i});
            Files.setLastModifiedTime(access.resolve(latest), FileTime.fromMillis(1600000000000L + i * 1000));
        }
        FaultSources.Scan scan = sources.discover(FaultPolicy.defaults());
        JSONObject coverage = scan.coverage.getJSONArray("sources").getJSONObject(2);
        assertEquals(828, coverage.getInt("enumerated")); assertEquals(600, coverage.getInt("matched"));
        assertEquals(600, access.metadataCalls); assertTrue(coverage.getBoolean("enumerationComplete"));
        assertEquals(64, scan.candidates.size()); assertEquals(latest, scan.candidates.get(0).path);
        assertEquals(1250, coverage.getLong("sourceBudgetMs"));
        // Each selected candidate still opens and checks its handle and absolute path.
        assertEquals(64 * 3 + 5, access.fullStatCalls);
        assertEquals(4L * 1048576, FaultDirectoryWalker.Android.MAX_BYTES);
        FaultArchive.jsonNew(new File(access.root.toFile(), "discovery-828.json"), scan.coverage);
    }
    @Test public void scopedMetadataIsSingleChildOnlyAndCannotReadAfterDirectoryScopeEnds() throws Exception {
        int[] reads = {0};
        FaultDirectoryWalker.ScopedMetadata metadata = new FaultDirectoryWalker.ScopedMetadata(name -> {
            reads[0]++; return FaultDirectoryWalker.Android.normalized(253, 123, 10, 11, 12, 0100640, 1000, 1001);
        });
        assertEquals("file", metadata.lstat("data_app_crash@1.txt").type);
        for (String name : new String[]{"..", "../escape", "/absolute", "a/b", "a\\b", "nul\u0000suffix"}) {
            try { metadata.lstat(name); fail(); } catch (java.io.IOException expected) { }
        }
        assertEquals(1, reads[0]); metadata.close();
        try { metadata.lstat("data_app_crash@1.txt"); fail(); }
        catch (java.io.IOException expected) { assertEquals("DIRECTORY_SCOPE_CLOSED", expected.getMessage()); }
        assertEquals(1, reads[0]);
    }
    @Test public void pinnedChildMetadataUsesSharedNormalizationWithoutFollowingLeafType() {
        net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat actual =
                FaultDirectoryWalker.Android.normalized(253, 12345, 4096, 100, 101, 0106751, -1, Integer.MIN_VALUE);
        net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat expected =
                new net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat(
                        "file", 253, 12345, 4096, 100, 101, 06751, 4294967295L, 2147483648L);
        assertTrue(expected.same(actual));
        assertEquals("symlink", FaultDirectoryWalker.Android.normalized(1, 2, 3, 4, 5, 0120777, 0, 0).type);
        assertEquals("directory", FaultDirectoryWalker.Android.normalized(1, 2, 3, 4, 5, 0040700, 0, 0).type);
        assertEquals("block", FaultDirectoryWalker.Android.normalized(1, 2, 3, 4, 5, 0060600, 0, 0).type);
        assertEquals("unsupported", FaultDirectoryWalker.Android.normalized(1, 2, 3, 4, 5, 0010600, 0, 0).type);
        assertEquals(0, FaultDirectoryWalker.Android.normalized(1, 2, -1, 4, 5, 0100600, 0, 0).size);
    }
    @Test public void candidateChangedAfterPinnedMetadataIsStillRejectedBySignatureCheck() throws Exception {
        String path = "/data/system/dropbox/data_app_crash@1.txt"; access.write(path, new byte[]{1});
        FaultDirectoryWalker changed = (directory, stat, c, deadline, visitor) -> {
            String name = "data_app_crash@1.txt";
            net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess.Stat before = access.lstat(path);
            visitor.name(name, child -> before);
            access.write(path, new byte[]{2, 3}); return "ENUMERATION_FINISHED";
        };
        FaultSources.Scan scan = new AndroidFaultSources(access, clock, changed).discover(FaultPolicy.defaults());
        assertEquals(0, scan.candidates.size());
        assertEquals(1, scan.coverage.getJSONArray("sources").getJSONObject(2).getInt("unreadable"));
    }
    @Test public void enumerationElapsedUsesMonotonicClockOnSuccessAndFailure() throws Exception {
        access.write("/data/system/dropbox/data_app_crash@1.txt", new byte[]{1});
        FaultDirectoryWalker timed = (path, stat, c, deadline, visitor) -> {
            clock.add(37); return access.walk(path, stat, c, deadline, visitor);
        };
        JSONObject coverage = new AndroidFaultSources(access, clock, timed).discover(FaultPolicy.defaults())
                .coverage.getJSONArray("sources").getJSONObject(2);
        assertEquals(37, coverage.getLong("elapsedMs"));
        assertEquals("ENUMERATION_AND_CHILD_METADATA_EXCLUDES_SIGNATURES", coverage.getString("elapsedScope"));
        FaultDirectoryWalker failed = (path, stat, c, deadline, visitor) -> {
            clock.add(19); throw new java.io.IOException("unavailable");
        };
        JSONObject failure = new AndroidFaultSources(access, clock, failed).discover(FaultPolicy.defaults())
                .coverage.getJSONArray("sources").getJSONObject(2);
        assertEquals(19, failure.getLong("elapsedMs")); assertEquals("UNAVAILABLE", failure.getString("state"));
    }
}
