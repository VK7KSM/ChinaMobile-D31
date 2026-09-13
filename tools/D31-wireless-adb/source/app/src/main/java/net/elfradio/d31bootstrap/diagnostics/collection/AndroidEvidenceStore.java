package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.FileDescriptor;
import java.io.Closeable;
import java.io.IOException;
import android.system.ErrnoException;
import android.system.Os;

/** 在父任务预建的私有目录排他落盘；失败原件保留，不覆盖，不清理历史证据。 */
public final class AndroidEvidenceStore implements FaultEvidenceCollector.Store {
    private final String directory;
    public AndroidEvidenceStore(String absoluteDirectory) { directory = CollectionSupport.path(absoluteDirectory); }

    @Override public FaultEvidenceCollector.Receipt storeNew(String artifactId, byte[] bytes) throws IOException {
        String name = CollectionSupport.id(artifactId + ".bin");
        if (bytes == null || bytes.length > FaultEvidenceCollector.MAX_CAPTURE_BYTES) throw new CollectionAccess.Failure("STORE_SIZE_LIMIT");
        FileDescriptor fd = AndroidCollectionAccess.createArtifact(directory, name);
        writeOwned(fd, bytes, DESCRIPTORS);
        return new FaultEvidenceCollector.Receipt(name, bytes.length, CollectionSupport.hex(CollectionSupport.digest().digest(bytes)));
    }

    interface DescriptorIo {
        int write(FileDescriptor fd, byte[] bytes, int offset, int length) throws IOException;
        void sync(FileDescriptor fd) throws IOException;
        void close(FileDescriptor fd) throws IOException;
    }
    private static final DescriptorIo DESCRIPTORS = new DescriptorIo() {
        @Override public int write(FileDescriptor fd, byte[] bytes, int offset, int length) throws IOException {
            try { return Os.write(fd, bytes, offset, length); }
            catch (ErrnoException error) { throw new IOException(error); }
        }
        @Override public void sync(FileDescriptor fd) throws IOException {
            try { Os.fsync(fd); } catch (ErrnoException error) { throw new IOException(error); }
        }
        @Override public void close(FileDescriptor fd) throws IOException {
            try { Os.close(fd); } catch (ErrnoException error) { throw new IOException(error); }
        }
    };

    static void writeOwned(final FileDescriptor fd, byte[] bytes, final DescriptorIo io) throws IOException {
        // 只存在一个FD拥有者；API23借用FD的FileOutputStream.close不能代替Os.close。
        try (Closeable owner = new Closeable() { @Override public void close() throws IOException { io.close(fd); } }) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = io.write(fd, bytes, offset, Math.min(65536, bytes.length - offset));
                if (count <= 0 || count > Math.min(65536, bytes.length - offset)) throw new IOException("WRITE_STALLED");
                offset += count;
            }
            io.sync(fd);
        } catch (IOException failure) { throw new CollectionAccess.Failure("STORE_WRITE_FAILED"); }
    }
}
