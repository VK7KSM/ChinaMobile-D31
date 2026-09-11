package net.elfradio.d31bootstrap.management;

import org.junit.Test;
import static org.junit.Assert.*;

/** 只mock结束自身，不调用Android kill，不故意阻塞真实Binder。 */
public class ContactsAppWatchdogTest {
    private static final class Effects implements ContactsAppWatchdog.Actions {
        int cancellations, exits;
        public void flagCancellation() { cancellations++; }
        public void exitSelf() { exits++; }
    }
    private static ContactsAppWatchdog.Action action(long now, boolean done, boolean identity, String name) {
        return ContactsAppWatchdog.decision(0, now, done, identity, name);
    }
    @Test public void workDeadlineCancelsBeforeHardExit() {
        Effects effects = new Effects();
        ContactsAppWatchdog.apply(action(9999, false, true, ContactsAppWatchdog.PROCESS), effects);
        assertEquals(0, effects.cancellations);
        ContactsAppWatchdog.apply(action(10000, false, true, ContactsAppWatchdog.PROCESS), effects);
        assertEquals(1, effects.cancellations); assertEquals(0, effects.exits);
        ContactsAppWatchdog.apply(action(10999, false, true, ContactsAppWatchdog.PROCESS), effects);
        assertEquals(0, effects.exits);
    }
    @Test public void anyStuckStageReachesSelfExitWithoutStageOrEndpointLock() {
        for (String stage : new String[]{"bind", "descriptor", "unbind", "onTransact", "result_delivery"}) {
            Effects effects = new Effects();
            ContactsAppWatchdog.apply(action(11000, false, true, ContactsAppWatchdog.PROCESS), effects);
            assertEquals(stage, 1, effects.exits); assertEquals(stage, 1, effects.cancellations);
        }
    }
    @Test public void cancellationWithoutCompletionStillReachesHardDeadline() {
        Effects effects = new Effects();
        ContactsAppWatchdog.apply(action(10000, false, true, ContactsAppWatchdog.PROCESS), effects);
        ContactsAppWatchdog.apply(action(11000, false, true, ContactsAppWatchdog.PROCESS), effects);
        assertEquals(2, effects.cancellations); assertEquals(1, effects.exits);
    }
    @Test public void completedDuringGraceNeverExits() {
        Effects effects = new Effects();
        ContactsAppWatchdog.apply(action(10000, false, true, ContactsAppWatchdog.PROCESS), effects);
        ContactsAppWatchdog.apply(action(11000, true, true, ContactsAppWatchdog.PROCESS), effects);
        assertEquals(1, effects.cancellations); assertEquals(0, effects.exits);
    }
    @Test public void completedNormallyNeverCancelsOrExits() {
        for (long time : new long[]{10, 10000, 11000, 12000}) {
            assertEquals(ContactsAppWatchdog.Action.NONE, action(time, true, true, ContactsAppWatchdog.PROCESS));
        }
    }
    @Test public void mainAppOtherProcessAndMissingNameNeverExit() {
        for (String name : new String[]{null, "", ContactsAppContract.PACKAGE, "net.elfradio.d31bootstrap:media", "net.elfradio.d31bootstrap:contacts2"}) {
            Effects effects = new Effects();
            ContactsAppWatchdog.apply(action(11000, false, true, name), effects);
            assertEquals(1, effects.cancellations); assertEquals(0, effects.exits);
        }
    }
    @Test public void unverifiedIdentityCannotExitEvenWithExactName() {
        Effects effects = new Effects();
        ContactsAppWatchdog.apply(action(11000, false, false, ContactsAppWatchdog.PROCESS), effects);
        assertEquals(0, effects.exits); assertEquals(1, effects.cancellations);
    }
    @Test public void hardDeadlinePrecedesRootReplyDeadline() {
        assertTrue(ContactsAppWatchdog.HARD_MS > ContactsAppContract.WORK_MS);
        assertTrue(ContactsAppWatchdog.HARD_MS + 50 < ContactsAppContract.REPLY_MS);
        assertEquals(ContactsAppWatchdog.Action.NONE, ContactsAppWatchdog.decision(10, 9, false, true, ContactsAppWatchdog.PROCESS));
    }
    @Test public void blockedWorkerHoldingMonitorDoesNotPreventDeadlineEffects() throws Exception {
        Object endpointLock = new Object();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch exited = new java.util.concurrent.CountDownLatch(1);
        Thread stuckWorker = new Thread(() -> {
            synchronized (endpointLock) {
                held.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        });
        Thread watchdog = new Thread(() -> ContactsAppWatchdog.apply(
                action(11000, false, true, ContactsAppWatchdog.PROCESS), new ContactsAppWatchdog.Actions() {
                    public void flagCancellation() { }
                    public void exitSelf() { exited.countDown(); }
                }));
        stuckWorker.start();
        try {
            assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS));
            watchdog.start();
            assertTrue(exited.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(stuckWorker.isAlive());
        } finally { release.countDown(); stuckWorker.join(2000); watchdog.join(2000); }
        assertFalse(stuckWorker.isAlive()); assertFalse(watchdog.isAlive());
    }
    @Test public void oldCompletionThenNewAdmissionRejectsStaleHardExitClaim() {
        ContactsAppWatchdog.Lifecycle<Object> life = new ContactsAppWatchdog.Lifecycle<Object>();
        Object old = new Object(), next = new Object();
        assertTrue(life.accept(old));
        Object staleWatchdogSnapshot = life.get();
        life.complete(old);
        assertTrue(life.accept(next));
        assertFalse(life.claimExit(staleWatchdogSnapshot));
        life.complete(old);
        assertSame(next, life.get());
    }
    @Test public void hardExitClaimThenOldCompletionNeverAdmitsNewRequest() {
        ContactsAppWatchdog.Lifecycle<Object> life = new ContactsAppWatchdog.Lifecycle<Object>();
        Object old = new Object(), next = new Object();
        assertTrue(life.accept(old));
        assertTrue(life.claimExit(old));
        assertFalse(life.accept(next));
        life.complete(old);
        assertNull(life.get());
        assertFalse(life.accept(next));
        assertFalse(life.claimExit(old));
    }
}
