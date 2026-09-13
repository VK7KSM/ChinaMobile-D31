package net.elfradio.d31bootstrap.faults;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Mutable query state is separate from write-once evidence and attempt reports. */
final class FaultArchive {
    static final int JSON_LIMIT = 65536;
    final File root;
    final FaultSources sources;
    FaultArchive(File root, FaultSources sources) throws IOException {
        this(root, sources, true);
    }
    FaultArchive(File root, FaultSources sources, boolean create) throws IOException {
        this.root = root.getAbsoluteFile(); this.sources = sources;
        if (create) directory(this.root);
        else { checked(this.root); if (!this.root.isDirectory()) throw new IOException("ARCHIVE_NOT_FOUND"); }
        if (sources != null) sources.checkPrivateRoot(this.root);
    }

    static void checked(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("ARCHIVE_LINK_REJECTED");
    }
    static void directory(File file) throws IOException {
        checked(file);
        if (!file.exists()) {
            if (!file.mkdir()) throw new IOException("ARCHIVE_DIRECTORY_FAILED");
            if (File.separatorChar != '\\' && (!file.setReadable(false, false) || !file.setWritable(false, false)
                    || !file.setExecutable(false, false) || !file.setReadable(true, true)
                    || !file.setWritable(true, true) || !file.setExecutable(true, true)))
                throw new IOException("ARCHIVE_PERMISSION_FAILED");
        }
        if (!file.isDirectory()) throw new IOException("ARCHIVE_DIRECTORY_REQUIRED");
    }
    static String hash(String text) { return hash(text.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static void writeNew(File file, byte[] bytes) throws IOException {
        checked(file);
        if (!file.createNewFile()) throw new IOException("ARCHIVE_EXISTS");
        if (File.separatorChar != '\\' && (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setReadable(true, true) || !file.setWritable(true, true)))
            throw new IOException("ARCHIVE_PERMISSION_FAILED");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.flush(); out.getFD().sync(); }
    }
    static void jsonNew(File file, JSONObject object) throws IOException {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JSON_LIMIT) throw new IOException("INDEX_SIZE_LIMIT");
        writeNew(file, bytes);
    }
    static JSONObject read(File file) throws Exception {
        checked(file);
        if (!file.isFile() || file.length() > JSON_LIMIT) throw new IOException("INDEX_MISSING_OR_OVERSIZE");
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int n;
            while ((n = in.read(chunk)) != -1) {
                if (bytes.size() + n > JSON_LIMIT) throw new IOException("INDEX_SIZE_LIMIT");
                bytes.write(chunk, 0, n);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    void state(File directory, String name, JSONObject state) throws Exception {
        File pending = new File(directory, name + ".next"); checked(pending);
        // .next is an uncommitted cache, never a raw artifact or committed attempt report.
        if (pending.exists() && !pending.delete()) throw new IOException("STATE_PENDING_BUSY");
        jsonNew(pending, state);
        File destination = new File(directory, name); checked(destination);
        sources.replace(pending, destination);
    }
    File event(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("INVALID_EVENT_ID");
        File event = new File(root, id); checked(event); return event;
    }
    List<String> ids() throws IOException {
        File[] files = root.listFiles(); if (files == null || files.length > 132) throw new IOException("ARCHIVE_ENTRY_LIMIT");
        List<String> ids = new ArrayList<String>();
        for (File file : files) if (file.getName().matches("[a-f0-9]{64}")) {
            checked(file); if (!file.isDirectory()) throw new IOException("EVENT_DIRECTORY_REQUIRED"); ids.add(file.getName());
        }
        Collections.sort(ids); return ids;
    }
    long bytes() throws IOException { return size(root, 0, new int[]{0}); }
    private long size(File file, int depth, int[] count) throws IOException {
        checked(file);
        if (++count[0] > 8192 || depth > 4) throw new IOException("ARCHIVE_ENTRY_LIMIT");
        if (file.isFile()) return file.length();
        File[] files = file.listFiles(); if (files == null || files.length > 256) throw new IOException("ARCHIVE_ENTRY_LIMIT");
        long total = 0; for (File child : files) total += size(child, depth + 1, count); return total;
    }
    JSONObject query(String id) throws Exception {
        File event = event(id);
        if (!event.isDirectory()) return new JSONObject().put("eventId", id).put("state", "NOT_FOUND");
        JSONObject receipt = read(new File(event, "event.json"));
        if (!id.equals(CandidateId(receipt))) throw new IOException("EVENT_ID_MISMATCH");
        JSONObject state;
        try { state = read(new File(event, "state.json")); }
        catch (Exception missing) { state = new JSONObject().put("phase", "INTERRUPTED").put("reason", "STATE_UNAVAILABLE"); }
        state.remove("attemptBoot"); state.remove("attemptElapsedMs");
        JSONArray attempts = new JSONArray();
        for (int i = 1; i <= 3; i++) {
            File attempt = new File(event, "attempt-" + i); checked(attempt);
            if (!attempt.exists()) continue;
            JSONObject item = new JSONObject().put("number", i);
            File report = new File(attempt, "report.json");
            if (report.isFile()) {
                try {
                    JSONObject value = read(report);
                    item.put("state", value.getJSONObject("diagnostic").optString("state", "PARTIAL"))
                            .put("report", reference(report));
                } catch (Exception damaged) { item.put("state", "REPORT_UNREADABLE"); }
            } else item.put("state", "INTERRUPTED");
            attempts.put(item);
        }
        JSONObject result = new JSONObject().put("schemaVersion", 1).put("eventId", id)
                .put("category", receipt.getJSONObject("source").getString("category"))
                .put("detectedAtMs", receipt.getLong("detectedAtMs"))
                .put("sourceTimeMs", receipt.getJSONObject("source").getLong("sourceTimeMs"))
                .put("sourceTimeBasis", "FILE_MTIME_NOT_FAULT_TIMESTAMP")
                .put("eventMeaning", "SOURCE_FILE_OBSERVATION_NOT_CONFIRMED_NEW_INCIDENT")
                .put("preWindowState", receipt.getString("preWindowState"))
                .put("beforeFaultCoverage", "NOT_ESTABLISHED").put("preFaultLogcat", "UNAVAILABLE")
                .put("state", state).put("attempts", attempts)
                .put("eventIndex", reference(new File(event, "event.json")))
                .put("rawContentInSummary", false).put("rootCause", "NOT_ESTABLISHED")
                .put("uploadState", "LOCAL_ONLY").put("systemConsistency", "NOT_ASSESSED");
        File post = new File(event, "post.json");
        if (post.exists()) result.put("postIndex", reference(post));
        result.put("export", FaultExports.summary(event));
        result.put("rawEvidence", FaultExports.rawSummary(event));
        return result;
    }
    private static String CandidateId(JSONObject event) throws Exception {
        return FaultSources.Candidate.parse(event.getJSONObject("source")).id();
    }
    static JSONObject reference(File file) throws Exception {
        read(file);
        // Hash the stored bytes, not a potentially reordered JSON serialization.
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > JSON_LIMIT) throw new IOException("INDEX_SIZE_LIMIT"); out.write(b, 0, n); }
            bytes = out.toByteArray();
        }
        return new JSONObject().put("path", file.getAbsolutePath()).put("bytes", bytes.length).put("sha256", hash(bytes));
    }
}
