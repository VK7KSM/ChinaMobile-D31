package net.elfradio.d31bootstrap.faults;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local, write-once exports. An explicit verified acknowledgment releases slots, never bytes. */
public final class FaultExports {
    public static final int MAX_RETAINED_EVENTS = 128;
    public static final int MAX_EXPORTS = 2;
    public static final int MAX_FILES = 48;
    public static final long MAX_PACKAGE_BYTES = 8L * 1048576;
    interface FileIdentity { String read(File file) throws Exception; }
    static FileIdentity identities = FaultExports::androidIdentity;
    private static final Map<String, Check> CHECKS = new LinkedHashMap<String, Check>(128, 0.75f, true);
    private static final Map<String, RawCheck> RAW_CHECKS = new LinkedHashMap<String, RawCheck>(128, 0.75f, true);
    private final FaultArchive archive;
    private final FaultPolicy policy;

    public FaultExports(File root, FaultSources sources, FaultPolicy policy) throws IOException {
        if (sources == null || policy == null) throw new IllegalArgumentException("MISSING_EXPORT_DEPENDENCY");
        this.archive = new FaultArchive(root, sources, false); this.policy = policy;
    }

    public JSONObject exportEvent(String eventId) throws Exception {
        try (Lock ignored = lock()) {
            File event = archive.event(eventId); terminal(event);
            JSONObject committed = latest(event);
            if (committed != null) { verify(event, committed, true); return committed; }
            List<Entry> initial = inventory(event);
            long reserve = sum(initial) + 3L * FaultArchive.JSON_LIMIT + (MAX_FILES + 1L) * 512 + 4096;
            if (reserve > MAX_PACKAGE_BYTES || archive.bytes() + reserve > policy.maxArchiveBytes
                    || archive.root.getUsableSpace() < policy.minFreeBytes + reserve) throw new IOException("EXPORT_CAPACITY_LIMIT");
            File exports = new File(event, "exports"); FaultArchive.directory(exports);
            int number = 1;
            while (number <= MAX_EXPORTS && new File(exports, "export-" + number).exists()) number++;
            if (number > MAX_EXPORTS) throw new IOException("EXPORT_ATTEMPTS_EXHAUSTED");
            File output = new File(exports, "export-" + number); FaultArchive.directory(output);
            // This immutable seal only stops new observation-counter writes; it releases no capacity.
            File seal = new File(event, "export-seal.json");
            if (!seal.exists()) FaultArchive.jsonNew(seal, new JSONObject().put("schemaVersion", 1)
                    .put("eventId", eventId).put("scope", "TERMINAL_EVENT_FILES").put("sealedAtMs", System.currentTimeMillis()));
            List<Entry> entries = inventory(event);
            JSONObject manifest = manifest(event, entries);
            byte[] manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length > FaultArchive.JSON_LIMIT) throw new IOException("EXPORT_MANIFEST_LIMIT");
            File manifestFile = new File(output, "manifest.json"); FaultArchive.writeNew(manifestFile, manifestBytes);
            long packageLimit = sum(entries) + manifestBytes.length + (entries.size() + 1L) * 512 + 4096;
            if (packageLimit > MAX_PACKAGE_BYTES) throw new IOException("EXPORT_PACKAGE_LIMIT");
            File bundle = new File(output, "bundle.zip"); FaultArchive.writeNew(bundle, new byte[0]);
            try (FileOutputStream file = new FileOutputStream(bundle);
                 ZipOutputStream zip = new ZipOutputStream(new BoundedOutput(file, packageLimit))) {
                add(zip, "manifest.json", manifestBytes);
                for (Entry entry : entries) {
                    ZipEntry target = stored("evidence/" + entry.path, entry.bytes, entry.crc);
                    zip.putNextEntry(target);
                    Digest copied = digest(entry.file, zip, entry.bytes);
                    if (!entry.matches(copied)) throw new IOException("EXPORT_SOURCE_CHANGED");
                    zip.closeEntry();
                }
                zip.finish(); zip.flush(); file.getFD().sync();
            }
            if (!same(entries, inventory(event))) throw new IOException("EXPORT_SOURCE_CHANGED");
            JSONObject receipt = new JSONObject().put("schemaVersion", 1).put("kind", "FAULT_EVENT_EXPORT")
                    .put("eventId", eventId).put("exportNumber", number).put("state", "EXPORTED")
                    .put("path", bundle.getAbsolutePath()).put("bytes", bundle.length())
                    .put("sha256", digest(bundle, null, MAX_PACKAGE_BYTES).sha256)
                    .put("manifestSha256", FaultArchive.hash(manifestBytes)).put("manifestBytes", manifestBytes.length)
                    .put("files", entries.size()).put("evidenceBytes", sum(entries))
                    .put("captureState", manifest.getJSONObject("rawEvidence").getString("captureState"))
                    .put("completeRawFiles", manifest.getJSONObject("rawEvidence").getInt("completeRawFiles"))
                    .put("scope", "ALL_FROZEN_EVENT_FILES").put("sourceCompleteness", "SEE_MANIFEST_GAPS")
                    .put("downloadState", "NOT_ACKNOWLEDGED").put("rawContentInSummary", false);
            verify(event, receipt, true);
            // 完整回执先同步临时缓存再提交，进程中断不会留下半份正式回执。
            archive.state(output, "receipt.json", receipt);
            return receipt;
        }
    }

    public JSONObject archiveEvent(String eventId, String packageSha256, long packageBytes,
                                   String manifestSha256) throws Exception {
        if (packageSha256 == null || !packageSha256.matches("[a-f0-9]{64}") || manifestSha256 == null
                || !manifestSha256.matches("[a-f0-9]{64}") || packageBytes < 1 || packageBytes > MAX_PACKAGE_BYTES)
            throw new IOException("INVALID_EXPORT_ACK");
        try (Lock ignored = lock()) {
            File event = archive.event(eventId); terminal(event);
            JSONObject receipt = latest(event);
            if (receipt == null) throw new IOException("EXPORT_RECEIPT_REQUIRED");
            if (!packageSha256.equals(receipt.getString("sha256")) || packageBytes != receipt.getLong("bytes")
                    || !manifestSha256.equals(receipt.getString("manifestSha256"))) throw new IOException("EXPORT_ACK_MISMATCH");
            verify(event, receipt, true);
            File record = new File(event, "archived.json");
            if (record.exists()) {
                if (!isArchived(event)) throw new IOException("ARCHIVE_RECEIPT_INVALID");
                return FaultArchive.read(record);
            }
            JSONObject acknowledgment = new JSONObject().put("schemaVersion", 1).put("eventId", eventId)
                    .put("state", "ARCHIVED").put("exportNumber", receipt.getInt("exportNumber"))
                    .put("receiptSha256", digest(receiptFile(event, receipt.getInt("exportNumber")), null, FaultArchive.JSON_LIMIT).sha256)
                    .put("sha256", packageSha256).put("bytes", packageBytes).put("manifestSha256", manifestSha256)
                    .put("acknowledgedAtMs", System.currentTimeMillis()).put("acknowledgment", "EXPLICIT_VERIFIED_DIGEST_ACK")
                    .put("remoteStorageProof", "CALLER_ATTESTATION_NOT_INDEPENDENT_NETWORK_PROOF")
                    .put("activeSlotReleased", true).put("originalsDeleted", false).put("releasedBytes", 0);
            if (archive.bytes() + FaultArchive.JSON_LIMIT > policy.maxArchiveBytes
                    || archive.root.getUsableSpace() < policy.minFreeBytes + FaultArchive.JSON_LIMIT)
                throw new IOException("ARCHIVE_CAPACITY_LIMIT");
            archive.state(event, "archived.json", acknowledgment);
            return acknowledgment;
        }
    }

    static boolean sealed(File event) throws IOException {
        File seal = new File(event, "export-seal.json"); FaultArchive.checked(seal); return seal.exists();
    }

    static boolean isArchived(File event) {
        try {
            File record = new File(event, "archived.json");
            if (!record.exists()) return false;
            JSONObject ack = FaultArchive.read(record);
            if (!event.getName().equals(ack.getString("eventId")) || !"ARCHIVED".equals(ack.getString("state"))) return false;
            File receiptFile = receiptFile(event, ack.getInt("exportNumber"));
            if (!ack.getString("receiptSha256").equals(digest(receiptFile, null, FaultArchive.JSON_LIMIT).sha256)) return false;
            JSONObject receipt = FaultArchive.read(receiptFile);
            if (!ack.getString("sha256").equals(receipt.getString("sha256")) || ack.getLong("bytes") != receipt.getLong("bytes")
                    || !ack.getString("manifestSha256").equals(receipt.getString("manifestSha256"))) return false;
            return cachedVerify(event, receipt).valid;
        } catch (Exception invalid) { return false; }
    }

    static JSONObject summary(File event) throws Exception {
        JSONObject receipt = latest(event);
        if (receipt == null) return new JSONObject().put("state", sealed(event) ? "INCOMPLETE_EXPORT" : "NOT_EXPORTED");
        Check check = cachedVerify(event, receipt);
        return new JSONObject().put("state", check.valid ? "EXPORTED" : "EXPORT_CORRUPT")
                .put("receipt", receipt).put("archived", check.valid && isArchived(event)).put("releasedBytes", 0)
                .put("verification", new JSONObject().put("mode", check.cached ? "STAT_CACHE" : "FULL_HASH")
                        .put("verifiedAtMs", check.verifiedAt).put("scope", "EXPORT_PACKAGE_AND_MANIFEST")
                        .put("unchangedStatIsNotContentProof", true));
    }

    private static Check cachedVerify(File event, JSONObject receipt) throws Exception {
        String identity = exportIdentity(event, receipt);
        String key = event.getAbsolutePath();
        synchronized (CHECKS) {
            Check prior = CHECKS.get(key);
            if (prior != null && prior.identity.equals(identity)) return new Check(identity, prior.valid, prior.verifiedAt, true);
        }
        boolean valid;
        try { verify(event, receipt, false); valid = true; } catch (Exception invalid) { valid = false; }
        if (!identity.equals(exportIdentity(event, receipt))) throw new IOException("EXPORT_CHANGED_DURING_VERIFICATION");
        Check check = new Check(identity, valid, System.currentTimeMillis(), false);
        synchronized (CHECKS) {
            CHECKS.put(key, check);
            while (CHECKS.size() > MAX_RETAINED_EVENTS) CHECKS.remove(CHECKS.keySet().iterator().next());
        }
        return check;
    }
    private static String exportIdentity(File event, JSONObject receipt) throws Exception {
        File file = receiptFile(event, receipt.getInt("exportNumber")), output = file.getParentFile();
        StringBuilder result = new StringBuilder();
        for (File part : new File[]{new File(event, "archived.json"), file,
                new File(output, "manifest.json"), new File(output, "bundle.zip")}) {
            FaultArchive.checked(part);
            result.append(part.getName()).append(':').append(part.exists() ? identities.read(part) : "MISSING").append('\n');
        }
        return result.toString();
    }
    private static String androidIdentity(File file) throws Exception {
        android.system.StructStat s = android.system.Os.lstat(file.getAbsolutePath());
        if (!android.system.OsConstants.S_ISREG(s.st_mode)) throw new IOException("EXPORT_REGULAR_FILE_REQUIRED");
        return s.st_dev + ":" + s.st_ino + ":" + s.st_size + ":" + s.st_mtime + ":" + s.st_ctime
                + ":" + s.st_mode + ":" + s.st_uid + ":" + s.st_gid + ":" + s.st_nlink;
    }
    private static final class Check {
        final String identity; final boolean valid, cached; final long verifiedAt;
        Check(String identity, boolean valid, long verifiedAt, boolean cached) {
            this.identity = identity; this.valid = valid; this.verifiedAt = verifiedAt; this.cached = cached;
        }
    }

    static JSONObject rawSummary(File event) throws Exception {
        return rawSummary(event, false);
    }
    private static JSONObject rawSummary(File event, boolean fullRead) throws Exception {
        JSONObject state = FaultArchive.read(new File(event, "state.json"));
        JSONArray items = new JSONArray(); int complete = 0, partial = 0;
        for (int i = 1; i <= 3; i++) {
            File report = new File(event, "attempt-" + i + "/report.json");
            if (!report.exists()) continue;
            JSONObject diagnostic;
            try { diagnostic = FaultArchive.read(report).getJSONObject("diagnostic"); }
            catch (Exception damaged) {
                partial++;
                items.put(new JSONObject().put("artifact", "attempt-" + i + "/report.json")
                        .put("completeOriginal", false).put("state", "REPORT_UNREADABLE"));
                continue;
            }
            JSONArray sources = diagnostic.optJSONArray("items");
            if (sources == null) continue;
            if (sources.length() > 64) throw new IOException("REPORT_ENTRY_LIMIT");
            for (int n = 0; n < sources.length(); n++) {
                JSONObject source = sources.getJSONObject(n); String artifact = source.optString("artifact");
                if (artifact.isEmpty()) continue;
                if (!artifact.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) throw new IOException("REPORT_ARTIFACT_PATH_REJECTED");
                String relative = "attempt-" + i + "/" + artifact;
                JSONObject item = new JSONObject().put("artifact", relative).put("completeOriginal", false);
                try {
                    RawCheck checked = rawDigest(new File(event, relative), fullRead);
                    Digest raw = checked.digest;
                    boolean verified = raw.bytes == source.optLong("storedBytes", -1) && raw.sha256.equals(source.optString("storedSha256"));
                    JSONObject before = source.optJSONObject("before");
                    boolean full = verified && "COMPLETE".equals(source.optString("state")) && before != null
                            && before.optLong("size", -1) == raw.bytes;
                    item.put("bytes", raw.bytes).put("sha256", raw.sha256).put("completeOriginal", full)
                            .put("verificationMode", checked.cached ? "STAT_CACHE" : "FULL_HASH").put("verifiedAtMs", checked.verifiedAt)
                            .put("state", full ? "COMPLETE" : verified ? "PARTIAL" : "RECEIPT_MISMATCH");
                    if (full) complete++; else partial++;
                } catch (IOException missing) { item.put("state", "UNAVAILABLE"); partial++; }
                items.put(item);
            }
        }
        return new JSONObject().put("captureState", safeState(state.optString("capture", "UNKNOWN")))
                .put("completeRawFiles", complete).put("partialRawFiles", partial).put("items", items)
                .put("unchangedStatIsNotContentProof", true)
                .put("completenessBasis", "DIAGNOSTIC_COMPLETE_AND_SOURCE_SIZE_AND_STORED_SHA256");
    }

    private static File receiptFile(File event, int number) throws IOException {
        if (number < 1 || number > MAX_EXPORTS) throw new IOException("INVALID_EXPORT_NUMBER");
        File file = new File(event, "exports/export-" + number + "/receipt.json"); FaultArchive.checked(file); return file;
    }
    private static JSONObject latest(File event) throws Exception {
        JSONObject result = null;
        for (int i = 1; i <= MAX_EXPORTS; i++) {
            File file = receiptFile(event, i);
            if (file.exists()) {
                if (result != null) throw new IOException("MULTIPLE_COMMITTED_EXPORTS");
                result = FaultArchive.read(file);
            }
        }
        return result;
    }
    private static void terminal(File event) throws Exception {
        JSONObject descriptor = FaultArchive.read(new File(event, "event.json"));
        if (!event.getName().equals(FaultSources.Candidate.parse(descriptor.getJSONObject("source")).id()))
            throw new IOException("EVENT_ID_MISMATCH");
        String phase = FaultArchive.read(new File(event, "state.json")).getString("phase");
        if (!phase.equals("COMPLETE") && !phase.equals("PARTIAL")) throw new IOException("EVENT_NOT_TERMINAL");
    }

    private static List<Entry> inventory(File event) throws Exception {
        FaultArchive.checked(event);
        File[] children = event.listFiles(); if (children == null || children.length > 24) throw new IOException("EXPORT_ENTRY_LIMIT");
        List<Entry> entries = new ArrayList<Entry>();
        for (File file : children) {
            FaultArchive.checked(file); String name = file.getName();
            if (name.equals("exports") || name.equals("archived.json") || name.equals("archived.json.next")) continue;
            if (name.matches("attempt-[1-3]")) {
                if (!file.isDirectory()) throw new IOException("EXPORT_DIRECTORY_REQUIRED");
                File[] leaves = file.listFiles(); if (leaves == null || leaves.length > 10) throw new IOException("EXPORT_ENTRY_LIMIT");
                for (File leaf : leaves) {
                    if (!leaf.getName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) throw new IOException("EXPORT_PATH_REJECTED");
                    entries.add(entry(leaf, name + "/" + leaf.getName()));
                }
            } else {
                if (!name.matches("event\\.json|state\\.json(\\.next)?|pre-[1-4]\\.json|post\\.json|export-seal\\.json"))
                    throw new IOException("EXPORT_PATH_REJECTED");
                entries.add(entry(file, name));
            }
            if (entries.size() > MAX_FILES) throw new IOException("EXPORT_ENTRY_LIMIT");
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.path.compareTo(b.path); }
        });
        if (sum(entries) > MAX_PACKAGE_BYTES - FaultArchive.JSON_LIMIT - 32768) throw new IOException("EXPORT_BYTE_LIMIT");
        return entries;
    }
    private static Entry entry(File file, String relative) throws Exception {
        FaultArchive.checked(file);
        if (!file.isFile() || file.length() > 1048576) throw new IOException("EXPORT_FILE_LIMIT");
        Digest digest = digest(file, null, 1048576);
        return new Entry(file, relative, digest);
    }
    private static long sum(List<Entry> entries) { long total = 0; for (Entry e : entries) total += e.bytes; return total; }
    private static boolean same(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).path.equals(b.get(i).path)
                || a.get(i).bytes != b.get(i).bytes || !a.get(i).sha256.equals(b.get(i).sha256)) return false;
        return true;
    }
    private static JSONObject manifest(File event, List<Entry> entries) throws Exception {
        JSONArray files = new JSONArray(), gaps = new JSONArray(); Set<String> paths = new HashSet<String>();
        for (Entry e : entries) { files.put(e.json()); paths.add(e.path); }
        JSONObject descriptor = FaultArchive.read(new File(event, "event.json"));
        JSONObject state = FaultArchive.read(new File(event, "state.json"));
        gaps.put(new JSONObject().put("code", "PRE_FAULT_CONTINUOUS_LOGS_UNAVAILABLE"));
        if (!"SAMPLED".equals(descriptor.optString("preWindowState")))
            gaps.put(new JSONObject().put("code", "PRE_WINDOW_INCOMPLETE"));
        if (!"SAMPLED".equals(state.optString("postWindow"))) gaps.put(new JSONObject().put("code", "POST_WINDOW_INCOMPLETE"));
        else if (!paths.contains("post.json")) gaps.put(new JSONObject().put("code", "MISSING_FROZEN_FILE").put("path", "post.json"));
        JSONArray pre = descriptor.optJSONArray("preWindow");
        if (pre != null) for (int i = 1; i <= pre.length(); i++)
            if (!paths.contains("pre-" + i + ".json")) gaps.put(new JSONObject().put("code", "MISSING_FROZEN_FILE").put("path", "pre-" + i + ".json"));
        int raw = 0;
        for (Entry e : entries) {
            if (e.path.endsWith(".bin")) raw++;
            if (!e.path.matches("attempt-[1-3]/report\\.json")) continue;
            try {
                JSONObject diagnostic = FaultArchive.read(e.file).getJSONObject("diagnostic");
                if (!"COMPLETE".equals(diagnostic.optString("state"))) gaps.put(new JSONObject().put("code", "CAPTURE_PARTIAL").put("path", e.path));
                JSONArray items = diagnostic.optJSONArray("items");
                if (!"COMPLETE".equals(diagnostic.optString("state")))
                    gaps.put(new JSONObject().put("code", "CAPTURE_INCOMPLETE").put("path", e.path)
                            .put("reason", safeState(diagnostic.optString("reason", "UNKNOWN"))));
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (!"COMPLETE".equals(item.optString("state"))) gaps.put(new JSONObject().put("code", "SOURCE_INCOMPLETE")
                            .put("path", e.path).put("sourceState", safeState(item.optString("state"))));
                    String artifact = item.optString("artifact");
                    if (!artifact.isEmpty()) {
                        if (!artifact.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) throw new IOException("REPORT_ARTIFACT_PATH_REJECTED");
                        String path = e.path.substring(0, e.path.indexOf('/') + 1) + artifact;
                        Entry stored = null; for (Entry entry : entries) if (entry.path.equals(path)) stored = entry;
                        if (stored == null) gaps.put(new JSONObject().put("code", "MISSING_RAW_ARTIFACT").put("path", path));
                        else if (stored.bytes != item.optLong("storedBytes", -1) || !stored.sha256.equals(item.optString("storedSha256")))
                            gaps.put(new JSONObject().put("code", "RAW_RECEIPT_MISMATCH").put("path", path));
                    }
                }
            } catch (IOException invalid) { throw invalid; }
            catch (Exception malformed) { gaps.put(new JSONObject().put("code", "REPORT_UNREADABLE").put("path", e.path)); }
        }
        for (int i = 1; i <= 3; i++) {
            File attempt = new File(event, "attempt-" + i);
            if (i <= state.optInt("attempts", 0) && !attempt.exists())
                gaps.put(new JSONObject().put("code", "MISSING_ATTEMPT_DIRECTORY").put("path", "attempt-" + i));
            if (attempt.exists() && !paths.contains("attempt-" + i + "/report.json"))
                gaps.put(new JSONObject().put("code", "INTERRUPTED_ATTEMPT").put("path", "attempt-" + i));
        }
        if (raw == 0) gaps.put(new JSONObject().put("code", "NO_RAW_ARTIFACT_SAVED"));
        return new JSONObject().put("schemaVersion", 1).put("kind", "FAULT_EVENT_BUNDLE")
                .put("eventId", event.getName()).put("category", descriptor.getJSONObject("source").getString("category"))
                .put("phase", state.getString("phase")).put("files", files).put("totalBytes", sum(entries)).put("rawFiles", raw)
                .put("rawEvidence", rawSummary(event, true))
                .put("gaps", gaps).put("scope", "ALL_FROZEN_EVENT_FILES").put("sourceCompleteness", "SEE_GAPS")
                .put("rootCause", "NOT_ESTABLISHED").put("systemConsistency", "NOT_ASSESSED");
    }
    private static String safeState(String state) { return state.matches("[A-Z_]{1,40}") ? state : "UNKNOWN"; }

    private static RawCheck rawDigest(File file, boolean fullRead) throws Exception {
        FaultArchive.checked(file); String identity = identities.read(file), key = file.getAbsolutePath();
        synchronized (RAW_CHECKS) {
            RawCheck prior = RAW_CHECKS.get(key);
            if (!fullRead && prior != null && prior.identity.equals(identity))
                return new RawCheck(identity, prior.digest, prior.verifiedAt, true);
        }
        Digest digest = digest(file, null, 1048576);
        if (!identity.equals(identities.read(file))) throw new IOException("RAW_CHANGED_DURING_VERIFICATION");
        RawCheck value = new RawCheck(identity, digest, System.currentTimeMillis(), false);
        synchronized (RAW_CHECKS) {
            RAW_CHECKS.put(key, value);
            while (RAW_CHECKS.size() > MAX_RETAINED_EVENTS * 3) RAW_CHECKS.remove(RAW_CHECKS.keySet().iterator().next());
        }
        return value;
    }
    private static final class RawCheck {
        final String identity; final Digest digest; final long verifiedAt; final boolean cached;
        RawCheck(String identity, Digest digest, long verifiedAt, boolean cached) {
            this.identity = identity; this.digest = digest; this.verifiedAt = verifiedAt; this.cached = cached;
        }
    }

    private static void verify(File event, JSONObject receipt, boolean originalFiles) throws Exception {
        if (!event.getName().equals(receipt.getString("eventId")) || !"EXPORTED".equals(receipt.getString("state"))
                || !"FAULT_EVENT_EXPORT".equals(receipt.getString("kind"))) throw new IOException("EXPORT_RECEIPT_INVALID");
        File output = receiptFile(event, receipt.getInt("exportNumber")).getParentFile();
        File bundle = new File(output, "bundle.zip"); FaultArchive.checked(bundle);
        if (!bundle.getAbsolutePath().equals(receipt.getString("path")) || bundle.length() != receipt.getLong("bytes")
                || !digest(bundle, null, MAX_PACKAGE_BYTES).sha256.equals(receipt.getString("sha256"))) throw new IOException("EXPORT_HASH_MISMATCH");
        File manifestFile = new File(output, "manifest.json");
        Digest manifestDigest = digest(manifestFile, null, FaultArchive.JSON_LIMIT);
        if (manifestDigest.bytes != receipt.getLong("manifestBytes") || !manifestDigest.sha256.equals(receipt.getString("manifestSha256")))
            throw new IOException("EXPORT_MANIFEST_MISMATCH");
        if (!originalFiles) return;
        JSONObject manifest = FaultArchive.read(manifestFile); JSONArray files = manifest.getJSONArray("files");
        List<Entry> actual = inventory(event);
        if (!event.getName().equals(manifest.getString("eventId")) || files.length() != actual.size()) throw new IOException("EXPORT_SOURCE_CHANGED");
        try (ZipFile zip = new ZipFile(bundle)) {
            if (zip.size() != actual.size() + 1) throw new IOException("EXPORT_ZIP_ENTRIES");
            Set<String> expected = new HashSet<String>(); expected.add("manifest.json");
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.getJSONObject(i); Entry entry = actual.get(i);
                if (!entry.path.equals(item.getString("path")) || entry.bytes != item.getLong("bytes")
                        || !entry.sha256.equals(item.getString("sha256"))) throw new IOException("EXPORT_SOURCE_CHANGED");
                expected.add("evidence/" + entry.path);
            }
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!expected.remove(entry.getName()) || entry.isDirectory() || entry.getMethod() != ZipEntry.STORED)
                    throw new IOException("EXPORT_ZIP_ENTRIES");
                long length; String hash;
                if (entry.getName().equals("manifest.json")) { length = manifestDigest.bytes; hash = manifestDigest.sha256; }
                else {
                    Entry source = null;
                    for (Entry a : actual) if (("evidence/" + a.path).equals(entry.getName())) source = a;
                    if (source == null) throw new IOException("EXPORT_ZIP_ENTRIES"); length = source.bytes; hash = source.sha256;
                }
                if (entry.getSize() != length) throw new IOException("EXPORT_ZIP_LENGTH");
                try (InputStream input = zip.getInputStream(entry)) {
                    Digest value = digest(input, null, length);
                    if (value.bytes != length || !hash.equals(value.sha256)) throw new IOException("EXPORT_ZIP_HASH");
                }
            }
            if (!expected.isEmpty()) throw new IOException("EXPORT_ZIP_ENTRIES");
        }
    }
    private Lock lock() throws Exception {
        archive.sources.checkPrivateRoot(archive.root); File path = new File(archive.root, "collector.lock"); FaultArchive.checked(path);
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        try {
            FileLock lock;
            try { lock = file.getChannel().tryLock(); } catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) throw new IOException("COLLECTOR_BUSY"); return new Lock(file, lock);
        } catch (Exception failure) { file.close(); throw failure; }
    }
    private static final class Lock implements Closeable {
        final RandomAccessFile file; final FileLock lock;
        Lock(RandomAccessFile file, FileLock lock) { this.file = file; this.lock = lock; }
        @Override public void close() throws IOException { try { lock.release(); } finally { file.close(); } }
    }
    private static ZipEntry stored(String name, long length, long crc) {
        ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setSize(length); entry.setCompressedSize(length);
        entry.setCrc(crc); entry.setTime(315532800000L); return entry;
    }
    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32(); crc.update(bytes); zip.putNextEntry(stored(name, bytes.length, crc.getValue())); zip.write(bytes); zip.closeEntry();
    }
    static Digest digest(File file, OutputStream output, long limit) throws Exception {
        FaultArchive.checked(file); if (!file.isFile()) throw new IOException("EXPORT_REGULAR_FILE_REQUIRED");
        try (InputStream input = new FileInputStream(file)) { return digest(input, output, limit); }
    }
    private static Digest digest(InputStream input, OutputStream output, long limit) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); CRC32 crc = new CRC32(); long count = 0; byte[] buffer = new byte[16384]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("EXPORT_CANCELLED");
            if (n == 0 || count + n > limit) throw new IOException("EXPORT_BYTE_LIMIT");
            count += n; sha.update(buffer, 0, n); crc.update(buffer, 0, n); if (output != null) output.write(buffer, 0, n);
        }
        StringBuilder hash = new StringBuilder(); for (byte b : sha.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        return new Digest(count, crc.getValue(), hash.toString());
    }
    static final class Digest {
        final long bytes, crc; final String sha256;
        Digest(long bytes, long crc, String hash) { this.bytes = bytes; this.crc = crc; this.sha256 = hash; }
    }
    private static final class Entry {
        final File file; final String path, sha256; final long bytes, crc;
        Entry(File file, String path, Digest digest) { this.file = file; this.path = path; bytes = digest.bytes; crc = digest.crc; sha256 = digest.sha256; }
        boolean matches(Digest digest) { return bytes == digest.bytes && sha256.equals(digest.sha256); }
        JSONObject json() throws Exception { return new JSONObject().put("path", path).put("bytes", bytes).put("sha256", sha256)
                .put("role", path.endsWith(".bin") ? "RAW" : "METADATA"); }
    }
    private static final class BoundedOutput extends FilterOutputStream {
        final long maximum; long written;
        BoundedOutput(OutputStream output, long maximum) { super(output); this.maximum = maximum; }
        @Override public void write(int value) throws IOException { if (++written > maximum) throw new IOException("EXPORT_BYTE_LIMIT"); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (written + length > maximum) throw new IOException("EXPORT_BYTE_LIMIT"); out.write(bytes, offset, length); written += length;
        }
    }
}
