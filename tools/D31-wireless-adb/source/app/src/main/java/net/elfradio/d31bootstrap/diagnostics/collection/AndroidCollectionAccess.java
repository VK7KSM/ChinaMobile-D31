package net.elfradio.d31bootstrap.diagnostics.collection;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** API23实际只读访问；目录枚举仅依赖调用方明确指定并验证的BusyBox。 */
public final class AndroidCollectionAccess implements CollectionAccess {
    private final String busybox;
    private final Clock clock;

    public AndroidCollectionAccess(String busyboxExecutable, Clock clock) {
        this.busybox = CollectionSupport.path(busyboxExecutable);
        if (clock == null) throw new IllegalArgumentException("MISSING_CLOCK");
        this.clock = clock;
    }

    public static Clock systemClock() {
        return new Clock() {
            @Override public long wallTimeMillis() { return System.currentTimeMillis(); }
            @Override public long elapsedRealtimeMillis() { return SystemClock.elapsedRealtime(); }
        };
    }

    @Override public Stat lstat(String absolutePath) throws IOException {
        try (Parent parent = parent(absolutePath)) { return stat(Os.lstat(parent.leaf())); }
        catch (ErrnoException error) { throw failure(error); }
    }

    @Override public String readLink(String absolutePath) throws IOException {
        try (Parent parent = parent(absolutePath)) { return Os.readlink(parent.leaf()); }
        catch (ErrnoException error) { throw failure(error); }
    }

    @Override public Handle openRegular(String absolutePath, Stat expected) throws IOException {
        if (expected == null || !expected.type.equals("file")) throw new Failure("NOT_REGULAR_FILE");
        return new OwnedHandle(open(absolutePath, expected, false), DESCRIPTORS);
    }

    interface DescriptorIo {
        Stat stat(FileDescriptor fd) throws IOException;
        int read(FileDescriptor fd, byte[] bytes, int offset, int length) throws IOException;
        void close(FileDescriptor fd) throws IOException;
    }
    private static final DescriptorIo DESCRIPTORS = new DescriptorIo() {
        @Override public Stat stat(FileDescriptor fd) throws IOException {
            try { return AndroidCollectionAccess.stat(Os.fstat(fd)); }
            catch (ErrnoException error) { throw failure(error); }
        }
        @Override public int read(FileDescriptor fd, byte[] bytes, int offset, int length) throws IOException {
            try { return Os.read(fd, bytes, offset, length); }
            catch (ErrnoException error) { throw new Failure("READ_ERROR"); }
        }
        @Override public void close(FileDescriptor fd) throws IOException { closeFd(fd); }
    };

    // API23的FileInputStream(FileDescriptor)不拥有传入FD；Os.open的结果必须由本Handle显式关闭。
    static final class OwnedHandle implements Handle {
        private final FileDescriptor fd;
        private final DescriptorIo io;
        private boolean closed;
        OwnedHandle(FileDescriptor fd, DescriptorIo io) { this.fd = fd; this.io = io; }
        @Override public synchronized Stat stat() throws IOException { checkOpen(); return io.stat(fd); }
        @Override public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            checkOpen();
            if (bytes == null) throw new NullPointerException();
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            int count = io.read(fd, bytes, offset, length);
            return count == 0 ? -1 : count;
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            // 即使close报告错误，也不能重试关闭已被系统复用的整数FD。
            closed = true;
            io.close(fd);
        }
        private void checkOpen() throws IOException { if (closed) throw new Failure("HANDLE_CLOSED"); }
    }

    @Override public Listing list(String absolutePath, Stat expected, int maximumNames,
                                   long maximumBytes, long timeoutMs) throws IOException {
        if (expected == null || !expected.type.equals("directory") || maximumNames < 0 || maximumNames > 4096
                || maximumBytes < 0 || timeoutMs < 1) throw new Failure("INVALID_LIST_REQUEST");
        FileDescriptor fd = open(absolutePath, expected, true);
        ParcelFileDescriptor pinned = pinAndCloseOriginal(fd);
        List<String> names = new ArrayList<String>();
        long consumed = 0;
        java.lang.Process process = null;
        try (ParcelFileDescriptor directory = pinned) {
            String root = "/proc/" + android.os.Process.myPid() + "/fd/" + directory.getFd() + "/.";
            CollectionSupport.Budget time = new CollectionSupport.Budget(clock, timeoutMs, maximumBytes);
            // 不经shell；NUL分隔才能识别带空格或换行的真实文件名。目录描述符固定祖先身份。
            process = new ProcessBuilder(busybox, "find", root, "-mindepth", "1", "-maxdepth", "1", "-print0")
                    .redirectErrorStream(true).start();
            InputStream output = process.getInputStream();
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                try { time.remainingMs(); }
                catch (Failure limit) { return new Listing(names, false, limit.code, consumed); }
                int available = output.available();
                boolean exited;
                int exit = 0;
                try { exit = process.exitValue(); exited = true; }
                catch (IllegalThreadStateException running) { exited = false; }
                if (available == 0 && exited) {
                    boolean stable = expected.same(stat(Os.fstat(directory.getFileDescriptor()))) && expected.same(lstat(absolutePath));
                    if (exit != 0 || record.size() != 0) return new Listing(names, false, "LIST_FAILED", consumed);
                    return new Listing(names, stable, stable ? "ENUMERATION_FINISHED" : "UNSTABLE_DIRECTORY", consumed);
                }
                if (available == 0) {
                    try { Thread.sleep(1); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return new Listing(names, false, "CANCELLED", consumed); }
                    continue;
                }
                if (consumed >= maximumBytes) return new Listing(names, false, "BYTE_LIMIT", consumed);
                int wanted = (int) Math.min(Math.min(buffer.length, available), maximumBytes - consumed);
                int read = output.read(buffer, 0, wanted);
                if (read <= 0) return new Listing(names, false, "LIST_FAILED", consumed);
                consumed += read;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == 0) {
                        String printed;
                        try { printed = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(record.toByteArray())).toString(); }
                        catch (CharacterCodingException invalid) { return new Listing(names, false, "UNREPRESENTABLE_CHILD", consumed); }
                        record.reset();
                        if (!printed.startsWith(root + "/")) return new Listing(names, false, "LIST_FAILED", consumed);
                        String child = printed.substring(root.length() + 1);
                        try { CollectionSupport.child(absolutePath, child); }
                        catch (IllegalArgumentException invalid) { return new Listing(names, false, "UNREPRESENTABLE_CHILD", consumed); }
                        if (names.size() == maximumNames) return new Listing(names, false, "ENTRY_LIMIT", consumed);
                        names.add(child);
                    } else {
                        if (record.size() >= 8192) return new Listing(names, false, "UNREPRESENTABLE_CHILD", consumed);
                        record.write(buffer[i]);
                    }
                }
            }
        } catch (ErrnoException failure) { return new Listing(names, false, "LIST_FAILED", consumed); }
        catch (IOException failure) { return new Listing(names, false, "LIST_FAILED", consumed); }
        finally {
            if (process != null) {
                process.destroy();
                closeQuietly(process.getInputStream()); closeQuietly(process.getErrorStream()); closeQuietly(process.getOutputStream());
            }
        }
    }

    private static FileDescriptor open(String absolutePath, Stat expected, boolean directory) throws IOException {
        FileDescriptor fd = null;
        try {
            try (Parent parent = parent(absolutePath)) {
                int flags = OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK;
                fd = Os.open(parent.leaf(), flags, 0);
                Stat actual = stat(Os.fstat(fd));
                if (!expected.same(actual) || (directory && !actual.type.equals("directory"))) throw new Failure("UNSTABLE_FILE");
            }
            // 父目录关闭成功后才移交叶FD，避免父目录close异常时丢失所有权。
            FileDescriptor result = fd; fd = null; return result;
        } catch (ErrnoException error) { throw failure(error); }
        finally { if (fd != null) closeFd(fd); }
    }

    static FileDescriptor createArtifact(String directory, String name) throws IOException {
        CollectionSupport.id(name);
        String target = CollectionSupport.child(CollectionSupport.path(directory), name);
        FileDescriptor fd = null;
        try {
            try (Parent parent = parent(target)) {
                fd = Os.open(parent.leaf(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                        | OsConstants.O_NOFOLLOW, 0600);
            }
            FileDescriptor result = fd; fd = null; return result;
        } catch (ErrnoException error) { throw new Failure(error.errno == OsConstants.EEXIST ? "ARTIFACT_EXISTS" : "STORE_OPEN_FAILED"); }
        finally { if (fd != null) closeFd(fd); }
    }

    private static Parent parent(String absolutePath) throws IOException {
        CollectionSupport.path(absolutePath);
        ParcelFileDescriptor directory = null;
        try {
            directory = directory("/");
            if (absolutePath.equals("/")) return new Parent(directory, ".");
            String[] parts = absolutePath.substring(1).split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                ParcelFileDescriptor next = directory("/proc/self/fd/" + directory.getFd() + "/" + parts[i]);
                try { directory.close(); } catch (IOException error) { next.close(); throw error; }
                directory = next;
            }
            Parent result = new Parent(directory, parts[parts.length - 1]); directory = null; return result;
        } finally { if (directory != null && !absolutePath.equals("/")) directory.close(); }
    }

    private static ParcelFileDescriptor directory(String path) throws IOException {
        try {
            Stat before = stat(Os.lstat(path));
            if (!before.type.equals("directory")) throw new Failure("DIRECTORY_REQUIRED");
            FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW, 0);
            boolean handedOff = false;
            try {
                if (!before.same(stat(Os.fstat(fd)))) throw new Failure("UNSTABLE_DIRECTORY");
                handedOff = true;
                return pinAndCloseOriginal(fd);
            } finally { if (!handedOff) closeFd(fd); }
        } catch (ErrnoException error) { throw failure(error); }
    }

    private static final class Parent implements Closeable {
        final ParcelFileDescriptor directory;
        final String name;
        Parent(ParcelFileDescriptor directory, String name) { this.directory = directory; this.name = name; }
        String leaf() { return "/proc/self/fd/" + directory.getFd() + "/" + name; }
        @Override public void close() throws IOException { directory.close(); }
    }

    private static Stat stat(StructStat stat) {
        String type = OsConstants.S_ISREG(stat.st_mode) ? "file" : OsConstants.S_ISDIR(stat.st_mode) ? "directory"
                : OsConstants.S_ISLNK(stat.st_mode) ? "symlink" : OsConstants.S_ISBLK(stat.st_mode) ? "block" : "unsupported";
        return new Stat(type, stat.st_dev, stat.st_ino, Math.max(0, stat.st_size), stat.st_mtime, stat.st_ctime,
                stat.st_mode & 07777, stat.st_uid & 0xffffffffL, stat.st_gid & 0xffffffffL);
    }

    private static Failure failure(ErrnoException error) {
        return new Failure(error.errno == OsConstants.ENOENT ? "NOT_FOUND"
                : error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM ? "ACCESS_DENIED"
                : error.errno == OsConstants.ELOOP ? "LINK_REFUSED" : "READ_ERROR");
    }
    private static void closeFd(FileDescriptor fd) throws IOException {
        try { Os.close(fd); } catch (ErrnoException error) { throw failure(error); }
    }
    private static ParcelFileDescriptor pinAndCloseOriginal(FileDescriptor fd) throws IOException {
        ParcelFileDescriptor pinned = null;
        try { pinned = ParcelFileDescriptor.dup(fd); }
        finally {
            try { closeFd(fd); }
            catch (IOException closeFailure) {
                if (pinned != null) {
                    try { pinned.close(); } catch (IOException duplicateFailure) { closeFailure.addSuppressed(duplicateFailure); }
                }
                throw closeFailure;
            }
        }
        return pinned;
    }
    private static void closeQuietly(Closeable closeable) { try { closeable.close(); } catch (IOException ignored) { } }
}
