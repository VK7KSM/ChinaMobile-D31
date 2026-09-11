package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** D31 root静态系统支持文件平台；没有shell、网络、重载或自建维护锁。 */
public final class AndroidRepairPlatform implements RepairPlatform {
    public interface LeaseProvider { Lease acquire(String taskId) throws Exception; }

    public static Map<String, File> productionPaths() {
        Map<String, File> paths = new LinkedHashMap<>();
        paths.put("system-support/start.sh", new File("/data/local/d31-system-support/start.sh"));
        return Collections.unmodifiableMap(paths);
    }

    private final File journalRoot, payloadRoot, job;
    private final RepairPlan plan;
    private final LeaseProvider leases;
    private final String digest, approvedDigest;
    private final Map<String, File> targets;
    private final RepairFileIo io;
    private Thread owner;

    public AndroidRepairPlatform(File journalRoot, File payloadRoot, RepairPlan plan,
                                 LeaseProvider leases, String approvedDigest) throws Exception {
        this(journalRoot, payloadRoot, plan, leases, approvedDigest, productionPaths(), new AndroidRepairFileIo());
    }

    // 仅同包JUnit注入宿主文件调用；生产入口没有fixture开关或路径映射参数。
    AndroidRepairPlatform(File journalRoot, File payloadRoot, RepairPlan plan, LeaseProvider leases,
                          String approvedDigest, Map<String, File> paths, RepairFileIo io) throws Exception {
        if (plan == null || leases == null || io == null) throw new IllegalArgumentException("修复平台缺少绑定参数");
        this.journalRoot = absolute(journalRoot); this.payloadRoot = absolute(payloadRoot);
        this.plan = plan; this.leases = leases; this.digest = plan.sha256();
        this.approvedDigest = RepairPlan.hash(approvedDigest); this.io = io;
        this.job = new File(this.journalRoot, plan.taskId);
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
        for (RepairPlan.Change change : plan.changes) target(change.path);
        for (RepairPlan.Dependency dependency : plan.dependencies) target(dependency.path);
        for (File file : targets.values()) {
            if (inside(this.journalRoot, file) || inside(this.payloadRoot, file)
                    || inside(file.getParentFile(), this.journalRoot) || inside(file.getParentFile(), this.payloadRoot))
                throw new IllegalArgumentException("事务和载荷目录不得与目标目录重叠");
        }
        if (inside(this.journalRoot, this.payloadRoot) || inside(this.payloadRoot, this.journalRoot))
            throw new IllegalArgumentException("载荷与日志必须分离");
    }

    @Override public synchronized Lease acquire(String taskId) throws Exception {
        if (!plan.taskId.equals(taskId)) throw new SecurityException("平台绑定不同任务");
        if (owner != null) return null;
        Lease lease = leases.acquire(taskId);
        if (lease == null) return null;
        try {
            io.environment(); io.privateDirectory(journalRoot);
            owner = Thread.currentThread();
        } catch (Exception failure) { lease.close(); throw failure; }
        return new Lease() {
            private boolean closed;
            @Override public void close() throws Exception {
                synchronized (AndroidRepairPlatform.this) {
                    if (closed) return;
                    if (owner != Thread.currentThread()) throw new IllegalStateException("维护租约线程变化");
                    try { lease.close(); } finally { owner = null; closed = true; }
                }
            }
        };
    }

    @Override public boolean approved(String sha) throws Exception { held(); return digest.equals(sha) && digest.equals(approvedDigest); }
    @Override public String deviceClass() throws Exception { held(); io.environment(); return "D31"; }
    @Override public String buildFingerprint() throws Exception { held(); return io.build(); }
    @Override public Set<String> allowedPaths() { return targets.keySet(); }
    @Override public long availableBytes() throws Exception {
        held(); long bytes = Math.min(io.available(journalRoot), io.available(payloadRoot));
        for (RepairPlan.Change change : plan.changes) bytes = Math.min(bytes, io.available(target(change.path).getParentFile()));
        return bytes;
    }
    @Override public FileState inspect(String path) throws Exception {
        held(); RepairFileIo.Snapshot snapshot = io.read(target(path));
        return FileState.regular(snapshot.hash, snapshot.bytes);
    }

    @Override public void backup(RepairPlan.Change change, File destination) throws Exception {
        held(); int index = index(change); artifact(destination, "backup", index);
        RepairFileIo.Snapshot original = io.read(target(change.path));
        if (!original.content(change.originalSha256, change.originalBytes)) throw new IOException("备份原像已变化");
        File record = metadataFile(index);
        io.privateDirectory(record.getParentFile());
        if (record.exists()) {
            if (!original.same(original(index))) throw new IOException("中断后原像内容或元数据变化");
        } else {
            String snapshot = original.json().toString();
            JSONObject value = new JSONObject().put("plan_sha256", digest).put("path", change.path)
                    .put("snapshot", snapshot).put("snapshot_sha256", RepairFiles.sha256(snapshot.getBytes(StandardCharsets.UTF_8)));
            io.writeNew(record, value.toString().getBytes(StandardCharsets.UTF_8));
        }
        io.copyNew(target(change.path), destination, original);
        if (!original.same(io.read(target(change.path)))) throw new IOException("备份期间现场变化");
    }

    @Override public void stage(RepairPlan.Change change, File destination) throws Exception {
        held(); int index = index(change); artifact(destination, "stage", index);
        if (!original(index).same(io.read(target(change.path)))) throw new IOException("暂存前原像元数据变化");
        File source = new File(payloadRoot, change.artifact);
        RepairFileIo.Snapshot snapshot = io.read(source);
        if (!snapshot.content(change.targetSha256, change.targetBytes)) throw new IOException("本地载荷摘要不符");
        io.copyNew(source, destination, snapshot);
    }

    @Override public void replace(String path, File content, FileState expected) throws Exception {
        held(); int index = index(path); RepairPlan.Change change = plan.changes.get(index);
        boolean rollback = content.getAbsoluteFile().equals(new File(job, "backup/" + index + ".bin"));
        artifact(content, rollback ? "backup" : "stage", index);
        RepairFileIo.Snapshot original = original(index), current = io.read(target(path)), source = io.read(content);
        if (!current.content(expected.sha256, expected.bytes)) throw new IOException("切换前现场内容变化");
        if (rollback) {
            if (!current.content(change.targetSha256, change.targetBytes)
                    || !source.content(change.originalSha256, change.originalBytes)) throw new IOException("恢复内容不在本方案中");
        } else {
            if (!current.same(original) || !source.content(change.targetSha256, change.targetBytes))
                throw new IOException("切换原像元数据或暂存内容变化");
        }
        io.replace(target(path), content, current, source, original.metadata);
        RepairFileIo.Snapshot actual = io.read(target(path));
        if (!actual.content(source.hash, source.bytes) || !actual.metadata.same(original.metadata))
            throw new IOException("切换后内容或元数据回读不符");
    }

    @Override public boolean verify(RepairPlan proposed) throws Exception {
        held(); if (!digest.equals(proposed.sha256())) return false;
        for (int i = 0; i < plan.changes.size(); i++) {
            RepairPlan.Change change = plan.changes.get(i); RepairFileIo.Snapshot actual = io.read(target(change.path));
            if (!actual.content(change.targetSha256, change.targetBytes) || !actual.metadata.same(original(i).metadata)) return false;
        }
        return true;
    }
    @Override public boolean verifyRestored(RepairPlan.Change change) throws Exception {
        held(); RepairFileIo.Snapshot actual = io.read(target(change.path)), original = original(index(change));
        return actual.content(change.originalSha256, change.originalBytes) && actual.metadata.same(original.metadata);
    }

    private RepairFileIo.Snapshot original(int index) throws Exception {
        JSONObject record = new JSONObject(new String(io.readRecord(metadataFile(index)), StandardCharsets.UTF_8));
        String snapshot = record.getString("snapshot"); RepairPlan.Change change = plan.changes.get(index);
        if (!digest.equals(record.getString("plan_sha256")) || !change.path.equals(record.getString("path"))
                || !RepairFiles.sha256(snapshot.getBytes(StandardCharsets.UTF_8)).equals(record.getString("snapshot_sha256")))
            throw new IOException("原像元数据记录损坏或属于其他方案");
        RepairFileIo.Snapshot original = RepairFileIo.Snapshot.parse(new JSONObject(snapshot));
        if (!original.content(change.originalSha256, change.originalBytes)) throw new IOException("原像元数据摘要不符");
        return original;
    }
    private File metadataFile(int index) { return new File(job, "metadata/" + index + ".json"); }
    private int index(RepairPlan.Change change) throws Exception {
        int i = index(change.path);
        RepairPlan.Change bound = plan.changes.get(i);
        if (!bound.id.equals(change.id) || !bound.originalSha256.equals(change.originalSha256)
                || !bound.targetSha256.equals(change.targetSha256) || bound.originalBytes != change.originalBytes
                || bound.targetBytes != change.targetBytes || !bound.artifact.equals(change.artifact)
                || !bound.after.equals(change.after)) throw new SecurityException("变更未绑定本平台方案");
        return i;
    }
    private int index(String path) throws IOException {
        for (int i = 0; i < plan.changes.size(); i++) if (plan.changes.get(i).path.equals(path)) return i;
        throw new IOException("只读依赖不能作为改写目标");
    }
    private void artifact(File file, String kind, int index) throws Exception {
        if (!absolute(file).equals(new File(job, kind + "/" + index + ".bin"))) throw new SecurityException("事务副本路径不符");
        io.trustedDirectory(file.getParentFile());
    }
    private File target(String logical) throws IOException {
        File file = targets.get(logical); if (file == null) throw new IOException("目标不在固定可信映射");
        return file;
    }
    private synchronized void held() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("未持有本任务维护租约");
    }
    private static File absolute(File file) throws IOException {
        if (file == null || !file.isAbsolute() || !file.equals(file.getCanonicalFile())) throw new IOException("平台路径必须是无链接的绝对路径");
        return file;
    }
    private static boolean inside(File directory, File file) {
        return directory.equals(file) || file.getPath().startsWith(directory.getPath() + File.separator);
    }
}
