package net.elfradio.d31bootstrap.management;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.ErrnoException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/** 固定root私有路径，拒绝链接/多硬链接/权限放宽/打开前后身份变化。 */
final class NetworkAndroidFiles implements NetworkChangeTransactionJournal.Protection {
    static final File ROOT = new File("/data/local/d31-remote/runtime/network");
    static void requireDevice() throws IOException {
        if (Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IOException("NETWORK_PLATFORM_REJECTED");
    }
    static void ancestors() throws Exception {
        trustedAncestors(ROOT);
    }
    private static void trustedAncestors(File child) throws Exception {
        for (File path = child.getParentFile(); !path.getPath().equals("/data"); path = path.getParentFile()) {
            StructStat stat = Os.lstat(path.getPath());
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 0 || (stat.st_mode & 0022) != 0
                    || !path.getAbsoluteFile().equals(path.getCanonicalFile())) throw new IOException("NETWORK_PARENT_REJECTED");
        }
    }
    static boolean exists(File file) throws Exception {
        try { Os.lstat(file.getPath()); return true; }
        catch (ErrnoException error) { if (error.errno == OsConstants.ENOENT) return false; throw error; }
    }
    static void directory(File path) throws Exception {
        if (!(path.equals(ROOT) || path.getAbsolutePath().startsWith(ROOT.getPath() + "/")) || !path.getAbsoluteFile().equals(path.getCanonicalFile()))
            throw new IOException("NETWORK_DIRECTORY_REJECTED");
        StructStat stat = Os.lstat(path.getPath());
        requirePrivateMetadata(stat.st_mode, stat.st_uid, stat.st_nlink, true);
    }
    static StructStat regular(File file) throws Exception {
        directory(file.getParentFile());
        StructStat stat = Os.lstat(file.getPath());
        requirePrivateMetadata(stat.st_mode, stat.st_uid, stat.st_nlink, false);
        return stat;
    }
    static void same(StructStat before, StructStat after) throws IOException {
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || after.st_uid != 0
                || after.st_nlink != 1 || !OsConstants.S_ISREG(after.st_mode) || (after.st_mode & 0777) != 0600)
            throw new IOException("NETWORK_FILE_IDENTITY_CHANGED");
    }
    static void requirePrivateMetadata(int mode, int uid, long links, boolean directory) throws IOException {
        if (uid != 0 || (mode & 0170000) != (directory ? 0040000 : 0100000)
                || (mode & 07777) != (directory ? 0700 : 0600) || (!directory && links != 1))
            throw new IOException("NETWORK_PRIVATE_METADATA_REJECTED");
    }
    static void syncDirectory(File directory) throws Exception {
        FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("NETWORK_SYNC_NOT_DIRECTORY");
            Os.fsync(fd);
        } finally { Os.close(fd); }
    }
    static void initialize() throws Exception {
        requireDevice(); ancestors();
        if (!ROOT.exists()) {
            Os.mkdir(ROOT.getPath(), 0700); syncDirectory(ROOT.getParentFile());
            create(new File(ROOT, "wifi-enabled.journal"), new byte[0]);
            StructStat journal = regular(new File(ROOT, "wifi-enabled.journal"));
            write(new File(ROOT, "initialized.json"), new JSONObject().put("dev", journal.st_dev).put("ino", journal.st_ino));
        }
        directory(ROOT);
        new NetworkAndroidFiles().beforeOpen(new File(ROOT, "wifi-enabled.journal"));
    }
    static File task(String id) throws Exception {
        NetworkChangeTransaction.token(id); initialize();
        File folder = new File(ROOT, id);
        if (!folder.exists()) { Os.mkdir(folder.getPath(), 0700); syncDirectory(ROOT); }
        directory(folder); return folder;
    }
    static void create(File file, byte[] bytes) throws Exception {
        directory(file.getParentFile());
        FileDescriptor fd = Os.open(file.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
        try {
            same(regular(file), Os.fstat(fd));
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fd);
                 OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) { out.write(bytes); out.flush(); Os.fsync(fd); }
        } finally { Os.close(fd); }
        syncDirectory(file.getParentFile());
    }
    static void write(File file, JSONObject value) throws Exception {
        if (exists(file)) regular(file);
        File next = new File(file.getParentFile(), "pending-" + UUID.randomUUID().toString());
        create(next, value.toString().getBytes(StandardCharsets.UTF_8));
        if (exists(file)) regular(file);
        Os.rename(next.getPath(), file.getPath()); syncDirectory(file.getParentFile());
    }
    static JSONObject read(File file) throws Exception {
        StructStat before = regular(file);
        FileDescriptor fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
        try {
            same(before, Os.fstat(fd));
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fd);
                 InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                return new JSONObject(text(input, 8192));
            }
        } finally { Os.close(fd); }
    }
    static String text(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
        while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > limit) throw new IOException("NETWORK_READ_LIMIT"); bytes.write(buffer, 0, n); }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
    static String proc(String path, int limit) throws Exception {
        try (InputStream input = new FileInputStream(path)) { return text(input, limit); }
    }
    static String boot() throws Exception {
        String boot = proc("/proc/sys/kernel/random/boot_id", 80).trim();
        if (!boot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new IOException("NETWORK_BOOT_UNKNOWN");
        return boot;
    }
    static File apk(String hash) throws Exception {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("NETWORK_APK_HASH_INVALID");
        String path = System.getenv("CLASSPATH");
        if (!("/data/local/d31-remote/releases/" + hash + "/remote.apk").equals(path))
            throw new IOException("NETWORK_PINNED_APK_REQUIRED");
        File file = new File(path);
        trustedAncestors(file);
        StructStat stat = Os.lstat(path);
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile()) || !OsConstants.S_ISREG(stat.st_mode)
                || stat.st_uid != 0 || (stat.st_mode & 0022) != 0) throw new IOException("NETWORK_APK_PATH_REJECTED");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
        StringBuilder actual = new StringBuilder(); for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.US, "%02x", b & 255));
        if (!hash.equals(actual.toString())) throw new IOException("NETWORK_APK_HASH_MISMATCH");
        return file;
    }
    @Override public void beforeOpen(File file) throws Exception {
        ancestors(); directory(ROOT); StructStat stat = regular(file);
        JSONObject init = read(new File(ROOT, "initialized.json"));
        File used = new File(ROOT, "used.json");
        boolean recorded = exists(used);
        if (recorded) read(used);
        if (stat.st_dev != init.getLong("dev") || stat.st_ino != init.getLong("ino")
                || (recorded && stat.st_size == 0)) throw new IOException("NETWORK_JOURNAL_REPLACED_OR_LOST");
    }
    @Override public void opened(File file, RandomAccessFile stream) throws Exception {
        beforeOpen(file); same(regular(file), Os.fstat(stream.getFD()));
    }
    @Override public void synced(File file) throws Exception {
        if (!exists(new File(ROOT, "used.json"))) write(new File(ROOT, "used.json"), new JSONObject().put("used", true));
        syncDirectory(ROOT);
    }
}
