package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkRecoveryDispatchTest {
    static final String HASH = String.join("", java.util.Collections.nCopies(64, "a"));
    static class Owner implements NetworkAndroidPlatform.Maintenance {
        boolean released, busy, wrong; int checks;
        public AutoCloseable acquire(String task, boolean recovery) throws Exception {
            if (busy) throw new NetworkChangeTransaction.Busy();
            requireReady(task); return () -> { };
        }
        public void requireReady(String task) throws Exception {
            checks++;
            if (wrong || released || !"task-1".equals(task)) throw new IOException("NETWORK_RESERVATION_MISMATCH");
        }
        public void release(String task) throws Exception {
            if (busy) throw new NetworkChangeTransaction.Busy();
            requireReady(task); released = true;
        }
    }
    static class Setup {
        final NetworkChangeTransactionTest.Fixture f = new NetworkChangeTransactionTest.Fixture();
        final Owner owner = new Owner();
        final NetworkRecoveryDispatch dispatch = new NetworkRecoveryDispatch(f.store, owner, HASH);
        NetworkConfirmationDispatch confirmation() {
            return new NetworkConfirmationDispatch(dispatch, f.time, (binding, proof) -> {
                if (owner.busy) throw new NetworkChangeTransaction.Busy();
                owner.requireReady(binding.taskId);
                return f.engine().confirmBound(binding.taskId, binding, proof);
            });
        }
        void begin() throws Exception { f.begin(); }
    }
    static NetworkConfirmationDispatch.Receipt receipt(NetworkConfirmationDispatch.Request r) {
        NetworkRecoveryDispatch.Binding b = r.original;
        return new NetworkConfirmationDispatch.Receipt(b.taskId, b.apkSha256, b.bootId, b.target,
                b.startedElapsed, b.deadlineElapsed, r.nonce, r.issuedElapsed);
    }
    @Test public void coreRestartKeepsOriginalDeadlineAndNeverReplaysApply() throws Exception {
        Setup s = new Setup(); s.begin(); s.f.time.now = 700;
        JSONObject result = s.dispatch.resume("task-1", binding -> {
            assertEquals(100, binding.startedElapsed); assertEquals(1100, binding.deadlineElapsed);
            assertEquals("test-boot", binding.bootId); assertTrue(binding.before); assertFalse(binding.target);
            assertTrue("调用launch期间仍持Journal锁", s.f.store.busy);
        });
        assertEquals("GUARD_READY", result.getString("recovery_dispatch"));
        assertEquals("AWAITING_CONFIRM", s.f.engine().recover("task-1").getString("state"));
        assertEquals(1, s.f.device.writes); assertFalse(s.owner.released);
    }
    @Test public void systemRestartPreservesBindingAndRestoresActualPreimageImmediately() throws Exception {
        Setup s = new Setup(); s.begin(); s.f.time.boot = "new-boot"; s.f.time.now = 5;
        s.dispatch.resume("task-1", b -> { assertEquals("test-boot", b.bootId); assertEquals(1100, b.deadlineElapsed); });
        assertEquals("ROLLED_BACK", s.f.engine().recover("task-1").getString("state"));
        assertTrue(s.f.device.value); assertEquals(2, s.f.device.writes);
        s.dispatch.resume("task-1", b -> fail("终态不得再次拉守护"));
        assertTrue(s.owner.released);
    }
    @Test public void preparedCrashOnlyObservesOriginalAndDoesNotReapply() throws Exception {
        Setup s = new Setup(); s.f.store.crashAt = 1;
        NetworkChangeTransactionTest.crashed(s::begin);
        s.dispatch.resume("task-1", b -> assertTrue(b.before));
        assertEquals("UNCHANGED", s.f.engine().recover("task-1").getString("state"));
        assertEquals(0, s.f.device.writes);
    }
    @Test public void preparedUnownedChangeRetainsReservation() throws Exception {
        Setup s = new Setup(); s.f.store.crashAt = 1; NetworkChangeTransactionTest.crashed(s::begin);
        s.f.device.value = false;
        s.dispatch.resume("task-1", b -> { });
        assertEquals("NEEDS_ATTENTION", s.f.engine().recover("task-1").getString("state"));
        assertFalse(s.owner.released); assertEquals(0, s.f.device.writes);
    }
    @Test public void trueAbsentReleasesOnlyMatchingReservation() throws Exception {
        Setup s = new Setup();
        assertEquals("UNSTARTED_RELEASED", s.dispatch.resume("task-1", b -> fail()).getString("recovery_dispatch"));
        assertTrue(s.owner.released); assertEquals(0, s.f.device.writes);
    }
    @Test public void wrongOwnerAndCorruptJournalNeverLaunchOrRelease() throws Exception {
        Setup s = new Setup(); s.begin(); s.owner.wrong = true;
        NetworkChangeTransactionTest.rejected(() -> s.dispatch.resume("task-1", b -> fail()));
        s.owner.wrong = false; s.f.store.saved.getJSONObject("task-1").put("before", "true");
        NetworkChangeTransactionTest.rejected(() -> s.dispatch.resume("task-1", b -> fail()));
        assertFalse(s.owner.released); assertEquals(1, s.f.device.writes);
    }
    @Test public void releaseBusyAndHandshakeFailureKeepPreimageAndReservation() throws Exception {
        Setup s = new Setup(); s.begin(); String original = s.f.store.saved.toString();
        NetworkChangeTransactionTest.rejected(() -> s.dispatch.resume("task-1", b -> { throw new IOException("failed"); }));
        assertEquals(original, s.f.store.saved.toString()); assertFalse(s.owner.released);
        s.f.engine().cancel("task-1"); s.owner.busy = true;
        try { s.dispatch.resume("task-1", b -> fail()); fail(); } catch (NetworkChangeTransaction.Busy expected) { }
        assertFalse(s.owner.released);
    }
    @Test public void storeBusyHasNoSideEffects() throws Exception {
        Setup s = new Setup(); s.begin(); s.f.store.busy = true;
        NetworkChangeTransactionTest.rejected(() -> s.dispatch.resume("task-1", b -> fail()));
        assertEquals(0, s.owner.checks); assertEquals(1, s.f.device.writes); assertFalse(s.owner.released);
    }
    @Test public void standaloneConfirmationDoesNotNeedNormalTaskQueueOrHoldJournalDuringFetch() throws Exception {
        Setup s = new Setup(); s.begin();
        JSONObject result = s.confirmation().poll("task-1", (r, budget) -> {
            assertFalse(s.f.store.busy); assertEquals(1000, budget);
            assertEquals("AWAITING_CONFIRM", s.f.engine().query("task-1").getString("state"));
            return receipt(r);
        });
        assertEquals("CONFIRMED", result.getString("state")); assertEquals(1, s.f.device.writes);
    }
    @Test public void confirmationCannotBlockIndependentRollbackAndLateReplyCannotResurrectTask() throws Exception {
        Setup s = new Setup(); s.begin();
        JSONObject result = s.confirmation().poll("task-1", (r, budget) -> {
            s.f.time.now = 1100;
            assertEquals("ROLLED_BACK", s.f.engine().recover("task-1").getString("state"));
            return receipt(r);
        });
        assertEquals("NOT_ACCEPTED", result.getString("confirmation"));
        assertTrue(s.f.device.value); assertEquals(2, s.f.device.writes);
    }
    @Test public void wrongBootAndExpiredNeverFetchOrConfirm() throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            Setup s = new Setup(); s.begin();
            if (variant == 0) s.f.time.boot = "other-boot"; else s.f.time.now = 1100;
            assertEquals("NOT_ACCEPTED", s.confirmation().poll("task-1", (r,b) -> { fail(); return null; }).getString("confirmation"));
            assertFalse(s.owner.released); assertEquals(1, s.f.device.writes);
        }
    }
    @Test public void everyConfirmationIdentityFieldMustMatch() throws Exception {
        for (int variant = 0; variant < 8; variant++) {
            final int v = variant;
            Setup s = new Setup(); s.begin();
            JSONObject result = s.confirmation().poll("task-1", (r,budget) -> {
                NetworkRecoveryDispatch.Binding b = r.original;
                return new NetworkConfirmationDispatch.Receipt(v == 0 ? "other" : b.taskId, v == 1 ? "bad" : b.apkSha256,
                        v == 2 ? "new-boot" : b.bootId, v == 3 ? !b.target : b.target,
                        b.startedElapsed + (v == 4 ? 1 : 0), b.deadlineElapsed + (v == 5 ? 1 : 0),
                        v == 6 ? "old-nonce" : r.nonce, r.issuedElapsed + (v == 7 ? 1 : 0));
            });
            assertEquals("NOT_ACCEPTED", result.getString("confirmation")); assertEquals(1, s.f.device.writes);
            assertEquals("AWAITING_CONFIRM", s.f.engine().query("task-1").getString("state"));
        }
    }
    @Test public void clockRollbackOrBootChangeDuringFetchInvalidatesReceipt() throws Exception {
        for (int variant = 0; variant < 2; variant++) {
            final int v = variant; Setup s = new Setup(); s.begin(); s.f.time.now = 300;
            assertEquals("NOT_ACCEPTED", s.confirmation().poll("task-1", (r,b) -> {
                if (v == 0) s.f.time.now = 200; else s.f.time.boot = "other";
                return receipt(r);
            }).getString("confirmation"));
        }
    }
    @Test public void persistedLastTimePreventsClockRollbackFetch() throws Exception {
        Setup s = new Setup(); s.begin(); s.f.time.now = 500; s.f.engine().recover("task-1"); s.f.time.now = 400;
        assertEquals("NOT_ACCEPTED", s.confirmation().poll("task-1", (r,b) -> { fail(); return null; }).getString("confirmation"));
    }
    @Test public void sourceFailureAndNullDoNotChangeOriginalTransaction() throws Exception {
        Setup s = new Setup(); s.begin(); String original = s.f.store.saved.toString();
        s.confirmation().poll("task-1", (r,b) -> { throw new IOException("offline"); });
        s.confirmation().poll("task-1", (r,b) -> null);
        assertEquals(original, s.f.store.saved.toString()); assertEquals(1, s.f.device.writes);
    }
    @Test public void sourceInterruptionCannotBecomeConfirmation() throws Exception {
        Setup s = new Setup(); s.begin();
        try {
            s.confirmation().poll("task-1", (r,b) -> { throw new InterruptedException(); }); fail();
        } catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertEquals("AWAITING_CONFIRM", s.f.engine().query("task-1").getString("state"));
    }
    @Test public void journalRebindingDuringFetchRejectedInsideConfirmationLock() throws Exception {
        Setup s = new Setup(); s.begin();
        NetworkChangeTransactionTest.rejected(() -> s.confirmation().poll("task-1", (r,b) -> {
            s.f.store.saved.getJSONObject("task-1").put("boot_id", "replacement"); return receipt(r);
        }));
        assertEquals(1, s.f.device.writes);
    }
    @Test public void confirmationBusyPreservesOriginalAndCanRetryWithNewChallenge() throws Exception {
        Setup s = new Setup(); s.begin();
        try {
            s.confirmation().poll("task-1", (r,b) -> { s.owner.busy = true; return receipt(r); }); fail();
        } catch (NetworkChangeTransaction.Busy expected) { }
        assertEquals("AWAITING_CONFIRM", s.f.engine().query("task-1").getString("state"));
        s.owner.busy = false;
        assertEquals("CONFIRMED", s.confirmation().poll("task-1", (r,b) -> receipt(r)).getString("state"));
    }
    @Test public void oldReceiptCannotConfirmNewPollEvenAtSameElapsed() throws Exception {
        Setup s = new Setup(); s.begin();
        final NetworkConfirmationDispatch.Receipt[] old = new NetworkConfirmationDispatch.Receipt[1];
        s.confirmation().poll("task-1", (r,b) -> { old[0] = receipt(r); return null; });
        assertEquals("NOT_ACCEPTED", s.confirmation().poll("task-1", (r,b) -> old[0]).getString("confirmation"));
    }
    @Test public void staleGuardParametersCannotDriveRecovery() throws Exception {
        Setup s = new Setup(); s.begin(); JSONObject job = s.f.store.saved.getJSONObject("task-1");
        NetworkRecoveryGuard.requireOriginal(job, "task-1", "test-boot", 1100);
        NetworkChangeTransactionTest.rejected(() -> NetworkRecoveryGuard.requireOriginal(job, "task-2", "test-boot", 1100));
        NetworkChangeTransactionTest.rejected(() -> NetworkRecoveryGuard.requireOriginal(job, "task-1", "new-boot", 1100));
        NetworkChangeTransactionTest.rejected(() -> NetworkRecoveryGuard.requireOriginal(job, "task-1", "test-boot", 2100));
    }
    @Test public void repeatedStartupMustNotLoopUnknownBinderRecoveryButNewBootMayRetry() throws Exception {
        JSONObject result = new JSONObject().put("guard_task", "task-1").put("guard_apk_sha256", HASH)
                .put("guard_armed_boot", "old-boot").put("guard_boot", "current-boot").put("guard_deadline", 1100)
                .put("state", "NEEDS_ATTENTION");
        assertTrue(NetworkRecoveryGuard.priorAttention(result, "task-1", HASH, "old-boot", "current-boot", 1100));
        assertFalse(NetworkRecoveryGuard.priorAttention(result, "task-1", HASH, "old-boot", "next-boot", 1100));
        assertFalse(NetworkRecoveryGuard.priorAttention(result, "task-2", HASH, "old-boot", "current-boot", 1100));
        assertFalse(NetworkRecoveryGuard.priorAttention(result, "task-1", HASH, "old-boot", "current-boot", 2100));
        result.put("state", "UNKNOWN");
        assertTrue(NetworkRecoveryGuard.priorAttention(result, "task-1", HASH, "old-boot", "current-boot", 1100));
        result.put("state", "ROLLED_BACK");
        assertFalse(NetworkRecoveryGuard.priorAttention(result, "task-1", HASH, "old-boot", "current-boot", 1100));
    }
    @Test public void cancellationAtConfirmationCommitPreservesInterruptAndNeverCommits() throws Exception {
        Setup s = new Setup(); s.begin();
        NetworkRecoveryDispatch.Binding binding = s.dispatch.awaiting("task-1");
        try {
            s.f.engine().confirmBound("task-1", binding, (a,b,c,d) -> { throw new InterruptedException(); }); fail();
        } catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertEquals("AWAITING_CONFIRM", s.f.engine().query("task-1").getString("state"));
        assertFalse(s.owner.released);
    }
}
