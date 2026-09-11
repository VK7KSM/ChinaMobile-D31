package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class AndroidFaultSourcesTest {
    private FaultTestFiles.Access access;
    private FaultTestFiles.Clock clock;
    private AndroidFaultSources sources;
    @Before public void setup() throws Exception {
        File root = Files.createTempDirectory("android-fault-adapter-").toFile();
        access = new FaultTestFiles.Access(root); clock = new FaultTestFiles.Clock(); sources = new AndroidFaultSources(access, clock);
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
    @Test public void enumerationOverflowIsExplicit() throws Exception {
        for (int i = 0; i < 65; i++) access.write("/data/tombstones/tombstone_" + String.format(java.util.Locale.ROOT, "%02d", i), new byte[]{1});
        FaultSources.Scan scan = sources.discover(FaultPolicy.defaults());
        assertEquals(64, scan.candidates.size());
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
}
