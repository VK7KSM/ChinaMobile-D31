package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 仅用于JUnit的真实临时文件适配器，不编入APK。 */
final class TemporaryRepairPlatform implements RepairPlatform {
    interface Hook { void at(String event) throws Exception; }
    static final class InterruptedProcess extends Error { }
    final File root, payloads;
    final Set<String> allowed = new HashSet<>();
    final Set<String> unreadable = new HashSet<>();
    final Map<String, Integer> counts = new HashMap<>();
    final List<String> switches = new ArrayList<>();
    String approvedDigest, model = "D31", build = "fixture-build";
    boolean busy, acquired, healthy = true;
    long space = Long.MAX_VALUE;
    Hook hook = event -> { };

    TemporaryRepairPlatform(File root) throws Exception {
        this.root = root.getCanonicalFile(); this.payloads = new File(this.root, "payloads");
        Files.createDirectories(payloads.toPath());
    }

    @Override public Lease acquire(String id) {
        if (busy || acquired) return null;
        acquired = true;
        return () -> acquired = false;
    }
    @Override public boolean approved(String digest) { return digest.equals(approvedDigest); }
    @Override public String deviceClass() { return model; }
    @Override public String buildFingerprint() { return build; }
    @Override public Set<String> allowedPaths() { return new HashSet<>(allowed); }
    @Override public long availableBytes() { return space; }

    File target(String logical) throws IOException {
        if (!allowed.contains(logical)) throw new IOException("未允许路径");
        File file = new File(root, logical).getAbsoluteFile();
        if (!file.equals(file.getCanonicalFile()) || !file.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("目标路径含链接或越界");
        return file;
    }

    @Override public FileState inspect(String path) throws Exception {
        if (unreadable.contains(path)) throw new IOException("读取失败");
        File file = target(path);
        if (!file.exists()) return FileState.missing();
        if (!file.isFile()) return FileState.other();
        return FileState.regular(RepairFiles.sha256(file), file.length());
    }
    @Override public void backup(RepairPlan.Change c, File destination) throws Exception {
        requireLease(); hook.at("before-backup-" + c.id);
        Files.copy(target(c.path).toPath(), destination.toPath());
        count("backup-" + c.id); hook.at("after-backup-" + c.id);
    }
    @Override public void stage(RepairPlan.Change c, File destination) throws Exception {
        requireLease(); hook.at("before-stage-" + c.id);
        Files.copy(new File(payloads, c.artifact).toPath(), destination.toPath());
        count("stage-" + c.id); hook.at("after-stage-" + c.id);
    }
    @Override public void replace(String path, File content, FileState expected) throws Exception {
        requireLease(); String direction = content.getParentFile().getName().equals("backup") ? "restore" : "switch";
        String id = new File(path).getName().substring(0, 1);
        count("call-" + direction + "-" + id);
        hook.at("before-" + direction + "-" + id);
        if (!inspect(path).matches(expected.sha256, expected.bytes)) throw new IOException("切换前比较失败");
        File target = target(path);
        Path temporary = target.toPath().resolveSibling(target.getName() + ".replace-" + UUID.randomUUID());
        Files.copy(content.toPath(), temporary);
        if (!inspect(path).matches(expected.sha256, expected.bytes)) throw new IOException("提交前目标变化");
        Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        switches.add(direction + "-" + id); count(direction + "-" + id);
        hook.at("after-" + direction + "-" + id);
    }
    @Override public boolean verify(RepairPlan plan) throws Exception { requireLease(); hook.at("verify"); return healthy; }
    void count(String name) { counts.put(name, countOf(name) + 1); }
    int countOf(String name) { return counts.getOrDefault(name, 0); }
    private void requireLease() { if (!acquired) throw new AssertionError("未持维护锁"); }

    void write(String logical, byte[] bytes) throws Exception {
        allowed.add(logical); File file = target(logical);
        Files.createDirectories(file.getParentFile().toPath()); Files.write(file.toPath(), bytes);
    }
}
