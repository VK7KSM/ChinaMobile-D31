package net.elfradio.d31bootstrap.faults;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

/** Android 6 on-disk adapters. No shell, DropBox binder context, or logcat process. */
public final class AndroidFaultSources implements FaultSources {
    public static final String ARCHIVE_ROOT = "/data/local/d31-remote/faults";
    private static final String[] ROOTS = {"/data/anr", "/data/tombstones", "/data/system/dropbox", "/data/local/d31-remote"};
    private static final String[] CATEGORIES = {"ANR", "TOMBSTONE", "DROPBOX", "BOOT"};
    private final CollectionAccess access;
    private final CollectionAccess.Clock clock;

    public AndroidFaultSources() {
        this.clock = AndroidCollectionAccess.systemClock();
        this.access = new AndroidCollectionAccess("/system/bin/busybox", clock);
    }
    AndroidFaultSources(CollectionAccess access, CollectionAccess.Clock clock) {
        this.access = access; this.clock = clock;
    }
    static boolean accepts(int root, String name) {
        if (name == null) return false;
        switch (root) {
            case 0: return name.matches("traces[0-9A-Za-z_.-]*\\.txt|anr_[0-9A-Za-z_.-]+");
            case 1: return name.matches("tombstone_[0-9]{2,3}(\\.pb)?");
            case 2: return name.matches("(data_app_(crash|anr)|system_app_(crash|anr)|system_server_(crash|anr)|SYSTEM_TOMBSTONE|SYSTEM_SERVER_WATCHDOG)@[0-9]+\\.(txt|dat|lost)(\\.gz)?");
            case 3: return name.matches("supervisor-launch-[0-9A-Za-z_-]+\\.log");
            default: return false;
        }
    }
    private static String fingerprint(CollectionAccess.Stat s) {
        return FaultArchive.hash(s.type + ":" + s.device + ":" + s.inode + ":" + s.size + ":"
                + s.modified + ":" + s.changed + ":" + s.mode + ":" + s.uid + ":" + s.gid);
    }
    private String signature(String path, CollectionAccess.Stat stat, int limit, long deadline) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (CollectionAccess.Handle handle = access.openRegular(path, stat)) {
            byte[] chunk = new byte[4096]; long expected = Math.min(stat.size, Math.min(limit, 4096));
            while (bytes.size() < expected) {
                if (Thread.currentThread().isInterrupted() || clock.elapsedRealtimeMillis() >= deadline)
                    throw new CollectionAccess.Failure("SCAN_TIME_LIMIT");
                int n = handle.read(chunk, 0, (int) Math.min(chunk.length, expected - bytes.size()));
                if (n <= 0) throw new CollectionAccess.Failure("UNSTABLE_FILE"); bytes.write(chunk, 0, n);
            }
            if (!stat.same(handle.stat()) || !stat.same(access.lstat(path))) throw new CollectionAccess.Failure("UNSTABLE_FILE");
        }
        return FaultArchive.hash(fingerprint(stat) + ":" + FaultArchive.hash(bytes.toByteArray()));
    }
    static String code(Exception failure) {
        if (failure instanceof CollectionAccess.Failure) {
            String code = ((CollectionAccess.Failure) failure).code;
            if (code.matches("[A-Z_]{1,64}")) return code;
        }
        return "SOURCE_IO_FAILED";
    }
    @Override public Scan discover(FaultPolicy policy) throws Exception {
        List<Candidate> candidates = new ArrayList<Candidate>(); JSONArray coverage = new JSONArray();
        long start = clock.elapsedRealtimeMillis();
        for (int root = 0; root < ROOTS.length; root++) {
            JSONObject item = new JSONObject().put("category", CATEGORIES[root]);
            long remaining = policy.scanMs - (clock.elapsedRealtimeMillis() - start);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                coverage.put(item.put("state", "NOT_CHECKED").put("reason", "SCAN_TIME_LIMIT")); continue;
            }
            try {
                CollectionAccess.Stat before = access.lstat(ROOTS[root]);
                CollectionAccess.Listing listing = access.list(ROOTS[root], before, 64, 32768, remaining);
                int accepted = 0, unreadable = 0;
                for (String name : listing.names) {
                    if (clock.elapsedRealtimeMillis() - start >= policy.scanMs) break;
                    if (!accepts(root, name)) continue;
                    try {
                        String path = ROOTS[root] + "/" + name;
                        CollectionAccess.Stat s = access.lstat(path);
                        if (!"file".equals(s.type)) { unreadable++; continue; }
                        candidates.add(new Candidate(CATEGORIES[root], path,
                                signature(path, s, policy.maxFileBytes, start + policy.scanMs), Math.max(0, s.modified) * 1000));
                        accepted++;
                    } catch (IOException failure) { unreadable++; }
                }
                boolean complete = listing.complete && unreadable == 0 && clock.elapsedRealtimeMillis() - start < policy.scanMs;
                item.put("state", complete ? "CHECKED" : "PARTIAL").put("accepted", accepted)
                        .put("unreadable", unreadable).put("enumerated", listing.names.size())
                        .put("reason", complete ? "BOUNDED_ENUMERATION" : "ENUMERATION_OR_READ_LIMIT");
            } catch (Exception failure) { item.put("state", "UNAVAILABLE").put("reason", code(failure)); }
            coverage.put(item);
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) {
                int time = Long.compare(b.sourceTimeMs, a.sourceTimeMs);
                return time == 0 ? a.id().compareTo(b.id()) : time;
            }
        });
        return new Scan(candidates, new JSONObject().put("sources", coverage).put("discovery", "BOUNDED_FILE_METADATA")
                .put("processExitDetection", "NOT_IMPLEMENTED").put("logcat", "NOT_COLLECTED")
                .put("historicalFilesMayPredateMonitor", true)
                .put("dedupScope", "STAT_AND_FIRST_4096_BYTES_NOT_EXHAUSTIVE_EVENT_DETECTION"));
    }
    private static boolean allowed(Candidate candidate) {
        for (int i = 0; i < ROOTS.length; i++) {
            String prefix = ROOTS[i] + "/";
            if (candidate.category.equals(CATEGORIES[i]) && candidate.path.startsWith(prefix)
                    && accepts(i, candidate.path.substring(prefix.length()))) return true;
        }
        return false;
    }
    @Override public Capture capture(Candidate candidate, File newAttempt, FaultPolicy policy) throws Exception {
        if (!allowed(candidate)) throw new IOException("SOURCE_NOT_REGISTERED");
        CollectionAccess.Stat current = access.lstat(candidate.path);
        if (!candidate.fingerprint.equals(signature(candidate.path, current, policy.maxFileBytes,
                clock.elapsedRealtimeMillis() + policy.scanMs)))
            return unavailable("SOURCE_CHANGED", false);
        CollectionLimits limits = new CollectionLimits(1, policy.maxFileBytes, policy.maxFileBytes,
                policy.scanMs, 0, 65536, policy.windowMs == 0 ? 1 : policy.windowMs);
        String category = candidate.category.equals("DROPBOX") ? "CUSTOM" : candidate.category;
        final String[] prefixHash = new String[1];
        final FaultEvidenceCollector.Store delegate = store(newAttempt);
        JSONObject result = new FaultEvidenceCollector(access, clock).collect("fault",
                Collections.singletonList(new FaultEvidenceCollector.Source("source", category, candidate.path)),
                limits, new FaultEvidenceCollector.Store() {
                    @Override public FaultEvidenceCollector.Receipt storeNew(String id, byte[] bytes) throws IOException {
                        prefixHash[0] = FaultArchive.hash(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 4096)));
                        return delegate.storeNew(id, bytes);
                    }
                });
        result.put("sourceCategory", candidate.category).put("encoding", candidate.path.endsWith(".gz") ? "GZIP_RAW" : "RAW");
        JSONObject item = result.getJSONArray("items").getJSONObject(0);
        JSONObject before = item.optJSONObject("before");
        if (before != null) {
            CollectionAccess.Stat captured = new CollectionAccess.Stat(before.getString("type"), before.getLong("device"),
                    before.getLong("inode"), before.getLong("size"), before.getLong("modified"), before.getLong("changed"),
                    before.getInt("mode"), before.getLong("uid"), before.getLong("gid"));
            long prefixLength = Math.min(captured.size, Math.min(policy.maxFileBytes, 4096));
            if (prefixHash[0] != null && item.optLong("capturedBytes", 0) >= prefixLength
                    && !candidate.fingerprint.equals(FaultArchive.hash(fingerprint(captured) + ":" + prefixHash[0]))) {
                result.put("state", "PARTIAL").put("reason", "SOURCE_CHANGED"); return new Capture(result, false);
            }
        }
        if (candidate.path.endsWith(".lost")) {
            result.put("state", "PARTIAL").put("reason", "DROPBOX_ENTRY_LOST"); return new Capture(result, false);
        }
        boolean retry = item.optString("state").matches("READ_FAILED|UNSTABLE|STORE_FAILED|NOT_CHECKED");
        return new Capture(result, retry);
    }
    FaultEvidenceCollector.Store store(File attempt) { return new AndroidEvidenceStore(attempt.getAbsolutePath()); }
    private static Capture unavailable(String reason, boolean retry) throws Exception {
        return new Capture(new JSONObject().put("schemaVersion", 1).put("state", "PARTIAL")
                .put("reason", reason).put("items", new JSONArray()), retry);
    }
    private byte[] readProc(String path, int maxBytes) throws IOException {
        CollectionAccess.Stat stat = access.lstat(path);
        try (CollectionAccess.Handle handle = access.openRegular(path, stat)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1024];
            while (out.size() < maxBytes) {
                int n = handle.read(b, 0, Math.min(b.length, maxBytes - out.size()));
                if (n < 0) return out.toByteArray();
                if (n == 0) throw new IOException("PROC_READ_STALLED"); out.write(b, 0, n);
            }
            throw new IOException("PROC_BYTE_LIMIT");
        }
    }
    @Override public String bootKey() throws Exception {
        String boot = new String(readProc("/proc/sys/kernel/random/boot_id", 128), StandardCharsets.US_ASCII).trim();
        if (!boot.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new IOException("BOOT_ID_UNAVAILABLE");
        return FaultArchive.hash(boot);
    }
    @Override public JSONObject context() throws Exception {
        JSONObject result = new JSONObject().put("schemaVersion", 1).put("capturedAtMs", clock.wallTimeMillis())
                .put("elapsedMs", clock.elapsedRealtimeMillis());
        for (String name : new String[]{"meminfo", "loadavg"}) {
            try {
                String text = new String(readProc("/proc/" + name, 16384), StandardCharsets.US_ASCII);
                result.put(name, new JSONObject().put("state", "CAPTURED").put("text", text));
            } catch (IOException failure) { result.put(name, new JSONObject().put("state", "UNAVAILABLE").put("reason", code(failure))); }
        }
        return result.put("threads", "NOT_COLLECTED").put("network", "NOT_COLLECTED")
                .put("logcat", "NOT_COLLECTED").put("atomicSnapshot", false);
    }
    @Override public void checkPrivateRoot(File root) throws IOException {
        if (!root.getAbsolutePath().equals(ARCHIVE_ROOT)) throw new IOException("PRODUCTION_ARCHIVE_PATH_REQUIRED");
        try {
            StructStat s = Os.lstat(root.getAbsolutePath());
            if (!OsConstants.S_ISDIR(s.st_mode) || s.st_uid != 0 || s.st_gid != 0 || (s.st_mode & 07777) != 0700)
                throw new IOException("PRIVATE_ARCHIVE_REQUIRED");
        } catch (ErrnoException failure) { throw new IOException("PRIVATE_ARCHIVE_UNAVAILABLE"); }
    }
    @Override public void replace(File temporary, File destination) throws IOException {
        try { Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath()); }
        catch (ErrnoException failure) { throw new IOException("STATE_COMMIT_FAILED"); }
    }
}
