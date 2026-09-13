package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实临时文件加可注入描述符操作；宿主不依赖FileInputStream拥有FD的不同语义。 */
public class AndroidCollectionAccessTest {
    private static class Io implements AndroidCollectionAccess.DescriptorIo {
        final RandomAccessFile file;
        int closes, reads, stats;
        boolean readFailure, statFailure, closeFailure;
        Io(File path) throws IOException { file = new RandomAccessFile(path, "r"); }
        public CollectionAccess.Stat stat(FileDescriptor fd) throws IOException {
            stats++;
            if (statFailure) throw new IOException("STAT_FAILED");
            assertSame(file.getFD(), fd);
            return new CollectionAccess.Stat("file", 1, 2, file.length(), 3, 4, 0600, 0, 0);
        }
        public int read(FileDescriptor fd, byte[] bytes, int offset, int length) throws IOException {
            reads++;
            if (readFailure) throw new IOException("READ_FAILED");
            assertSame(file.getFD(), fd);
            int n = file.read(bytes, offset, length);
            return n < 0 ? 0 : n;
        }
        public void close(FileDescriptor fd) throws IOException {
            closes++;
            assertSame(file.getFD(), fd);
            file.close();
            if (closeFailure) throw new IOException("CLOSE_FAILED_AFTER_RELEASE");
        }
        AndroidCollectionAccess.OwnedHandle handle() throws IOException {
            return new AndroidCollectionAccess.OwnedHandle(file.getFD(), this);
        }
    }
    private static File fixture() throws IOException {
        File file = File.createTempFile("collection-owned-fd-", ".bin");
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        return file;
    }

    @Test public void repeatedRealFileReadsReleaseEveryDescriptorWithoutGc() throws Exception {
        File path = fixture();
        for (int i = 0; i < 1200; i++) {
            Io io = new Io(path); FileDescriptor fd = io.file.getFD();
            try (CollectionAccess.Handle handle = io.handle()) {
                byte[] bytes = new byte[8];
                assertEquals(4, handle.stat().size);
                assertEquals(4, handle.read(bytes, 0, bytes.length));
                assertEquals(-1, handle.read(bytes, 0, bytes.length));
                assertArrayEquals(new byte[]{1, 2, 3, 4, 0, 0, 0, 0}, bytes);
            }
            assertFalse("FD仍有效，轮次=" + i, fd.valid());
            assertEquals(1, io.closes);
        }
    }
    @Test public void readStatAndConsumerFailuresAllReleaseTheOwnedFd() throws Exception {
        File path = fixture();
        for (int mode = 0; mode < 3; mode++) {
            Io io = new Io(path); FileDescriptor fd = io.file.getFD();
            io.readFailure = mode == 0; io.statFailure = mode == 1;
            try (CollectionAccess.Handle handle = io.handle()) {
                if (mode == 0) handle.read(new byte[1], 0, 1);
                else if (mode == 1) handle.stat();
                else throw new IOException("CANCELLED_OR_DEADLINE");
                fail();
            } catch (IOException expected) { }
            assertFalse(fd.valid()); assertEquals(1, io.closes);
        }
    }
    @Test public void eofAndZeroLengthMatchTheExistingStreamContract() throws Exception {
        Io io = new Io(fixture());
        try (CollectionAccess.Handle handle = io.handle()) {
            assertEquals(0, handle.read(new byte[0], 0, 0)); assertEquals(0, io.reads);
            assertEquals(4, handle.read(new byte[4], 0, 4));
            assertEquals(-1, handle.read(new byte[4], 0, 4));
            assertEquals(-1, handle.read(new byte[4], 0, 4));
            assertEquals(0, handle.read(new byte[4], 4, 0));
            for (int[] range : new int[][]{{-1, 1}, {0, -1}, {4, 1}, {1, Integer.MAX_VALUE}}) {
                try { handle.read(new byte[4], range[0], range[1]); fail(); } catch (IndexOutOfBoundsException expected) { }
            }
        }
    }
    @Test public void closeFailureIsReportedButNeverRetriedAndClosedAccessIsRefused() throws Exception {
        Io io = new Io(fixture()); io.closeFailure = true;
        FileDescriptor fd = io.file.getFD(); CollectionAccess.Handle handle = io.handle();
        try { handle.close(); fail(); } catch (IOException expected) { }
        assertFalse(fd.valid()); handle.close(); assertEquals(1, io.closes);
        try { handle.stat(); fail(); } catch (CollectionAccess.Failure expected) { assertEquals("HANDLE_CLOSED", expected.code); }
        try { handle.read(new byte[1], 0, 1); fail(); } catch (CollectionAccess.Failure expected) { assertEquals("HANDLE_CLOSED", expected.code); }
        assertEquals(0, io.reads); assertEquals(0, io.stats);
    }
    @Test public void staleHandleCannotReadOrCloseReusedDescriptorSlot() throws Exception {
        final FileDescriptor slot = new FileDescriptor();
        final int[] generation = {1}, closes = {0};
        AndroidCollectionAccess.DescriptorIo io = new AndroidCollectionAccess.DescriptorIo() {
            public CollectionAccess.Stat stat(FileDescriptor fd) { throw new AssertionError("旧Handle不得触碰复用FD"); }
            public int read(FileDescriptor fd, byte[] b, int o, int n) { throw new AssertionError("旧Handle不得触碰复用FD"); }
            public void close(FileDescriptor fd) { assertEquals(1, generation[0]); closes[0]++; }
        };
        CollectionAccess.Handle old = new AndroidCollectionAccess.OwnedHandle(slot, io);
        old.close(); generation[0] = 2; old.close();
        try { old.read(new byte[1], 0, 1); fail(); } catch (CollectionAccess.Failure expected) { }
        try { old.stat(); fail(); } catch (CollectionAccess.Failure expected) { }
        assertEquals(1, closes[0]);
    }
    @Test public void closeCannotRaceAnInFlightReadOrCloseTwice() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1), finishRead = new CountDownLatch(1), closing = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        Io io = new Io(fixture()) {
            @Override public int read(FileDescriptor fd, byte[] b, int o, int n) throws IOException {
                reading.countDown();
                try { if (!finishRead.await(2, TimeUnit.SECONDS)) throw new IOException("TEST_TIMEOUT"); }
                catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                return super.read(fd, b, o, n);
            }
        };
        FileDescriptor fd = io.file.getFD(); final CollectionAccess.Handle handle = io.handle();
        Thread reader = new Thread(() -> { try { assertEquals(1, handle.read(new byte[1], 0, 1)); } catch (Throwable e) { error.set(e); } });
        Thread closer = new Thread(() -> { closing.countDown(); try { handle.close(); handle.close(); } catch (Throwable e) { error.set(e); } });
        reader.start();
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS)); closer.start(); assertTrue(closing.await(2, TimeUnit.SECONDS));
            assertTrue(fd.valid()); assertEquals(0, io.closes);
        } finally { finishRead.countDown(); reader.join(3000); closer.join(3000); handle.close(); }
        assertFalse(reader.isAlive()); assertFalse(closer.isAlive()); assertNull(error.get());
        assertFalse(fd.valid()); assertEquals(1, io.closes);
    }
}
