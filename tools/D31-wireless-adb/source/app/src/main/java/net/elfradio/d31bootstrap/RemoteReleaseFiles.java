package net.elfradio.d31bootstrap;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/** 仅收紧私有发布树权限；不改APK字节，也不跟随链接或接管其它属主。 */
final class RemoteReleaseFiles {
    static final class Entry {
        final int mode, uid; final long links, device, inode; final boolean canonical;
        Entry(int mode, int uid, long links, long device, long inode, boolean canonical) {
            this.mode=mode; this.uid=uid; this.links=links; this.device=device; this.inode=inode; this.canonical=canonical;
        }
    }
    interface Handle extends AutoCloseable {
        Entry stat() throws Exception;
        void chmod(int mode) throws Exception;
        void sync() throws Exception;
        void close() throws Exception;
    }
    interface Access {
        Entry stat(File path) throws Exception;
        void mkdir(File path) throws Exception;
        Handle open(File path) throws Exception;
    }
    private final Access access;
    RemoteReleaseFiles() { this(new NativeAccess()); }
    RemoteReleaseFiles(Access access) { this.access=access; }
    boolean exists(File path) throws Exception { return access.stat(path)!=null; }
    void requireNewPart(File path) throws Exception {
        if (exists(path)) throw new IOException("RELEASE_PART_ALREADY_EXISTS");
    }
    void requireRoot(File path) throws Exception {
        Entry root=access.stat(path); require(root,true);
        if ((root.mode & 07777)!=0700) throw new IOException("RELEASE_ROOT_NOT_PRIVATE");
    }
    void directory(File path) throws Exception {
        if (access.stat(path)==null) access.mkdir(path);
        tighten(path,true);
    }
    void file(File path) throws Exception { tighten(path,false); }
    private void tighten(File path, boolean directory) throws Exception {
        Entry before=access.stat(path); require(before,directory);
        try (Handle handle=access.open(path)) {
            same(before,handle.stat(),directory);
            handle.chmod(directory ? 0700 : 0600);
            Entry after=handle.stat(); same(before,after,directory);
            if ((after.mode & 07777)!=(directory ? 0700 : 0600)) throw new IOException("RELEASE_MODE_NOT_APPLIED");
            handle.sync();
            same(after,access.stat(path),directory);
        }
    }
    private static void require(Entry entry, boolean directory) throws IOException {
        if (entry==null || !entry.canonical || entry.uid!=0
                || (entry.mode & 0170000)!=(directory ? 0040000 : 0100000)
                || (!directory && entry.links!=1)) throw new IOException("RELEASE_PATH_REJECTED");
    }
    private static void same(Entry before, Entry after, boolean directory) throws IOException {
        require(after,directory);
        if (before.device!=after.device || before.inode!=after.inode) throw new IOException("RELEASE_IDENTITY_CHANGED");
    }
    private static final class NativeAccess implements Access {
        private static Entry entry(StructStat stat, boolean canonical) {
            return new Entry(stat.st_mode,stat.st_uid,stat.st_nlink,stat.st_dev,stat.st_ino,canonical);
        }
        public Entry stat(File path) throws Exception {
            try { return entry(Os.lstat(path.getPath()),path.getAbsoluteFile().equals(path.getCanonicalFile())); }
            catch (ErrnoException error) { if (error.errno==OsConstants.ENOENT) return null; throw error; }
        }
        public void mkdir(File path) throws Exception { Os.mkdir(path.getPath(),0700); }
        public Handle open(File path) throws Exception {
            final FileDescriptor fd=Os.open(path.getPath(),OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK,0);
            return new Handle() {
                public Entry stat() throws Exception { return entry(Os.fstat(fd),true); }
                public void chmod(int mode) throws Exception { Os.fchmod(fd,mode); }
                public void sync() throws Exception { Os.fsync(fd); }
                public void close() throws Exception { Os.close(fd); }
            };
        }
    }
}
