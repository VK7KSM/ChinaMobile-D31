package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkChangeTransactionTest {
    static final class Crash extends Error { }
    static final class Time implements NetworkChangeTransaction.Clock {
        long now = 100; String boot = "test-boot";
        public String bootId() { return boot; }
        public long elapsedMillis() { return now; }
    }
    static final class Memory implements NetworkChangeTransaction.Store {
        JSONObject saved = new JSONObject(); int saves, failAt, crashAt; boolean busy;
        public Session lock() throws Exception {
            if (busy) throw new IOException("busy");
            busy = true;
            return new Session() {
                public JSONObject read() throws Exception {
                    return new JSONObject().put("schema_version", 1).put("records", new JSONObject(saved.toString()));
                }
                public void save(JSONObject records) throws Exception {
                    saves++;
                    if (saves == failAt) throw new IOException("save failed");
                    saved = new JSONObject(records.toString());
                    if (saves == crashAt) throw new Crash();
                }
                public void close() { busy = false; }
            };
        }
    }
    static final class Device implements NetworkChangeTransaction.Platform {
        Boolean value = true; int writes, reads, releases, owners;
        boolean ready = true, refuse, failWrite, failAfterWrite, crashWrite, crashAfterWrite, noOwner;
        Runnable onGuard;
        public AutoCloseable acquire(String task) throws Exception {
            if (noOwner) return null;
            owners++;
            return () -> releases++;
        }
        public void requireRecoveryOwner(String task, String boot, long deadline) throws Exception {
            if (onGuard != null) onGuard.run();
            if (!ready) throw new IOException("guard absent");
        }
        public Boolean readWifiEnabled() { reads++; return value; }
        public void setWifiEnabled(boolean target) throws Exception {
            writes++;
            if (crashWrite) throw new Crash();
            if (failWrite) throw new IOException("setter rejected");
            if (!refuse) value = target;
            if (crashAfterWrite) throw new Crash();
            if (failAfterWrite) throw new IOException("reply lost");
        }
    }
    static final class Fixture {
        Memory store = new Memory(); Device device = new Device(); Time time = new Time();
        NetworkChangeTransaction engine() { return new NetworkChangeTransaction(store, device, time); }
        JSONObject begin() throws Exception { return engine().begin("task-1", false, 1000); }
    }
    interface Action { void run() throws Exception; }
    static void rejected(Action action) throws Exception {
        try { action.run(); fail("应拒绝"); } catch (IOException expected) { }
    }
    static void crashed(Action action) throws Exception {
        try { action.run(); fail("应模拟中断"); } catch (Crash expected) { }
    }
    static void state(String expected, JSONObject result) throws Exception { assertEquals(expected, result.getString("state")); }

    @Test public void freshPreimageAndAuthenticatedConfirmation() throws Exception {
        Fixture f = new Fixture(); JSONObject waiting = f.begin();
        state("AWAITING_CONFIRM", waiting); assertTrue(waiting.getBoolean("before"));
        state("CONFIRMED", f.engine().confirm("task-1", (task, target, start, now) -> {
            assertEquals("task-1", task); assertFalse(target); assertEquals(100, start); return true;
        }));
        assertEquals(1, f.device.writes); assertEquals(f.device.owners, f.device.releases);
    }
    @Test public void unknownPreimageNeverWritesOrInventsDefault() throws Exception {
        Fixture f = new Fixture(); f.device.value = null; rejected(() -> f.begin());
        assertEquals(0, f.device.writes); state("ABSENT", f.engine().query("task-1"));
    }
    @Test public void missingRecoveryOwnerAbortsWithoutWrites() throws Exception {
        Fixture f = new Fixture(); f.device.ready = false;
        state("ABORTED", f.begin()); assertEquals(0, f.device.writes);
    }
    @Test public void noMaintenanceLeasePreventsCaptureAndWrite() throws Exception {
        Fixture f = new Fixture(); f.device.noOwner = true; rejected(() -> f.begin());
        assertEquals(0, f.device.reads); assertEquals(0, f.store.saves);
    }
    @Test public void noChangeDoesNotPretendRollback() throws Exception {
        Fixture f = new Fixture(); f.device.value = false;
        JSONObject result = f.begin(); state("UNCHANGED", result);
        assertFalse(result.getBoolean("restored")); assertEquals(0, f.device.writes);
    }
    @Test public void duplicateDoesNotWriteOrExtendDeadlineEvenAfterExpiry() throws Exception {
        Fixture f = new Fixture(); JSONObject first = f.begin(); f.time.now = 9000;
        JSONObject duplicate = f.begin(); assertEquals(first.toString(), duplicate.toString());
        assertEquals(1, f.device.writes);
    }
    @Test public void changedValueOrWindowWithSameTaskIsRejected() throws Exception {
        Fixture f = new Fixture(); f.begin();
        rejected(() -> f.engine().begin("task-1", true, 1000));
        rejected(() -> f.engine().begin("task-1", false, 2000)); assertEquals(1, f.device.writes);
    }
    @Test public void secondTaskBlockedUntilSettled() throws Exception {
        Fixture f = new Fixture(); f.begin(); rejected(() -> f.engine().begin("task-2", true, 1000));
        f.engine().cancel("task-1"); state("UNCHANGED", f.engine().begin("task-2", true, 1000));
    }
    @Test public void failedPreimageSavePreventsApply() throws Exception {
        Fixture f = new Fixture(); f.store.failAt = 1; rejected(() -> f.begin()); assertEquals(0, f.device.writes);
    }
    @Test public void failedIntentSavePreventsApplyAndRestartDoesNotApply() throws Exception {
        Fixture f = new Fixture(); f.store.failAt = 2; rejected(() -> f.begin());
        assertEquals(0, f.device.writes); state("UNCHANGED", f.engine().recover("task-1"));
    }
    @Test public void failedResultSaveRecoversFromDurableApplyIntent() throws Exception {
        Fixture f = new Fixture(); f.store.failAt = 3; rejected(() -> f.begin());
        state("APPLYING", f.engine().query("task-1"));
        state("ROLLED_BACK", f.engine().recover("task-1")); assertTrue(f.device.value);
    }
    @Test public void crashAfterPreimageDoesNotReapplyOrOverwriteThirdParty() throws Exception {
        Fixture f = new Fixture(); f.store.crashAt = 1; crashed(() -> f.begin()); f.device.value = false;
        state("NEEDS_ATTENTION", f.engine().recover("task-1")); assertEquals(0, f.device.writes);
    }
    @Test public void crashBetweenApplyIntentAndSetterDoesNotClaimRollback() throws Exception {
        Fixture f = new Fixture(); f.store.crashAt = 2; crashed(() -> f.begin());
        JSONObject result = f.engine().recover("task-1"); state("ORIGINAL_OBSERVED", result);
        assertFalse(result.getBoolean("restored")); assertEquals(0, f.device.writes);
    }
    @Test public void crashInsideSetterAfterChangeRecovered() throws Exception {
        Fixture f = new Fixture(); f.device.crashAfterWrite = true; crashed(() -> f.begin());
        f.device.crashAfterWrite = false;
        state("ROLLED_BACK", f.engine().recover("task-1")); assertEquals(2, f.device.writes);
    }
    @Test public void exactDeadlineRollsBackNotConfirm() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.time.now = 1100;
        state("ROLLED_BACK", f.engine().confirm("task-1", (a,b,c,d) -> { fail("过期不验确认"); return true; }));
    }
    @Test public void confirmationCallbackCannotRunPastDeadline() throws Exception {
        Fixture f = new Fixture(); f.begin();
        state("ROLLED_BACK", f.engine().confirm("task-1", (a,b,c,d) -> { f.time.now = 1100; return true; }));
    }
    @Test public void unauthenticatedAndMissingConfirmationsRemainPending() throws Exception {
        Fixture f = new Fixture(); f.begin();
        state("AWAITING_CONFIRM", f.engine().confirm("task-1", null));
        state("AWAITING_CONFIRM", f.engine().confirm("task-1", (a,b,c,d) -> false));
        state("AWAITING_CONFIRM", f.engine().confirm("task-1", (a,b,c,d) -> { throw new IOException(); }));
        assertEquals(1, f.device.writes);
    }
    @Test public void queryDoesNotReadDeviceOrRunExpiredRecovery() throws Exception {
        Fixture f = new Fixture(); f.begin(); int reads = f.device.reads; f.time.now = 2000;
        state("AWAITING_CONFIRM", f.engine().query("task-1"));
        assertEquals(reads, f.device.reads); assertEquals(1, f.device.writes);
    }
    @Test public void sameBootRestartKeepsOriginalDeadline() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.time.now = 1099;
        state("AWAITING_CONFIRM", f.engine().recover("task-1")); f.time.now = 1100;
        state("ROLLED_BACK", f.engine().recover("task-1"));
    }
    @Test public void rebootNeverRebasesRemainingConfirmationWindow() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.time.boot = "next-boot"; f.time.now = 0;
        state("ROLLED_BACK", f.engine().recover("task-1"));
    }
    @Test public void clockRollbackAndUnavailableBootFailConservatively() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.time.now = 900; f.engine().recover("task-1");
        f.time.now = 800; state("ROLLED_BACK", f.engine().recover("task-1"));
        Fixture g = new Fixture(); g.begin(); g.time.boot = null;
        state("ROLLED_BACK", g.engine().recover("task-1"));
    }
    @Test public void guardDelayCannotStartLateChange() throws Exception {
        Fixture f = new Fixture(); f.device.onGuard = () -> f.time.now = 1100;
        state("ABORTED", f.begin()); assertEquals(0, f.device.writes);
    }
    @Test public void preimageChangedDuringGuardArmBlocksApply() throws Exception {
        Fixture f = new Fixture(); f.device.onGuard = () -> f.device.value = false;
        state("NEEDS_ATTENTION", f.begin()); assertEquals(0, f.device.writes);
    }
    @Test public void unknownRollbackReadbackCannotClaimRecoveryOrWrite() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.device.value = null;
        JSONObject result = f.engine().cancel("task-1"); state("NEEDS_ATTENTION", result);
        assertFalse(result.getBoolean("restored")); assertEquals(1, f.device.writes);
        rejected(() -> f.engine().begin("task-2", true, 1000));
    }
    @Test public void rollbackRejectedAndRetryLimitPreservesAttention() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.device.failWrite = true;
        state("NEEDS_ATTENTION", f.engine().cancel("task-1"));
        state("NEEDS_ATTENTION", f.engine().recover("task-1"));
        state("NEEDS_ATTENTION", f.engine().recover("task-1")); assertEquals(3, f.device.writes);
    }
    @Test public void rollbackSetterReturnWithoutEffectiveChangeNotSuccess() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.device.refuse = true;
        JSONObject result = f.engine().cancel("task-1"); state("NEEDS_ATTENTION", result);
        assertFalse(result.getBoolean("restored")); assertTrue(result.getBoolean("rollback_returned"));
    }
    @Test public void crashAfterRollbackIntentAndExternalOriginalNotFakeRecovery() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.store.crashAt = 4;
        crashed(() -> f.engine().cancel("task-1")); f.device.value = true;
        JSONObject result = f.engine().recover("task-1"); state("ORIGINAL_OBSERVED", result);
        assertFalse(result.getBoolean("restored")); assertEquals(1, f.device.writes);
    }
    @Test public void lostRollbackReplyReportsObservedOriginalOnly() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.device.failAfterWrite = true;
        JSONObject result = f.engine().cancel("task-1"); state("ORIGINAL_OBSERVED", result);
        assertFalse(result.getBoolean("restored")); assertTrue(result.getBoolean("original_verified"));
    }
    @Test public void rollbackIntentSaveFailureDoesNotCallSetter() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.store.failAt = 4;
        rejected(() -> f.engine().cancel("task-1")); assertEquals(1, f.device.writes);
        state("ROLLED_BACK", f.engine().cancel("task-1"));
    }
    @Test public void rejectedApplyDoesNotInventRestored() throws Exception {
        Fixture f = new Fixture(); f.device.failWrite = true;
        JSONObject result = f.begin(); state("ORIGINAL_OBSERVED", result); assertFalse(result.getBoolean("restored"));
    }
    @Test public void lostApplyReplyStillAttemptsRollback() throws Exception {
        Fixture f = new Fixture(); f.device.failAfterWrite = true;
        JSONObject result = f.begin(); state("ORIGINAL_OBSERVED", result); assertEquals(2, f.device.writes);
    }
    @Test public void confirmedAndRolledBackDuplicatesDoNotRepeatSideEffects() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.engine().confirm("task-1", (a,b,c,d) -> true);
        state("CONFIRMED", f.engine().cancel("task-1")); f.begin(); assertEquals(1, f.device.writes);
        Fixture g = new Fixture(); g.begin(); g.engine().cancel("task-1"); g.begin();
        state("ROLLED_BACK", g.engine().recover("task-1")); assertEquals(2, g.device.writes);
    }
    @Test public void invalidIdAndDurationRejectedBeforeDependencies() throws Exception {
        Fixture f = new Fixture();
        rejected(() -> f.engine().begin("../task", false, 1000));
        rejected(() -> f.engine().begin("a", false, 999));
        rejected(() -> f.engine().begin("a", false, 120001)); assertEquals(0, f.device.reads);
    }
    @Test public void invalidStoredStateAndFlagsNeverReachDevice() throws Exception {
        Fixture f = new Fixture(); f.begin(); int reads = f.device.reads;
        f.store.saved.getJSONObject("task-1").put("state", "FUTURE_STATE");
        state("UNKNOWN", f.engine().query("task-1")); rejected(() -> f.engine().recover("task-1"));
        assertEquals(reads, f.device.reads);
    }
    @Test public void rollbackFinalSaveFailureDoesNotTurnIntentIntoProvenExecution() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.store.failAt = 5;
        rejected(() -> f.engine().cancel("task-1")); assertTrue(f.device.value);
        state("ROLLING_BACK", f.engine().query("task-1"));
        JSONObject result = f.engine().recover("task-1"); state("ORIGINAL_OBSERVED", result);
        assertFalse(result.getBoolean("restored")); assertEquals(2, f.device.writes);
    }
    @Test public void invalidStoredBooleanAndDeadlineAreUnknown() throws Exception {
        Fixture f = new Fixture(); f.begin();
        f.store.saved.getJSONObject("task-1").put("before", "true");
        state("UNKNOWN", f.engine().query("task-1")); rejected(() -> f.engine().recover("task-1"));
        Fixture g = new Fixture(); g.begin();
        g.store.saved.getJSONObject("task-1").put("deadline_elapsed", 999999);
        state("UNKNOWN", g.engine().query("task-1")); assertEquals(1, g.device.writes);
    }
    @Test public void busyJournalQueryIsUnknownNotAbsent() throws Exception {
        Fixture f = new Fixture(); f.store.busy = true;
        state("UNKNOWN", f.engine().query("task-1")); assertEquals(0, f.device.reads);
    }
    @Test public void acceptedConfirmationStillRequiresCurrentTargetReadback() throws Exception {
        Fixture f = new Fixture(); f.begin(); f.device.value = null;
        JSONObject result = f.engine().confirm("task-1", (a,b,c,d) -> true);
        state("NEEDS_ATTENTION", result); assertFalse(result.getBoolean("target_verified"));
        assertFalse(result.getBoolean("restored")); assertEquals(1, f.device.writes);
    }
}
