package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;

/** 在父任务预建的私有目录排他落盘；失败原件保留，不覆盖，不清理历史证据。 */
public final class AndroidEvidenceStore implements FaultEvidenceCollector.Store {
    private final String directory;
    public AndroidEvidenceStore(String absoluteDirectory) { directory = CollectionSupport.path(absoluteDirectory); }

    @Override public FaultEvidenceCollector.Receipt storeNew(String artifactId, byte[] bytes) throws IOException {
        String name = CollectionSupport.id(artifactId + ".bin");
        if (bytes == null || bytes.length > FaultEvidenceCollector.MAX_CAPTURE_BYTES) throw new CollectionAccess.Failure("STORE_SIZE_LIMIT");
        FileDescriptor fd = AndroidCollectionAccess.createArtifact(directory, name);
        try (FileOutputStream output = new FileOutputStream(fd)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } catch (IOException failure) { throw new CollectionAccess.Failure("STORE_WRITE_FAILED"); }
        return new FaultEvidenceCollector.Receipt(name, bytes.length, CollectionSupport.hex(CollectionSupport.digest().digest(bytes)));
    }
}
