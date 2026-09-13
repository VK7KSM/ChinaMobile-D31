package net.elfradio.d31bootstrap.faults;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess;

/** Streaming names never accumulate in a directory-sized Java collection. */
interface FaultDirectoryWalker {
    interface Metadata { CollectionAccess.Stat lstat(String name) throws IOException; }
    interface Visitor { void name(String name, Metadata metadata) throws IOException; }
    final class ScopedMetadata implements Metadata, java.io.Closeable {
        private final Metadata delegate;
        private boolean open = true;
        ScopedMetadata(Metadata delegate) { this.delegate = delegate; }
        @Override public synchronized CollectionAccess.Stat lstat(String name) throws IOException {
            if (!open) throw new CollectionAccess.Failure("DIRECTORY_SCOPE_CLOSED");
            Android.checkChild(name);
            return delegate.lstat(name);
        }
        @Override public synchronized void close() { open = false; }
    }
    String walk(String path, CollectionAccess.Stat expected, CollectionAccess.Clock clock,
                long deadline, Visitor visitor) throws IOException;

    final class Android implements FaultDirectoryWalker {
        static final long MAX_BYTES = 4L * 1048576;
        @Override public String walk(String path, CollectionAccess.Stat expected, CollectionAccess.Clock clock,
                                     long deadline, Visitor visitor) throws IOException {
            FileDescriptor fd = null;
            Process process = null;
            try {
                fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
                StructStat before = Os.fstat(fd);
                if (!OsConstants.S_ISDIR(before.st_mode) || !same(expected, before))
                    throw new CollectionAccess.Failure("UNSTABLE_DIRECTORY");
                try (ParcelFileDescriptor pinned = ParcelFileDescriptor.dup(fd)) {
                    Os.close(fd); fd = null;
                    String root = "/proc/" + android.os.Process.myPid() + "/fd/" + pinned.getFd() + "/.";
                    process = new ProcessBuilder("/system/bin/busybox", "find", root,
                            "-mindepth", "1", "-maxdepth", "1", "-print0").redirectErrorStream(true).start();
                    try (InputStream output = process.getInputStream();
                         ScopedMetadata metadata = new ScopedMetadata(name -> {
                             try { return normalized(Os.lstat("/proc/self/fd/" + pinned.getFd() + "/" + name)); }
                             catch (android.system.ErrnoException failure) { throw new CollectionAccess.Failure("SOURCE_STAT_FAILED"); }
                         })) {
                        ByteArrayOutputStream record = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096]; long consumed = 0;
                        while (true) {
                            if (Thread.currentThread().isInterrupted()) return "CANCELLED";
                            if (clock.elapsedRealtimeMillis() >= deadline) return "SCAN_TIME_LIMIT";
                            int available = output.available(), exit = 0; boolean exited;
                            try { exit = process.exitValue(); exited = true; }
                            catch (IllegalThreadStateException running) { exited = false; }
                            if (available == 0 && exited) {
                                if (exit != 0 || record.size() != 0) return "LIST_FAILED";
                                return same(expected, Os.fstat(pinned.getFileDescriptor()))
                                        ? "ENUMERATION_FINISHED" : "UNSTABLE_DIRECTORY";
                            }
                            if (available == 0) { Thread.sleep(1); continue; }
                            if (consumed >= MAX_BYTES) return "SCAN_BYTE_LIMIT";
                            int n = output.read(buffer, 0, (int) Math.min(Math.min(available, buffer.length), MAX_BYTES - consumed));
                            if (n <= 0) return "LIST_FAILED";
                            consumed += n;
                            for (int i = 0; i < n; i++) {
                                if (clock.elapsedRealtimeMillis() >= deadline) return "SCAN_TIME_LIMIT";
                                if (buffer[i] != 0) {
                                    if (record.size() >= 8192) return "UNREPRESENTABLE_CHILD";
                                    record.write(buffer[i]); continue;
                                }
                                String name = child(root, record.toByteArray());
                                record.reset();
                                visitor.name(name, metadata);
                            }
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return "CANCELLED";
            } catch (android.system.ErrnoException failure) {
                throw new CollectionAccess.Failure("DIRECTORY_OPEN_FAILED");
            } finally {
                if (process != null) {
                    process.destroy(); close(process.getInputStream()); close(process.getErrorStream()); close(process.getOutputStream());
                }
                if (fd != null) try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
            }
        }
        private static boolean same(CollectionAccess.Stat expected, StructStat actual) {
            return sameDirectory(expected, actual.st_dev, actual.st_ino, actual.st_size, actual.st_mtime,
                    actual.st_ctime, actual.st_mode, actual.st_uid, actual.st_gid);
        }
        private static CollectionAccess.Stat normalized(StructStat actual) {
            return normalized(actual.st_dev, actual.st_ino, actual.st_size, actual.st_mtime,
                    actual.st_ctime, actual.st_mode, actual.st_uid, actual.st_gid);
        }
        static CollectionAccess.Stat normalized(long device, long inode, long size, long modified,
                                                long changed, int mode, int uid, int gid) {
            int type = mode & 0170000;
            String kind = type == 0100000 ? "file" : type == 0040000 ? "directory"
                    : type == 0120000 ? "symlink" : type == 0060000 ? "block" : "unsupported";
            return new CollectionAccess.Stat(kind, device, inode, Math.max(0, size), modified, changed,
                    mode & 07777, uid & 0xffffffffL, gid & 0xffffffffL);
        }
        static boolean sameDirectory(CollectionAccess.Stat expected, long device, long inode, long size,
                                     long modified, long changed, int mode, int uid, int gid) {
            // Match AndroidCollectionAccess.stat normalization; check Linux S_IFMT/S_IFDIR separately.
            return "directory".equals(expected.type) && (mode & 0170000) == 0040000
                    && expected.device == device && expected.inode == inode && expected.size == Math.max(0, size)
                    && expected.modified == modified && expected.changed == changed
                    && expected.mode == (mode & 07777) && expected.uid == (uid & 0xffffffffL) && expected.gid == (gid & 0xffffffffL);
        }
        static String child(String root, byte[] record) throws IOException {
            String printed = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(record)).toString();
            if (!printed.startsWith(root + "/")) throw new CollectionAccess.Failure("LIST_PREFIX_MISMATCH");
            String name = printed.substring(root.length() + 1);
            checkChild(name);
            return name;
        }
        static void checkChild(String name) throws IOException {
            if (name == null || name.isEmpty() || name.length() > 255 || name.contains("/") || name.contains("\\") || name.indexOf(0) >= 0
                    || name.equals(".") || name.equals("..")) throw new CollectionAccess.Failure("UNREPRESENTABLE_CHILD");
        }
        private static void close(java.io.Closeable closeable) { try { closeable.close(); } catch (IOException ignored) { } }
    }
}
