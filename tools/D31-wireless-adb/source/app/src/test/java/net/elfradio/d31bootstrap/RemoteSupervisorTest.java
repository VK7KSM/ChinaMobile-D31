package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.channels.FileLock;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteSupervisorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyBranchYieldsAfterClosingMaintenanceLease() throws Exception {
        for (String branch : new String[]{"busy", "packages", "reserved", "manual", "normal"}) {
            Fixture cycle = new Fixture(temporary.newFolder());
            cycle.busy = branch.equals("busy");
            cycle.packages = !branch.equals("packages");
            cycle.reservation = branch.equals("reserved");
            cycle.manual = branch.equals("manual");
            RemoteSupervisor.runCycle(cycle);
            assertEquals(branch, 1, cycle.attempts);
            assertEquals(branch, 1, cycle.pauses);
            assertEquals(branch, 2000L, cycle.delay);
            assertEquals(branch, branch.equals("normal") ? 1 : 0, cycle.updates);
            assertEquals(branch, branch.equals("normal") || branch.equals("reserved") ? 1 : 0, cycle.coreChecks);
            assertEquals(branch, branch.equals("normal") || branch.equals("manual") ? 1 : 0, cycle.manualChecks);
        }
    }

    @Test public void reservationLeavesARealLockWindowForReleaseBeforeNextCycle() throws Exception {
        Fixture cycle = new Fixture(temporary.newFolder());
        cycle.reservation = true;
        cycle.releaseReservationDuringPause = true;
        RemoteSupervisor.runCycle(cycle);
        assertFalse(cycle.reservation);
        assertEquals(0, cycle.updates);
        RemoteSupervisor.runCycle(cycle);
        assertEquals(2, cycle.pauses);
        assertEquals(1, cycle.updates);
        assertEquals(2, cycle.otherWriters);
    }

    @Test public void persistentReservationCannotSkipTheOuterDelay() throws Exception {
        Fixture cycle = new Fixture(temporary.newFolder());
        cycle.reservation = true;
        for (int i = 0; i < 4; i++) RemoteSupervisor.runCycle(cycle);
        assertEquals(4, cycle.pauses);
        assertEquals(4, cycle.otherWriters);
        assertEquals(8000L, cycle.totalDelay);
        assertEquals(0, cycle.manualChecks);
        assertEquals(0, cycle.updates);
    }

    @Test public void independentProcessCanReserveAndReleaseInTheReservedCycleWindow() throws Exception {
        File root = temporary.newFolder();
        runReservationWriter(root, "reserve", temporary.newFile());
        File releaseLog = temporary.newFile();
        Fixture cycle = new Fixture(root) {
            @Override public void pause(long millis) throws InterruptedException {
                super.pause(millis);
                try { runReservationWriter(root, "release", releaseLog); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        cycle.reservation = true;
        RemoteSupervisor.runCycle(cycle);
        assertFalse(new File(root, "windows.fixture").exists());
        assertEquals(1, cycle.coreChecks);
        assertEquals(0, cycle.updates);
        assertEquals(2000L, cycle.delay);
    }

    @Test public void failureReportingAndBackoffBothHappenAfterRelease() throws Exception {
        for (String failure : new String[]{"acquire", "ensure", "update", "close"}) {
            Fixture cycle = new Fixture(temporary.newFolder());
            cycle.failure = failure;
            RemoteSupervisor.runCycle(cycle);
            assertEquals(failure, 1, cycle.errors);
            assertEquals(failure, 1, cycle.pauses);
            assertEquals(failure, 7000L, cycle.delay);
            assertEquals(failure, 1, cycle.otherWriters);
        }
    }

    @Test public void workInterruptionClosesLeaseAndStopsWithoutRetry() throws Exception {
        Fixture cycle = new Fixture(temporary.newFolder());
        cycle.interruptWork = true;
        try {
            try { RemoteSupervisor.runCycle(cycle); fail(); }
            catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
            assertFalse(cycle.held);
            assertEquals(0, cycle.pauses);
            assertEquals(0, cycle.errors);
        } finally { Thread.interrupted(); }
    }

    @Test public void pauseInterruptionCannotLeaveMaintenanceLocked() throws Exception {
        Fixture cycle = new Fixture(temporary.newFolder());
        cycle.interruptPause = true;
        try { RemoteSupervisor.runCycle(cycle); fail(); }
        catch (InterruptedException expected) { }
        assertFalse(cycle.held);
        assertEquals(1, cycle.otherWriters);
        assertEquals(0, cycle.errors);
    }

    private static void runReservationWriter(File root, String operation, File log) throws Exception {
        String executable = new File(new File(System.getProperty("java.home"), "bin"),
                File.separatorChar == '\\' ? "java.exe" : "java").getPath();
        String classes = new File(ReservationWriter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process process = new ProcessBuilder(executable, "-cp", classes, ReservationWriter.class.getName(),
                root.getPath(), operation).redirectErrorStream(true).redirectOutput(log).start();
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(RescueFiles.read(log, 4096), 0, process.exitValue());
        } finally { process.destroy(); }
    }

    /** 仅桌面合成预留；独立进程以非阻塞方式争用同一文件锁。 */
    public static final class ReservationWriter {
        public static void main(String[] args) throws Exception {
            File root = new File(args[0]), record = new File(root, "windows.fixture");
            String expected = "fixture-session\nfixture-boot\n";
            try (RandomAccessFile file = new RandomAccessFile(new File(root, "lock"), "rw");
                 FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) throw new IOException("fixture-maintenance-busy");
                if (args[1].equals("reserve")) {
                    if (!record.createNewFile()) throw new IOException("fixture-reservation-exists");
                    try (FileOutputStream output = new FileOutputStream(record)) { output.write(expected.getBytes("UTF-8")); }
                } else {
                    String actual = new String(java.nio.file.Files.readAllBytes(record.toPath()), "UTF-8");
                    if (!expected.equals(actual) || !record.delete()) throw new IOException("fixture-reservation-mismatch");
                }
            }
        }
    }

    private static class Fixture implements RemoteSupervisor.Cycle {
        final File root;
        boolean held, busy, reservation, manual, releaseReservationDuringPause, interruptWork, interruptPause;
        boolean packages = true;
        String failure = "";
        int attempts, pauses, errors, updates, coreChecks, manualChecks, otherWriters;
        long delay, totalDelay;
        Fixture(File root) { this.root = root; }
        public AutoCloseable acquire() throws Exception {
            assertFalse(held);
            attempts++;
            if (failure.equals("acquire")) throw new IOException("fixture-acquire");
            if (busy) return null;
            final RemoteMaintenance.Lease lease = RemoteMaintenance.acquire(root);
            assertNotNull(lease);
            held = true;
            return () -> {
                try { lease.close(); } finally { held = false; }
                if (failure.equals("close")) throw new IOException("fixture-close");
            };
        }
        public boolean packagesReady() { assertTrue(held); return packages; }
        public boolean reserved() { assertTrue(held); return reservation; }
        public boolean manualTick() { assertTrue(held); manualChecks++; return manual; }
        public void ensureCore() throws Exception {
            assertTrue(held); coreChecks++;
            if (interruptWork) throw new InterruptedException();
            if (failure.equals("ensure")) throw new IOException("fixture-ensure");
        }
        public void update() throws Exception {
            assertTrue(held); updates++;
            if (failure.equals("update")) throw new IOException("fixture-update");
        }
        public void failed(Exception error) { assertFalse(held); errors++; }
        public void pause(long millis) throws InterruptedException {
            assertFalse("不能持维护锁等待", held);
            pauses++; delay = millis; totalDelay += millis;
            // 使用真实临时文件锁，模拟释放方在外层等待窗口取得同一把锁。
            try (RemoteMaintenance.Lease writer = RemoteMaintenance.acquire(root)) {
                assertNotNull("维护预留释放方必须有获取锁的窗口", writer);
                otherWriters++;
                if (releaseReservationDuringPause) reservation = false;
            } catch (Exception failure) { throw new AssertionError(failure); }
            if (interruptPause) throw new InterruptedException();
        }
    }
}
