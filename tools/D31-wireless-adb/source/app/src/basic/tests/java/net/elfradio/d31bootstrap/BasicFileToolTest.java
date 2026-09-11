package net.elfradio.d31bootstrap;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class BasicFileToolTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void retriesCannotOverwriteAndCommitRequiresWholeHash() throws Exception {
        File stage = new File(temp.getRoot(), ".d31-basic-part-test").getCanonicalFile();
        File target = new File(stage.getParentFile(), "received.bin");
        byte[] first = {0, 1, 2}, second = {3, 4};
        assertEquals(3, BasicFileTool.write(stage, 0, first, BasicFileTool.digest(first)));
        assertEquals(3, BasicFileTool.write(stage, 0, first, BasicFileTool.digest(first)));
        assertThrows(Exception.class, () -> BasicFileTool.write(stage, 0, second, BasicFileTool.digest(second)));
        assertThrows(Exception.class, () -> BasicFileTool.write(stage, 4, second, BasicFileTool.digest(second)));
        assertThrows(Exception.class, () -> BasicFileTool.write(stage, 3, second, "bad"));
        assertEquals(5, BasicFileTool.write(stage, 3, second, BasicFileTool.digest(second)));
        assertThrows(Exception.class, () -> BasicFileTool.commit(stage, target, "bad"));
        assertTrue(stage.exists());
        BasicFileTool.commit(stage, target, BasicFileTool.digest(new byte[]{0, 1, 2, 3, 4}),
                (source, destination) -> java.nio.file.Files.copy(source.toPath(), destination.toPath()));
        assertFalse(stage.exists());
        assertArrayEquals(new byte[]{2, 3, 4}, BasicFileTool.read(target, 2, 3));
    }

    @Test public void destinationCreatedDuringCommitIsNotReplaced() throws Exception {
        File stage = new File(temp.getRoot(), ".d31-basic-part-race").getCanonicalFile();
        File target = new File(stage.getParentFile(), "raced.bin");
        byte[] bytes = {8};
        BasicFileTool.write(stage, 0, bytes, BasicFileTool.digest(bytes));
        assertThrows(Exception.class, () -> BasicFileTool.commit(stage, target, BasicFileTool.digest(bytes),
                (source, destination) -> {
                    assertTrue(destination.createNewFile());
                    java.nio.file.Files.copy(source.toPath(), destination.toPath());
                }));
        assertEquals(0, target.length());
        assertTrue(stage.exists());
    }

    @Test public void publisherCanReadWhileConcurrentWriteAndCommitStayLocked() throws Exception {
        File stage = new File(temp.getRoot(), ".d31-basic-part-lock").getCanonicalFile();
        File target = new File(stage.getParentFile(), "locked-result.bin");
        byte[] bytes = {1, 9};
        String hash = BasicFileTool.digest(bytes);
        BasicFileTool.write(stage, 0, bytes, hash);
        BasicFileTool.commit(stage, target, hash, (source, destination) -> {
            assertEquals(hash, RescueFiles.sha256(source));
            assertThrows(Exception.class, () -> BasicFileTool.write(source, bytes.length, bytes, hash));
            assertThrows(Exception.class, () -> BasicFileTool.commit(source,
                    new File(source.getParentFile(), "competing.bin"), hash,
                    (ignoredSource, ignoredTarget) -> fail("锁被占用时不应进入发布")));
            assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(source.toPath()));
            java.nio.file.Files.copy(source.toPath(), destination.toPath());
        });
        assertFalse(stage.exists());
        assertEquals(hash, RescueFiles.sha256(target));
        File lock = new File(stage.getPath() + ".lock");
        assertTrue(lock.isFile());
        assertThrows(Exception.class, () -> BasicFileTool.write(lock, 0, bytes, hash));
    }

    @Test public void wrongWholeHashRejectsBeforePublisherAndReleasesLock() throws Exception {
        File stage = new File(temp.getRoot(), ".d31-basic-part-hash").getCanonicalFile();
        File target = new File(stage.getParentFile(), "hash-result.bin");
        byte[] bytes = {6};
        BasicFileTool.write(stage, 0, bytes, BasicFileTool.digest(bytes));
        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> BasicFileTool.commit(stage, target, BasicFileTool.digest(new byte[]{7}),
                        (source, destination) -> fail("哈希错误时不应进入发布")));
        assertEquals("完整哈希不匹配，未提交", failure.getMessage());
        assertFalse(target.exists());
        assertEquals(2, BasicFileTool.write(stage, 1, bytes, BasicFileTool.digest(bytes)));
    }

    @Test public void rejectsExistingDestinationAndOrdinaryFileWrites() throws Exception {
        File stage = new File(temp.getRoot(), ".d31-basic-part-test").getCanonicalFile();
        File target = temp.newFile("existing.bin").getCanonicalFile();
        byte[] bytes = {7};
        assertThrows(Exception.class, () -> BasicFileTool.write(target, 0, bytes, BasicFileTool.digest(bytes)));
        BasicFileTool.write(stage, 0, bytes, BasicFileTool.digest(bytes));
        assertThrows(Exception.class, () -> BasicFileTool.commit(stage, target, BasicFileTool.digest(bytes)));
        assertEquals(0, target.length());
        assertThrows(Exception.class, () -> BasicFileTool.read(stage, -1, 1));
        assertThrows(Exception.class, () -> BasicFileTool.read(stage, 0, 3073));
        assertThrows(Exception.class, () -> BasicFileTool.write(stage, Long.MAX_VALUE, bytes, BasicFileTool.digest(bytes)));
    }
}
