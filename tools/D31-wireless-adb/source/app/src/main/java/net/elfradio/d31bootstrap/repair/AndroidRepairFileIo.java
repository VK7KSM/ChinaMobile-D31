package net.elfradio.d31bootstrap.repair;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** API23系统调用实现；固定父目录描述符，所有文件读写拒绝链接及特殊文件。 */
final class AndroidRepairFileIo implements RepairFileIo {
    private final Object libcore;
    private final Method getxattr, setxattr;

    AndroidRepairFileIo() throws Exception {
        Class<?> type = Class.forName("libcore.io.Os");
        libcore = Class.forName("libcore.io.Libcore").getField("os").get(null);
        // Android 6已有隐藏三参数版本；公开的两参数Os.getxattr要到API26才存在。
        getxattr = type.getMethod("getxattr", String.class, String.class, byte[].class);
        setxattr = type.getMethod("setxattr", String.class, String.class, byte[].class, int.class);
    }

    @Override public void environment() throws Exception {
        if (Os.getuid() != 0 || Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(Build.DEVICE)
                || !"hct6737t_66_m0".equals(Build.MODEL)) throw new SecurityException("仅支持已授权D31 root文件平台");
    }
    @Override public String build() { return Build.FINGERPRINT; }

    @Override public void trustedDirectory(File directory) throws Exception {
        try (Directory ignored = directory(directory.getPath())) { }
    }
    @Override public void privateDirectory(File directory) throws Exception {
        try (Parent parent = parent(directory)) {
            try { Os.mkdir(parent.leaf(), 0700); Os.fsync(parent.directory.fd()); }
            catch (ErrnoException exists) { if (exists.errno != OsConstants.EEXIST) throw exists; }
        }
        trustedDirectory(directory);
        try (Directory pinned = directory(directory.getPath())) {
            StructStat stat = Os.fstat(pinned.fd());
            if (stat.st_uid != 0 || stat.st_gid != 0 || (stat.st_mode & 07777) != 0700)
                throw new IOException("修复私有目录必须为root:root 0700");
        }
    }
    @Override public long available(File directory) throws Exception {
        try (Directory pinned = directory(directory.getPath())) {
            android.system.StructStatVfs stat = Os.fstatvfs(pinned.fd());
            if (stat.f_bavail < 0 || stat.f_frsize <= 0) throw new IOException("无法确认可用空间");
            return stat.f_bavail > Long.MAX_VALUE / stat.f_frsize ? Long.MAX_VALUE : stat.f_bavail * stat.f_frsize;
        }
    }

    @Override public Snapshot read(File file) throws Exception {
        try (Parent parent = parent(file); OpenFile opened = open(parent.leaf())) {
            Snapshot result = snapshot(opened);
            samePath(parent, opened.stat);
            return result;
        }
    }

    @Override public byte[] readRecord(File file) throws Exception {
        try (Parent parent = parent(file); OpenFile input = open(parent.leaf())) {
            if (input.stat.st_size > 32768) throw new IOException("元数据记录过大");
            byte[] bytes = readBytes(input.fd(), (int) input.stat.st_size);
            if (!stable(input.stat, Os.fstat(input.fd()))) throw new IOException("元数据记录读取期间变化");
            samePath(parent, input.stat); return bytes;
        }
    }

    @Override public void writeNew(File destination, byte[] bytes) throws Exception {
        if (bytes.length > 32768) throw new IOException("元数据记录过大");
        try (Parent parent = parent(destination)) {
            String temp = parent.directory.path() + "/.repair-record-" + UUID.randomUUID();
            FileDescriptor fd = Os.open(temp, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            try { write(fd, bytes, bytes.length); Os.fsync(fd); } finally { Os.close(fd); }
            absent(parent.leaf());
            // link排他提交完整记录，避免同名原件被rename覆盖；中断临时件不复用。
            Os.link(temp, parent.leaf()); Os.remove(temp); Os.fsync(parent.directory.fd());
        }
    }

    @Override public void copyNew(File source, File destination, Snapshot expected) throws Exception {
        try (Parent from = parent(source); OpenFile input = open(from.leaf()); Parent to = parent(destination)) {
            if (!expected.same(snapshot(input))) throw new IOException("复制源已变化");
            FileDescriptor out = Os.open(to.leaf(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            try { copy(input, out, expected); Os.fsync(out); } finally { Os.close(out); }
            samePath(from, input.stat); Os.fsync(to.directory.fd());
        }
        if (!read(destination).content(expected.hash, expected.bytes)) throw new IOException("事务副本回读不符");
    }

    @Override public void replace(File destination, File content, Snapshot expectedTarget, Snapshot expectedContent,
                                  Metadata originalMetadata) throws Exception {
        try (Parent target = parent(destination); OpenFile old = open(target.leaf());
             Parent source = parent(content); OpenFile input = open(source.leaf())) {
            if (!expectedTarget.same(snapshot(old)) || !expectedContent.same(snapshot(input)))
                throw new IOException("提交前文件身份变化");
            String temporary = target.directory.path() + "/.repair-switch-" + UUID.randomUUID();
            FileDescriptor out = Os.open(temporary, OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            try (OpenFile replacement = new OpenFile(out)) {
                copy(input, replacement.fd(), expectedContent);
                apply(replacement, originalMetadata);
                Os.fsync(replacement.fd());
                Snapshot prepared = snapshot(replacement);
                if (!prepared.content(expectedContent.hash, expectedContent.bytes) || !originalMetadata.same(prepared.metadata))
                    throw new IOException("临时替换文件内容或元数据不符");
                if (!expectedTarget.same(snapshot(old)) || !expectedContent.same(snapshot(input)))
                    throw new IOException("准备期间原文件变化");
                samePath(target, old.stat); samePath(source, input.stat);
                // 调用方的全局维护租约覆盖此窗口；不合作的root写入者不属于此互斥合同。
                RepairTransactions.checkInterrupted();
                Os.rename(temporary, target.leaf());
                Os.fsync(target.directory.fd());
            }
        }
    }

    private Snapshot snapshot(OpenFile input) throws Exception {
        StructStat before = Os.fstat(input.fd()); regular(before);
        Metadata metadata = metadata(input, before);
        Os.lseek(input.fd(), 0, OsConstants.SEEK_SET);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0; byte[] bytes = new byte[32768]; int read;
        while ((read = Os.read(input.fd(), bytes, 0, bytes.length)) != 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("文件读取已取消");
            count += read; if (count > RepairPlan.MAX_FILE_BYTES) throw new IOException("文件读取超过上限");
            digest.update(bytes, 0, read);
        }
        StructStat after = Os.fstat(input.fd());
        if (count != before.st_size || !stable(before, after) || !metadata.same(metadata(input, after)))
            throw new IOException("文件内容或元数据采集期间变化");
        input.stat = after;
        return new Snapshot(RepairFiles.hex(digest.digest()), count, after.st_dev + ":" + after.st_ino, metadata);
    }

    private void copy(OpenFile input, FileDescriptor out, Snapshot expected) throws Exception {
        Os.lseek(input.fd(), 0, OsConstants.SEEK_SET);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0; byte[] bytes = new byte[32768]; int read;
        while ((read = Os.read(input.fd(), bytes, 0, bytes.length)) != 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("文件复制已取消");
            count += read; if (count > expected.bytes) throw new IOException("复制源长度变化");
            write(out, bytes, read); digest.update(bytes, 0, read);
        }
        if (count != expected.bytes || !RepairFiles.hex(digest.digest()).equals(expected.hash)
                || !expected.same(snapshot(input))) throw new IOException("复制源摘要或元数据变化");
    }
    private static void write(FileDescriptor fd, byte[] bytes, int length) throws Exception {
        int offset = 0;
        while (offset < length) {
            int written = Os.write(fd, bytes, offset, length - offset);
            if (written <= 0) throw new IOException("文件写入未推进"); offset += written;
        }
    }
    private static byte[] readBytes(FileDescriptor fd, int length) throws Exception {
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < length) {
            int read = Os.read(fd, bytes, offset, length - offset);
            if (read <= 0) throw new IOException("元数据记录长度变化"); offset += read;
        }
        if (Os.read(fd, new byte[1], 0, 1) != 0) throw new IOException("元数据记录长度变化");
        return bytes;
    }

    private Metadata metadata(OpenFile file, StructStat stat) throws Exception {
        String path = file.path();
        for (String name : new String[]{"security.capability", "system.posix_acl_access"}) {
            if (xattr(path, name) != null) throw new IOException("首批文件不支持能力或ACL元数据");
        }
        return new Metadata(stat.st_uid, stat.st_gid, stat.st_mode & 07777, stat.st_mtime,
                requireSelinuxContext(xattr(path, "security.selinux")));
    }
    private void apply(OpenFile file, Metadata metadata) throws Exception {
        Os.fchown(file.fd(), metadata.uid, metadata.gid);
        Os.fchmod(file.fd(), metadata.mode);
        byte[] label = (metadata.context + '\0').getBytes(StandardCharsets.UTF_8);
        invoke(setxattr, file.path(), "security.selinux", label, 0);
        if (metadata.modified > Long.MAX_VALUE / 1000 || !new File(file.path()).setLastModified(metadata.modified * 1000))
            throw new IOException("修改时间恢复失败");
    }
    private byte[] xattr(String path, String name) throws Exception {
        byte[] bytes = new byte[4096];
        try {
            int length = (Integer) invoke(getxattr, path, name, bytes);
            if (length < 0 || length > bytes.length) throw new IOException("扩展属性长度无效");
            return Arrays.copyOf(bytes, length);
        } catch (ErrnoException missing) {
            if (absentXattr(name, missing.errno == OsConstants.ENODATA, missing.errno == OsConstants.ENOTSUP)) return null;
            throw missing;
        }
    }
    static boolean absentXattr(String name, boolean noData, boolean unsupported) {
        // rootfs可不支持ACL/能力；此例外不得扩展到必需的SELinux标签或其他错误。
        return noData || unsupported && ("security.capability".equals(name)
                || "system.posix_acl_access".equals(name) || "system.posix_acl_default".equals(name));
    }
    static String requireSelinuxContext(byte[] context) throws IOException {
        if (context == null || context.length < 2 || context[context.length - 1] != 0) throw new IOException("SELinux标签无法确认");
        return new String(context, 0, context.length - 1, StandardCharsets.UTF_8);
    }
    private Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(libcore, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static final class Directory implements Closeable {
        final ParcelFileDescriptor descriptor;
        Directory(FileDescriptor fd) throws Exception {
            try { descriptor = ParcelFileDescriptor.dup(fd); } finally { Os.close(fd); }
        }
        String path() { return "/proc/self/fd/" + descriptor.getFd(); }
        FileDescriptor fd() { return descriptor.getFileDescriptor(); }
        @Override public void close() throws IOException { descriptor.close(); }
    }
    private static final class Parent implements Closeable {
        final Directory directory; final String name;
        Parent(Directory directory, String name) { this.directory = directory; this.name = name; }
        String leaf() { return directory.path() + "/" + name; }
        @Override public void close() throws IOException { directory.close(); }
    }
    private static final class OpenFile implements Closeable {
        final ParcelFileDescriptor descriptor;
        StructStat stat;
        OpenFile(FileDescriptor fd) throws Exception {
            try { descriptor = ParcelFileDescriptor.dup(fd); } finally { Os.close(fd); }
            try { stat = Os.fstat(fd()); regular(stat); }
            catch (Exception failure) { descriptor.close(); throw failure; }
        }
        FileDescriptor fd() { return descriptor.getFileDescriptor(); }
        String path() { return "/proc/self/fd/" + descriptor.getFd(); }
        @Override public void close() throws IOException { descriptor.close(); }
    }
    private OpenFile open(String path) throws Exception {
        return new OpenFile(Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0));
    }
    private Parent parent(File file) throws Exception {
        String path = file.getPath();
        if (!file.isAbsolute() || path.contains("//") || path.contains("\\") || path.endsWith("/")) throw new IOException("文件路径无效");
        return new Parent(directory(file.getParent()), file.getName());
    }
    private Directory directory(String path) throws Exception {
        if (path == null || !path.startsWith("/")) throw new IOException("目录必须绝对路径");
        Directory current = openDirectory("/", false);
        String logical = "";
        try {
            for (String part : path.substring(1).split("/")) {
                if (part.isEmpty()) continue;
                if (part.equals(".") || part.equals("..")) throw new IOException("目录不能穿越");
                logical += "/" + part;
                Directory next = openDirectory(current.path() + "/" + part, logical.equals("/data"));
                try { current.close(); } catch (Exception failure) { next.close(); throw failure; }
                current = next;
            }
            Directory result = current; current = null; return result;
        } finally { if (current != null) current.close(); }
    }
    private Directory openDirectory(String path, boolean dataRoot) throws Exception {
        StructStat before = Os.lstat(path);
        // Android的/data由system:system持有且组可写；仅对此固定祖先接受系统属主。
        boolean trusted = before.st_uid == 0 && (before.st_mode & 0022) == 0;
        if (dataRoot) trusted = before.st_uid == 1000 && before.st_gid == 1000 && (before.st_mode & 07777) == 0771;
        if (!OsConstants.S_ISDIR(before.st_mode) || !trusted)
            throw new IOException("祖先目录非root独占写入或不是普通目录");
        Directory result = new Directory(Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0));
        try {
            if (!sameInode(before, Os.fstat(result.fd()))) throw new IOException("祖先目录身份变化");
            if (xattr(result.path(), "system.posix_acl_default") != null) throw new IOException("不接受继承ACL的目录");
            return result;
        } catch (Exception failure) { result.close(); throw failure; }
    }
    private static void regular(StructStat stat) throws IOException {
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1 || stat.st_size < 0
                || stat.st_size > RepairPlan.MAX_FILE_BYTES || (stat.st_mode & 07000) != 0)
            throw new IOException("文件含链接、特殊类型、特殊权限或长度超限");
    }
    private static boolean sameInode(StructStat a, StructStat b) { return a.st_dev == b.st_dev && a.st_ino == b.st_ino; }
    private static boolean stable(StructStat a, StructStat b) {
        return sameInode(a, b) && a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime
                && a.st_mode == b.st_mode && a.st_uid == b.st_uid && a.st_gid == b.st_gid && a.st_nlink == b.st_nlink;
    }
    private static void samePath(Parent parent, StructStat expected) throws Exception {
        if (!stable(expected, Os.lstat(parent.leaf()))) throw new IOException("路径当前文件身份变化");
    }
    private static void absent(String path) throws Exception {
        try { Os.lstat(path); }
        catch (ErrnoException missing) { if (missing.errno == OsConstants.ENOENT) return; throw missing; }
        throw new IOException("原件已存在，禁止覆盖");
    }
}
