package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionSupport.*;

/** 按需复制父任务指定的故障文件前缀；不启动logcat、不发现默认路径、不建立监听。 */
public final class FaultEvidenceCollector {
    public static final int MAX_SOURCES = 64;
    public static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    private final CollectionAccess access;
    private final CollectionAccess.Clock clock;

    public FaultEvidenceCollector(CollectionAccess access, CollectionAccess.Clock clock) {
        if (access == null || clock == null) throw new IllegalArgumentException("MISSING_ACCESS");
        this.access = access; this.clock = clock;
    }

    public static final class Source {
        public final String id, category, path;
        public Source(String id, String category, String absolutePath) {
            this.id = CollectionSupport.id(id);
            if (category == null || !category.matches("LOGCAT|ANR|TOMBSTONE|BOOT|CUSTOM")) throw new IllegalArgumentException("INVALID_SOURCE_CATEGORY");
            this.category = category; this.path = CollectionSupport.path(absolutePath);
        }
    }

    /** 保存必须排他创建新原件；失败保留已写内容，不覆盖或删除旧证据。 */
    public interface Store {
        Receipt storeNew(String artifactId, byte[] bytes) throws IOException;
    }

    public static final class Receipt {
        public final String reference, sha256;
        public final long bytes;
        public Receipt(String reference, long bytes, String sha256) {
            this.reference = id(reference); this.bytes = bytes; this.sha256 = sha256;
        }
    }

    public JSONObject collect(String eventId, List<Source> sources, CollectionLimits limits, Store store) throws JSONException {
        id(eventId);
        if (sources == null || sources.size() > MAX_SOURCES || limits == null || store == null) throw new IllegalArgumentException("INVALID_FAULT_REQUEST");
        Set<String> ids = new HashSet<String>(), paths = new HashSet<String>();
        for (Source source : sources) {
            if (source == null || !ids.add(source.id) || !paths.add(source.path)) throw new IllegalArgumentException("DUPLICATE_FAULT_SOURCE");
            if ((eventId + "-" + source.id + ".bin").length() > 80) throw new IllegalArgumentException("ARTIFACT_ID_TOO_LONG");
        }
        long wall = clock.wallTimeMillis();
        if (wall < 0) throw new IllegalArgumentException("INVALID_CLOCK");
        Budget budget = new Budget(clock, limits.durationMs, limits.maxReadBytes);
        JSONArray items = new JSONArray();
        int complete = 0, unchecked = 0, failed = 0, partial = 0;
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            JSONObject item = new JSONObject().put("id", source.id).put("category", source.category)
                    .put("sourcePath", source.path).put("capturedAtMs", clock.wallTimeMillis()).put("offset", 0)
                    .put("rootCause", "NOT_ESTABLISHED");
            String state = "NOT_CHECKED", reason = "SOURCE_LIMIT";
            CollectionAccess.Stat before = null;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            boolean opened = false;
            if (i < limits.maxEntries) {
                try {
                    budget.remainingMs();
                    before = access.lstat(source.path);
                    if (before == null) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                    item.put("before", statJson(before));
                    budget.remainingMs();
                    if (!before.type.equals("file")) throw new CollectionAccess.Failure("NOT_REGULAR_FILE");
                    long cap = Math.min(Math.min(limits.maxFileBytes, MAX_CAPTURE_BYTES), budget.maximum - budget.readBytes);
                    if (before.size > 0 && cap == 0) throw new CollectionAccess.Failure("BYTE_LIMIT");
                    long expected = Math.min(before.size, cap);
                    try (CollectionAccess.Handle handle = access.openRegular(source.path, before)) {
                        if (!before.same(handle.stat())) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                        opened = true;
                        byte[] bytes = new byte[8192];
                        while (captured.size() < expected) {
                            budget.remainingMs();
                            int requested = (int) Math.min(bytes.length, expected - captured.size());
                            int read = handle.read(bytes, 0, requested);
                            if (read < 0) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                            if (read == 0 || read > requested) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                            captured.write(bytes, 0, read); budget.readBytes += read;
                            budget.remainingMs();
                        }
                        CollectionAccess.Stat after = handle.stat();
                        item.put("afterHandle", statJson(after));
                        if (!before.same(after)) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                    }
                    CollectionAccess.Stat after = access.lstat(source.path);
                    if (after == null) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                    item.put("afterPath", statJson(after));
                    if (!before.same(after)) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                    budget.remainingMs();
                    state = captured.size() == before.size ? "COMPLETE" : "TRUNCATED";
                    reason = state.equals("COMPLETE") ? "BOUNDED_READ_FINISHED" : "PREFIX_BYTE_LIMIT";
                } catch (IOException failure) {
                    reason = error(failure);
                    state = reason.startsWith("UNSTABLE") ? "UNSTABLE" : reason.endsWith("LIMIT")
                            || reason.equals("CANCELLED") || reason.equals("CLOCK_ROLLBACK") ? "NOT_CHECKED" : "READ_FAILED";
                    if (captured.size() > 0 && state.equals("NOT_CHECKED")) state = "TRUNCATED";
                }
            }
            item.put("capturedBytes", captured.size()).put("state", state).put("reason", reason);
            // 即使读取失败或超时，也先保存已经读到的前缀，避免异常路径丢失原始证据。
            if (opened && (captured.size() > 0 || (before != null && before.size == 0))) {
                byte[] bytes = captured.toByteArray();
                String hash = hex(digest().digest(bytes));
                String artifact = eventId + "-" + source.id;
                item.put("attemptedArtifact", artifact + ".bin").put("capturedSha256", hash);
                try {
                    Receipt receipt = store.storeNew(artifact, bytes);
                    if (receipt == null || receipt.bytes != bytes.length || !hash.equals(receipt.sha256)) throw new CollectionAccess.Failure("STORE_RECEIPT_MISMATCH");
                    item.put("artifact", receipt.reference).put("storedBytes", receipt.bytes).put("storedSha256", receipt.sha256).put("storedBytesKnown", true);
                } catch (IOException failure) {
                    state = "STORE_FAILED";
                    item.put("state", state).put("storeReason", error(failure)).put("storedBytesKnown", false);
                }
            }
            if (state.equals("COMPLETE")) complete++;
            else if (state.equals("NOT_CHECKED")) unchecked++;
            else if (state.equals("READ_FAILED") || state.equals("STORE_FAILED")) failed++;
            else partial++;
            items.put(item);
        }
        boolean withinTime;
        try { budget.remainingMs(); withinTime = true; } catch (IOException overrun) { withinTime = false; }
        return new JSONObject().put("schemaVersion", 1).put("collectorVersion", ManifestCollector.VERSION).put("eventId", eventId)
                .put("capturedAtMs", wall).put("state", complete == sources.size() && !sources.isEmpty() && withinTime ? "COMPLETE" : "PARTIAL")
                .put("counts", new JSONObject().put("total", sources.size()).put("complete", complete).put("unchecked", unchecked).put("failed", failed).put("partial", partial))
                .put("readBytes", budget.readBytes).put("elapsedMs", budget.elapsed()).put("withinReadDeadline", withinTime)
                .put("deadlineSemantics", "COOPERATIVE_READ_BUDGET_PRESERVE_ACQUIRED_BYTES")
                .put("atomicSnapshot", false).put("stabilityEvidence", "STAT_CHECKS_ONLY")
                .put("items", items).put("rawContentInIndex", false).put("defaultSources", false)
                .put("rootCause", "NOT_ESTABLISHED").put("systemConsistency", "NOT_ASSESSED");
    }

    private static JSONObject statJson(CollectionAccess.Stat stat) throws JSONException {
        return new JSONObject().put("type", stat.type).put("device", stat.device).put("inode", stat.inode)
                .put("size", stat.size).put("modified", stat.modified).put("changed", stat.changed)
                .put("mode", stat.mode).put("uid", stat.uid).put("gid", stat.gid);
    }
}
