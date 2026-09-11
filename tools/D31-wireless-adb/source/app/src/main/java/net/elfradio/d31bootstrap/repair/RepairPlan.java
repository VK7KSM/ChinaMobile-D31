package net.elfradio.d31bootstrap.repair;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 明确批准的普通文件替换方案；证据摘要不构成执行授权。 */
public final class RepairPlan {
    public static final int SCHEMA = 1;
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    public final String taskId, planId, revision, deviceClass, buildFingerprint, evidenceSha256;
    public final List<Change> changes;
    public final List<Dependency> dependencies;

    public static final class Change {
        public final String id, path, originalSha256, targetSha256, artifact;
        public final long originalBytes, targetBytes;
        public final List<String> after;

        public Change(String id, String path, String originalSha256, long originalBytes,
                      String targetSha256, long targetBytes, String artifact, List<String> after) {
            this.id = token(id); this.path = path(path);
            this.originalSha256 = hash(originalSha256); this.targetSha256 = hash(targetSha256);
            this.originalBytes = size(originalBytes); this.targetBytes = size(targetBytes);
            this.artifact = token(artifact);
            this.after = Collections.unmodifiableList(new ArrayList<>(after));
            if (originalSha256.equals(targetSha256)) throw new IllegalArgumentException("不接受无内容变化的替换");
        }
    }

    public static final class Dependency {
        public final String path, sha256;
        public final long bytes;
        public Dependency(String path, String sha256, long bytes) {
            this.path = path(path); this.sha256 = hash(sha256); this.bytes = size(bytes);
        }
    }

    public RepairPlan(String taskId, String planId, String revision, String deviceClass,
                      String buildFingerprint, String evidenceSha256, List<Change> changes,
                      List<Dependency> dependencies) {
        this.taskId = token(taskId); this.planId = token(planId); this.revision = token(revision);
        this.deviceClass = token(deviceClass);
        if (buildFingerprint == null || buildFingerprint.isEmpty() || buildFingerprint.length() > 256
                || buildFingerprint.indexOf('\0') >= 0 || buildFingerprint.indexOf('\n') >= 0)
            throw new IllegalArgumentException("目标构建无效");
        this.buildFingerprint = buildFingerprint; this.evidenceSha256 = hash(evidenceSha256);
        if (changes.isEmpty() || changes.size() > 16 || dependencies.size() > 32)
            throw new IllegalArgumentException("事务文件数量超出范围");
        Set<String> ids = new HashSet<>(), paths = new HashSet<>();
        for (Change change : changes) {
            if (!paths.add(change.path) || ids.contains(change.id)) throw new IllegalArgumentException("目标重复");
            Set<String> seen = new HashSet<>();
            for (String previous : change.after) {
                if (!ids.contains(previous) || !seen.add(previous))
                    throw new IllegalArgumentException("依赖必须是此前已登记的不同变更，不能成环");
            }
            ids.add(change.id);
        }
        for (Dependency dependency : dependencies) {
            if (!paths.add(dependency.path)) throw new IllegalArgumentException("依赖路径重复或与目标重叠");
        }
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
        this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
    }

    /** 与JSON字段顺序无关；父任务授权必须绑定此摘要，不仅是taskId或证据。 */
    public String sha256() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(SCHEMA);
            for (String value : new String[]{taskId, planId, revision, deviceClass, buildFingerprint, evidenceSha256}) out.writeUTF(value);
            out.writeInt(changes.size());
            for (Change c : changes) {
                out.writeUTF(c.id); out.writeUTF(c.path); out.writeUTF(c.originalSha256); out.writeLong(c.originalBytes);
                out.writeUTF(c.targetSha256); out.writeLong(c.targetBytes); out.writeUTF(c.artifact);
                out.writeInt(c.after.size()); for (String previous : c.after) out.writeUTF(previous);
            }
            out.writeInt(dependencies.size());
            for (Dependency d : dependencies) { out.writeUTF(d.path); out.writeUTF(d.sha256); out.writeLong(d.bytes); }
        }
        return RepairFiles.sha256(bytes.toByteArray());
    }

    public JSONObject toJson() throws Exception {
        JSONArray files = new JSONArray(), prerequisites = new JSONArray();
        for (Change c : changes) files.put(new JSONObject().put("id", c.id).put("path", c.path)
                .put("original_sha256", c.originalSha256).put("original_bytes", c.originalBytes)
                .put("target_sha256", c.targetSha256).put("target_bytes", c.targetBytes)
                .put("artifact", c.artifact).put("after", new JSONArray(c.after)));
        for (Dependency d : dependencies) prerequisites.put(new JSONObject().put("path", d.path).put("sha256", d.sha256).put("bytes", d.bytes));
        return new JSONObject().put("schema", SCHEMA).put("task_id", taskId).put("plan_id", planId)
                .put("revision", revision).put("device_class", deviceClass).put("build", buildFingerprint)
                .put("evidence_sha256", evidenceSha256).put("changes", files).put("dependencies", prerequisites);
    }

    public static RepairPlan fromJson(JSONObject json) throws Exception {
        onlyKeys(json, "schema", "task_id", "plan_id", "revision", "device_class", "build", "evidence_sha256", "changes", "dependencies");
        if (integer(json, "schema") != SCHEMA) throw new IllegalArgumentException("不支持的方案版本");
        List<Change> files = new ArrayList<>(); List<Dependency> prerequisites = new ArrayList<>();
        JSONArray changes = json.getJSONArray("changes"), dependencies = json.getJSONArray("dependencies");
        for (int i = 0; i < changes.length(); i++) {
            JSONObject c = changes.getJSONObject(i); List<String> after = new ArrayList<>();
            onlyKeys(c, "id", "path", "original_sha256", "original_bytes", "target_sha256", "target_bytes", "artifact", "after");
            JSONArray order = c.getJSONArray("after"); for (int j = 0; j < order.length(); j++) after.add(order.getString(j));
            files.add(new Change(c.getString("id"), c.getString("path"), c.getString("original_sha256"),
                    integer(c, "original_bytes"), c.getString("target_sha256"), integer(c, "target_bytes"), c.getString("artifact"), after));
        }
        for (int i = 0; i < dependencies.length(); i++) {
            JSONObject d = dependencies.getJSONObject(i);
            onlyKeys(d, "path", "sha256", "bytes");
            prerequisites.add(new Dependency(d.getString("path"), d.getString("sha256"), integer(d, "bytes")));
        }
        return new RepairPlan(json.getString("task_id"), json.getString("plan_id"), json.getString("revision"),
                json.getString("device_class"), json.getString("build"), json.getString("evidence_sha256"), files, prerequisites);
    }

    private static void onlyKeys(JSONObject json, String... keys) {
        Set<String> allowed = new HashSet<>(Arrays.asList(keys));
        for (Iterator<String> it = json.keys(); it.hasNext();) {
            if (!allowed.contains(it.next())) throw new IllegalArgumentException("方案含有未支持的字段");
        }
    }

    static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))
            throw new IllegalArgumentException("方案标识无效");
        return value;
    }
    static String path(String value) {
        if (value == null || value.length() > 240 || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*"))
            throw new IllegalArgumentException("只接受允许目录内的逻辑相对文件路径");
        return value;
    }
    static String hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("需要小写SHA-256");
        return value;
    }
    static long size(long value) {
        if (value < 0 || value > MAX_FILE_BYTES) throw new IllegalArgumentException("文件长度超限");
        return value;
    }
    private static long integer(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).longValue())
            throw new IllegalArgumentException("文件长度必须为整数");
        return ((Number) value).longValue();
    }
}
