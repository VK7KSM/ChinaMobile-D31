package net.elfradio.d31bootstrap.diagnostics.collection;

import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** 有界目录拆批。原件由调用方先保全再接纳；检查点只保存原件摘要，恢复时逐件重放。 */
public final class CollectionPlan {
    public interface EvidenceReader { JSONObject read(String sha256) throws Exception; }
    private static final int MAX_TASKS = 32768, MAX_CHILDREN = 4096;
    private static final long LIST_BYTES = 4L * 1024 * 1024;
    private static final Set<String> SPLIT_REASONS = new HashSet<String>(Arrays.asList(
            "ENTRY_LIMIT", "MANIFEST_LIMIT", "DEPTH_LIMIT", "BYTE_LIMIT", "FILE_BYTE_LIMIT", "TIME_LIMIT"));
    final JSONObject config;
    final TreeMap<String, Node> nodes = new TreeMap<String, Node>();
    final List<String> receipts = new ArrayList<String>();
    final Set<String> snapshots = new HashSet<String>();
    final Set<String> metadataGaps = new TreeSet<String>();
    final CollectionLimits limits;
    long readBytes, elapsedMs;
    int partialBatches, completeBatches;

    static final class Node {
        final String path;
        String phase = "COLLECT";
        JSONObject expansion;
        List<String> children = new ArrayList<String>();
        Node(String path) { this.path = path; }
    }

    public static CollectionPlan create(JSONObject identity, String sessionBinding, List<String> roots,
            CollectionLimits batchLimits, int maxTasks, long maxReadBytes, long maxElapsedMs) throws Exception {
        need(identity != null && batchLimits != null, "MISSING_CONFIG");
        Set<String> identityKeys = new HashSet<String>(Arrays.asList("snapshotId", "baselineId", "baselineRevision", "firmwareId", "build", "context"));
        Iterator<String> identityNames = identity.keys();
        while (identityNames.hasNext()) need(identityKeys.contains(identityNames.next()), "UNKNOWN_IDENTITY_FIELD");
        need(sessionBinding != null && sessionBinding.matches("[A-Za-z0-9_-]{1,128}"), "SESSION_BINDING_REQUIRED");
        need(maxTasks > 0 && maxTasks <= MAX_TASKS && maxReadBytes > 0 && maxReadBytes <= 64L * 1024 * 1024 * 1024
                && maxElapsedMs > 0 && maxElapsedMs <= 3600000, "PLAN_LIMIT_INVALID");
        ManifestCollector.validateIndependentScopes(roots);
        for (String root : roots) need(systemPath(root), "NON_SYSTEM_SCOPE_REFUSED");
        JSONObject b = new JSONObject().put("maxEntries", batchLimits.maxEntries).put("maxReadBytes", batchLimits.maxReadBytes)
                .put("maxFileBytes", batchLimits.maxFileBytes).put("durationMs", batchLimits.durationMs)
                .put("maxDepth", batchLimits.maxDepth).put("maxManifestChars", batchLimits.maxManifestChars)
                .put("validForMs", batchLimits.validForMs);
        JSONObject c = new JSONObject().put("schemaVersion", 1).put("identity", new JSONObject(identity.toString()))
                .put("sessionBinding", sessionBinding).put("roots", new JSONArray(roots)).put("batchLimits", b)
                .put("maxTasks", maxTasks).put("maxReadBytes", maxReadBytes).put("maxElapsedMs", maxElapsedMs);
        // 沿原清单解析器验证身份及上下文，验证过程不访问文件系统。
        JSONObject probe = new JSONObject(identity.toString()).put("schemaVersion", 1).put("role", "TARGET")
                .put("collectorVersion", ManifestCollector.VERSION).put("capturedAtMs", 0).put("validUntilMs", 0)
                .put("uptimeMs", 0).put("completeness", "PARTIAL").put("entries", new JSONArray())
                .put("scope", new JSONArray().put(new JSONObject().put("path", roots.get(0))
                        .put("state", "NOT_CHECKED").put("source", "PLAN").put("reason", "NOT_STARTED")));
        DiagnosticManifest.parse(probe);
        return new CollectionPlan(c, batchLimits);
    }

    private CollectionPlan(JSONObject c, CollectionLimits l) throws Exception {
        config = new JSONObject(c.toString()); limits = l;
        JSONArray roots = config.getJSONArray("roots");
        need(roots.length() <= config.getInt("maxTasks"), "PLAN_LIMIT_INVALID");
        for (int i = 0; i < roots.length(); i++) nodes.put(roots.getString(i), new Node(roots.getString(i)));
    }

    /** 沿原manifest入口固定产品目录；私人数据、伪文件系统和原始分区不纳入相同判定。 */
    private static boolean systemPath(String p) {
        for (String root : Arrays.asList("/system", "/vendor", "/product", "/odm", "/system_ext",
                "/data/local/d31-diagnostic-input", "/data/local/d31-patches", "/data/local/d31-system-support",
                "/data/local/d31-startup-handover", "/data/local/d31-recovery-entry", "/data/local/d31-rescue",
                "/data/local/d31-startup-curtain", "/data/system/devices/keylayout"))
            if (p.equals(root) || p.startsWith(root + "/")) return true;
        return false;
    }

    private Node pending() {
        for (Node n : nodes.values()) {
            if (n.phase.equals("COLLECT") || n.phase.equals("EXPAND")) return n;
            if (n.phase.equals("SEAL")) {
                boolean done = true;
                for (String child : n.children) done &= nodes.get(child).phase.equals("DONE");
                if (done) return n;
            }
        }
        return null;
    }

    /** 无设备访问；空返回值可能是完成，也可能是预算耗尽或证据阻塞，须读取聚合摘要。 */
    public JSONObject next() throws Exception {
        Node n = pending();
        if (n == null || readBytes >= config.getLong("maxReadBytes") || elapsedMs >= config.getLong("maxElapsedMs")
                || receipts.size() >= MAX_TASKS * 3) return null;
        return new JSONObject().put("path", n.path).put("phase", n.phase).put("sequence", receipts.size())
                .put("planSha256", digest(config));
    }

    /** 每次只执行一个有界只读批次，不推进状态；调用方保全返回原件后调用accept。 */
    public JSONObject collectNext(CollectionAccess access, CollectionAccess.Clock clock,
            String sessionBinding, String snapshotId) throws Exception {
        need(config.getString("sessionBinding").equals(sessionBinding), "SESSION_BINDING_CHANGED");
        JSONObject task = next(); need(task != null, "NO_RUNNABLE_BATCH");
        long remainingBytes = config.getLong("maxReadBytes") - readBytes;
        long remainingMs = config.getLong("maxElapsedMs") - elapsedMs;
        JSONObject event = new JSONObject().put("task", task).put("sessionBinding", sessionBinding);
        if (task.getString("phase").equals("COLLECT")) {
            JSONObject identity = config.getJSONObject("identity");
            identity = new JSONObject(identity.toString()).put("snapshotId", snapshotId);
            need(!snapshots.contains(snapshotId), "SNAPSHOT_REUSED");
            long bytes = Math.min(limits.maxReadBytes, remainingBytes);
            CollectionLimits bounded = new CollectionLimits(limits.maxEntries, bytes, Math.min(limits.maxFileBytes, bytes),
                    Math.min(limits.durationMs, remainingMs), limits.maxDepth, limits.maxManifestChars, limits.validForMs);
            ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity, task.getString("path"), bounded);
            event.put("manifest", result.manifest().toJson()).put("index", result.index());
        } else {
            long started = clock.elapsedRealtimeMillis();
            JSONObject proof = new JSONObject().put("state", "PARTIAL");
            long[] charged = {0};
            try {
                String p = task.getString("path");
                checkClock(clock, started, Math.min(remainingMs, 30000));
                CollectionAccess.Stat before = access.lstat(p);
                needIo(before != null && before.type.equals("directory"), "DIRECTORY_REQUIRED");
                List<String> first = listing(access, clock, p, before, started, remainingMs, remainingBytes, charged);
                List<String> second = listing(access, clock, p, before, started, remainingMs, remainingBytes, charged);
                needIo(before.same(access.lstat(p)) && first.equals(second), "UNSTABLE_DIRECTORY");
                checkClock(clock, started, Math.min(remainingMs, 30000));
                proof.put("state", "STABLE_DIRECT_CHILDREN").put("stat", stat(before)).put("names", new JSONArray(first));
            } catch (IOException failure) {
                proof.put("reason", failure instanceof CollectionAccess.Failure ? ((CollectionAccess.Failure) failure).code : "READ_FAILED");
            }
            long duration = clock.elapsedRealtimeMillis() - started;
            event.put("directory", proof).put("readBytes", charged[0]).put("elapsedMs", Math.max(0, duration));
        }
        return event;
    }

    private static List<String> listing(CollectionAccess access, CollectionAccess.Clock clock, String p,
            CollectionAccess.Stat stat, long started, long maxMs, long maxBytes, long[] charged) throws IOException {
        long time = checkClock(clock, started, Math.min(maxMs, 30000));
        long available = Math.min(LIST_BYTES, maxBytes - charged[0]);
        needIo(available > 0, "BYTE_LIMIT");
        CollectionAccess.Listing listing = access.list(p, stat, MAX_CHILDREN, available, time);
        needIo(listing != null && listing.bytesRead >= 0 && listing.bytesRead <= available, "LIST_CONTRACT");
        charged[0] += listing.bytesRead;
        needIo(listing.complete && listing.names.size() <= MAX_CHILDREN, "DIRECTORY_LIST_INCOMPLETE");
        TreeSet<String> names = new TreeSet<String>();
        long chars = 0;
        for (String name : listing.names) {
            needIo(name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                    && name.length() <= 255 && !name.contains("/") && !name.contains("\\")
                    && !name.matches(".*[\\p{Cntrl}].*"), "CHILD_NAME_INVALID");
            needIo(names.add(name), "DUPLICATE_CHILD"); chars += name.length();
            needIo(chars <= LIST_BYTES && p.length() + name.length() + 1 <= 4096, "LIST_LIMIT");
        }
        return new ArrayList<String>(names);
    }

    private static long checkClock(CollectionAccess.Clock clock, long start, long duration) throws IOException {
        long elapsed = clock.elapsedRealtimeMillis() - start;
        needIo(!Thread.currentThread().isInterrupted(), "CANCELLED");
        needIo(elapsed >= 0 && elapsed < duration, "TIME_LIMIT_OR_CLOCK_ROLLBACK");
        return duration - elapsed;
    }

    private static JSONObject stat(CollectionAccess.Stat s) throws Exception {
        return new JSONObject().put("device", s.device).put("inode", s.inode).put("size", s.size)
                .put("modified", s.modified).put("changed", s.changed).put("mode", s.mode).put("uid", s.uid).put("gid", s.gid);
    }

    /** 按顺序接纳已保全的原件；不接受伪造新scope，也不修改原manifest的完整性字段。 */
    public void accept(JSONObject input) throws Exception {
        need(input != null && input.toString().length() <= 8 * 1024 * 1024, "EVENT_LIMIT");
        JSONObject event = new JSONObject(input.toString());
        JSONObject task = next(); need(task != null && same(task, event.getJSONObject("task")), "TASK_BINDING_CHANGED");
        need(config.getString("sessionBinding").equals(event.getString("sessionBinding")), "SESSION_BINDING_CHANGED");
        Node node = nodes.get(task.getString("path"));
        long bytes, elapsed;
        String nextPhase;
        List<String> children = new ArrayList<String>();
        JSONObject expansion = null;
        String snapshot = null;
        boolean partial = false;
        Set<String> gaps = new TreeSet<String>();
        if (node.phase.equals("COLLECT")) {
            JSONObject m = DiagnosticManifest.parse(event.getJSONObject("manifest")).toJson();
            JSONObject identity = config.getJSONObject("identity");
            for (String k : Arrays.asList("baselineId", "baselineRevision", "firmwareId", "build", "context"))
                need(same(m.get(k), identity.get(k)), "IDENTITY_CHANGED");
            need(m.getString("role").equals("TARGET") && m.getString("collectorVersion").equals(ManifestCollector.VERSION), "COLLECTOR_CHANGED");
            JSONArray scopes = m.getJSONArray("scope");
            need(scopes.length() == 1 && scopes.getJSONObject(0).getString("path").equals(node.path), "SCOPE_CHANGED");
            snapshot = m.getString("snapshotId"); need(!snapshots.contains(snapshot), "SNAPSHOT_REUSED");
            JSONObject index = event.getJSONObject("index");
            need(index.getString("snapshotId").equals(snapshot) && index.getString("scope").equals(node.path)
                    && index.getString("state").equals(m.getString("completeness")), "INDEX_CHANGED");
            bytes = index.getLong("readBytes"); elapsed = index.getLong("elapsedMs");
            JSONArray entries = m.getJSONArray("entries");
            for (String name : Arrays.asList("selinux", "xattrs", "activeSource", "mountSource", "activation")) {
                boolean all = entries.length() > 0;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject field = entries.getJSONObject(i).getJSONObject("fields").optJSONObject(name);
                    all &= field != null && field.optString("state").equals("OBSERVED");
                }
                if (!all) gaps.add(name);
            }
            partial = !m.getString("completeness").equals("COMPLETE");
            nextPhase = ManifestBatchAggregation.contentClosed(m) ? "DONE" : "BLOCKED";
            if (partial && splittable(m, index, node.path)) nextPhase = "EXPAND";
        } else {
            JSONObject proof = event.getJSONObject("directory");
            bytes = event.getLong("readBytes"); elapsed = event.getLong("elapsedMs");
            nextPhase = "BLOCKED";
            if (proof.getString("state").equals("STABLE_DIRECT_CHILDREN")) {
                JSONArray names = proof.getJSONArray("names");
                need(names.length() <= MAX_CHILDREN, "LIST_LIMIT");
                TreeSet<String> unique = new TreeSet<String>();
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i), child = node.path + "/" + name;
                    need(!name.isEmpty() && name.length() <= 255 && !name.equals(".") && !name.equals("..")
                            && !name.contains("/") && !name.contains("\\") && !name.matches(".*[\\p{Cntrl}].*")
                            && child.length() <= 4096 && unique.add(name), "CHILD_NAME_INVALID");
                    children.add(child);
                }
                JSONObject st = proof.getJSONObject("stat");
                for (String k : Arrays.asList("device", "inode", "size", "modified", "changed", "mode", "uid", "gid"))
                    need(st.get(k) instanceof Number && !(st.get(k) instanceof Double) && !(st.get(k) instanceof Float), "STAT_INVALID");
                if (node.phase.equals("EXPAND")) {
                    for (String child : children) need(!nodes.containsKey(child), "OVERLAPPING_TASK");
                    // 达到任务上限也保全本次枚举，留下阻塞，不能在同一游标上无限重复。
                    if (nodes.size() + children.size() <= config.getInt("maxTasks")) {
                        expansion = proof; nextPhase = "SEAL";
                    }
                } else if (same(proof, node.expansion)) nextPhase = "DONE";
            }
        }
        need(bytes >= 0 && elapsed >= 0 && bytes <= config.getLong("maxReadBytes") - readBytes, "CHARGE_INVALID");
        need(elapsed <= 3600000, "ELAPSED_INVALID");
        // 在所有校验结束后才推进；异常不能吞掉待执行批次。
        String reference = digest(event);
        node.phase = nextPhase;
        if (expansion != null) {
            node.expansion = expansion; node.children = children;
            for (String child : children) nodes.put(child, new Node(child));
        }
        if (snapshot != null) { snapshots.add(snapshot); metadataGaps.addAll(gaps); if (partial) partialBatches++; else completeBatches++; }
        readBytes += bytes; elapsedMs += elapsed; receipts.add(reference);
    }

    private static boolean splittable(JSONObject m, JSONObject index, String path) throws Exception {
        JSONArray reasons = index.getJSONArray("reasons");
        if (reasons.length() == 0) return false;
        for (int i = 0; i < reasons.length(); i++) if (!SPLIT_REASONS.contains(reasons.getString(i))) return false;
        JSONArray entries = m.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String presence = e.getJSONObject("presence").optString("state");
            if (presence.equals("READ_FAILED") || presence.equals("UNSTABLE")) return false;
            JSONObject fields = e.getJSONObject("fields"); Iterator<String> names = fields.keys();
            while (names.hasNext()) {
                String state = fields.getJSONObject(names.next()).getString("state");
                if (state.equals("READ_FAILED") || state.equals("UNSTABLE")) return false;
            }
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (e.getString("path").equals(path)) {
                JSONObject type = e.getJSONObject("fields").optJSONObject("type");
                return type != null && type.optString("state").equals("OBSERVED") && type.optString("value").equals("directory");
            }
        }
        return false;
    }

    public JSONObject checkpoint() throws Exception {
        return new JSONObject().put("config", new JSONObject(config.toString())).put("receipts", new JSONArray(receipts));
    }

    /** 原件缺失、摘要变化、换启动会话或重放乱序一律拒绝，不凭检查点中的计数恢复。 */
    public static CollectionPlan restore(JSONObject checkpoint, String sessionBinding, EvidenceReader reader) throws Exception {
        JSONObject c = checkpoint.getJSONObject("config"), b = c.getJSONObject("batchLimits");
        need(c.getInt("schemaVersion") == 1 && c.getString("sessionBinding").equals(sessionBinding), "SESSION_BINDING_CHANGED");
        List<String> roots = new ArrayList<String>();
        JSONArray r = c.getJSONArray("roots"); for (int i = 0; i < r.length(); i++) roots.add(r.getString(i));
        CollectionPlan p = create(c.getJSONObject("identity"), sessionBinding, roots,
                new CollectionLimits(b.getInt("maxEntries"), b.getLong("maxReadBytes"), b.getLong("maxFileBytes"),
                        b.getLong("durationMs"), b.getInt("maxDepth"), b.getInt("maxManifestChars"), b.getLong("validForMs")),
                c.getInt("maxTasks"), c.getLong("maxReadBytes"), c.getLong("maxElapsedMs"));
        need(same(p.config, c), "CHECKPOINT_CONFIG_CHANGED");
        JSONArray refs = checkpoint.getJSONArray("receipts"); need(refs.length() <= MAX_TASKS * 3, "RECEIPT_LIMIT");
        for (int i = 0; i < refs.length(); i++) {
            String sha = refs.getString(i); need(sha.matches("[a-f0-9]{64}"), "RECEIPT_DIGEST_INVALID");
            JSONObject event = reader.read(sha); need(event != null && digest(event).equals(sha), "EVIDENCE_CHANGED"); p.accept(event);
        }
        return p;
    }

    public static String digest(JSONObject object) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical(object).getBytes("UTF-8"));
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }
    private static String canonical(Object o) throws Exception {
        if (o instanceof JSONObject) {
            JSONObject value = (JSONObject) o; TreeSet<String> keys = new TreeSet<String>();
            Iterator<String> it = value.keys(); while (it.hasNext()) keys.add(it.next());
            StringBuilder s = new StringBuilder("{");
            for (String k : keys) { if (s.length() > 1) s.append(','); s.append(JSONObject.quote(k)).append(':').append(canonical(value.get(k))); }
            return s.append('}').toString();
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o; StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) { if (i > 0) s.append(','); s.append(canonical(a.get(i))); }
            return s.append(']').toString();
        }
        return o instanceof String ? JSONObject.quote((String) o) : String.valueOf(o);
    }
    static boolean same(Object a, Object b) throws Exception { return canonical(a).equals(canonical(b)); }
    static void need(boolean ok, String code) { if (!ok) throw new IllegalArgumentException(code); }
    private static void needIo(boolean ok, String code) throws IOException { if (!ok) throw new CollectionAccess.Failure(code); }
}
