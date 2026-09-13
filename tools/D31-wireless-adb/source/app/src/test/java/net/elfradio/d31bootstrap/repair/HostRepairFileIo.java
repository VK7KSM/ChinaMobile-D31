package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** 真实宿主文件与原子改名；仅Android属主、模式及标签用显式测试模型代替。 */
final class HostRepairFileIo implements RepairFileIo {
    interface Hook { void at(String event, File file) throws Exception; }
    final Map<String, Metadata> metadata = new HashMap<>();
    Hook hook = (event, file) -> { };
    int replacements;
    String fingerprint = "fixture-build";
    @Override public void environment() { }
    @Override public String build() { return fingerprint; }
    @Override public void privateDirectory(File directory) throws Exception {
        Files.createDirectories(directory.toPath()); trustedDirectory(directory);
    }
    @Override public void trustedDirectory(File directory) throws Exception {
        if (!directory.isDirectory() || !directory.getAbsoluteFile().equals(directory.getCanonicalFile()))
            throw new IOException("目录不可信");
    }
    @Override public long available(File directory) throws Exception { trustedDirectory(directory); return Long.MAX_VALUE; }
    @Override public Snapshot read(File file) throws Exception {
        if (!file.isFile() || !file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("非常规文件");
        BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Metadata saved = metadata.get(file.getPath());
        Metadata actual = new Metadata(saved == null ? 0 : saved.uid, saved == null ? 0 : saved.gid,
                saved == null ? 0600 : saved.mode, file.lastModified() / 1000,
                saved == null ? "u:object_r:system_data_file:s0" : saved.context);
        // Windows提供者可能不公开fileKey；离线夹具用路径及创建时间识别该文件。
        String identity = attributes.fileKey() == null ? file.getPath() + ":" + attributes.creationTime() : attributes.fileKey().toString();
        return new Snapshot(RepairFiles.sha256(file), file.length(), identity, actual);
    }
    void apply(File file, Metadata value) throws Exception {
        metadata.put(file.getPath(), value); Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(value.modified * 1000));
    }
    @Override public void copyNew(File source, File destination, Snapshot expected) throws Exception {
        if (!expected.same(read(source))) throw new IOException("复制源变化");
        hook.at("before-copy", destination); Files.copy(source.toPath(), destination.toPath());
        hook.at("after-copy", destination);
        if (!expected.same(read(source)) || !read(destination).content(expected.hash, expected.bytes)) throw new IOException("副本内容变化");
    }
    @Override public void writeNew(File file, byte[] bytes) throws Exception {
        hook.at("before-record", file); RepairFiles.writeNew(file, bytes); hook.at("after-record", file);
    }
    @Override public byte[] readRecord(File file) throws Exception { return RepairFiles.read(file, 32768); }
    @Override public void replace(File destination, File content, Snapshot expectedTarget,
                                  Snapshot expectedContent, Metadata value) throws Exception {
        if (!expectedTarget.same(read(destination)) || !expectedContent.same(read(content))) throw new IOException("提交前内容变化");
        Path temp = Files.createTempFile(destination.getParentFile().toPath(), ".repair-switch-", ".tmp");
        Files.copy(content.toPath(), temp, StandardCopyOption.REPLACE_EXISTING);
        apply(temp.toFile(), value); hook.at("metadata", temp.toFile());
        if (!value.same(read(temp.toFile()).metadata)) throw new IOException("临时文件元数据不符");
        hook.at("before-rename", destination);
        if (!expectedTarget.same(read(destination)) || !expectedContent.same(read(content))) throw new IOException("准备期间内容变化");
        Files.move(temp, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        metadata.put(destination.getPath(), value); replacements++; hook.at("after-rename", destination);
    }
}
