package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class AndroidEvidenceStoreTest {
    private static class Io implements AndroidEvidenceStore.DescriptorIo {
        final File path;
        final RandomAccessFile file;
        int closes, writes, syncs, maxWrite = 3;
        boolean failWrite, failSync, failClose, stall;
        Io() throws IOException { path = File.createTempFile("collection-output-fd-", ".bin"); file = new RandomAccessFile(path, "rw"); }
        public int write(FileDescriptor fd, byte[] bytes, int offset, int length) throws IOException {
            writes++; assertSame(file.getFD(), fd);
            if (failWrite) throw new IOException("WRITE_FAILED");
            if (stall) return 0;
            int count = Math.min(maxWrite, length); file.write(bytes, offset, count); return count;
        }
        public void sync(FileDescriptor fd) throws IOException {
            syncs++; assertSame(file.getFD(), fd);
            if (failSync) throw new IOException("SYNC_FAILED");
            fd.sync();
        }
        public void close(FileDescriptor fd) throws IOException {
            closes++; assertSame(file.getFD(), fd); file.close();
            if (failClose) throw new IOException("CLOSE_FAILED_AFTER_RELEASE");
        }
    }
    @Test public void partialNativeWritesCompleteAndSyncBeforeOwnedClose() throws Exception {
        Io io = new Io(); byte[] bytes = new byte[31]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        FileDescriptor fd = io.file.getFD();
        AndroidEvidenceStore.writeOwned(fd, bytes, io);
        assertArrayEquals(bytes, Files.readAllBytes(io.path.toPath())); assertFalse(fd.valid());
        assertEquals(11, io.writes); assertEquals(1, io.syncs); assertEquals(1, io.closes);
    }
    @Test public void emptyEvidenceStillSyncsAndCloses() throws Exception {
        Io io = new Io(); FileDescriptor fd = io.file.getFD();
        AndroidEvidenceStore.writeOwned(fd, new byte[0], io);
        assertEquals(0, io.writes); assertEquals(1, io.syncs); assertEquals(1, io.closes); assertFalse(fd.valid());
    }
    @Test public void writeSyncStallAndCloseFailuresPreserveFileAndReleaseDescriptor() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            Io io = new Io(); io.failWrite = mode == 0; io.failSync = mode == 1; io.stall = mode == 2; io.failClose = mode == 3;
            FileDescriptor fd = io.file.getFD();
            try { AndroidEvidenceStore.writeOwned(fd, new byte[]{1, 2, 3}, io); fail(); }
            catch (CollectionAccess.Failure expected) { assertEquals("STORE_WRITE_FAILED", expected.code); }
            assertFalse(fd.valid()); assertEquals(1, io.closes); assertTrue(io.path.isFile());
            assertEquals(mode == 1 || mode == 3 ? 3 : 0, io.path.length());
        }
    }
    @Test public void uncheckedWriterFailureStillClosesOwnedFd() throws Exception {
        Io io = new Io() {
            @Override public int write(FileDescriptor fd, byte[] b, int o, int n) { throw new IllegalStateException("synthetic"); }
        };
        FileDescriptor fd = io.file.getFD();
        try { AndroidEvidenceStore.writeOwned(fd, new byte[]{1}, io); fail(); } catch (IllegalStateException expected) { }
        assertFalse(fd.valid()); assertEquals(1, io.closes);
    }
    @Test public void readOnlyDeviceHelperRejectsPathsAndUnboundedIterations() throws Exception {
        assertEquals(64, DescriptorOwnershipCheck.iterations(new String[]{"64"}));
        assertEquals(128, DescriptorOwnershipCheck.iterations(new String[]{"128"}));
        for (String[] args : new String[][]{null, {}, {"0"}, {"129"}, {"/data/system"}, {"64", "extra"}}) {
            try { DescriptorOwnershipCheck.iterations(args); fail(); } catch (IOException expected) { }
        }
    }
}
