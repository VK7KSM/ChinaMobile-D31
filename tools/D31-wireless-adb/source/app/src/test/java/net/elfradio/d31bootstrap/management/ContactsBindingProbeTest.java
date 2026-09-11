package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 隔离生命周期测试；不构造Android Context，不读取真实联系人。 */
public class ContactsBindingProbeTest {
    private static final class Clock implements ContactsBindingProbe.Clock {
        long time; Runnable onPause;
        public long now() { return time; }
        public void pause() { time += 25; if (onPause != null) onPause.run(); }
    }
    private static class Platform implements ContactsBindingProbe.Platform {
        ContactsBindingProbe.Listener listener;
        boolean accepted = true, connect = true, binder = true;
        int prepares, binds, verifies, unbinds;
        String prepareError, bindError, cleanupError;
        Runnable onVerify, onUnbind;
        public void prepare(ContactsBindingProbe.Control control) throws Exception {
            prepares++; if (prepareError != null) throw new IOException(prepareError);
        }
        public boolean bind(ContactsBindingProbe.Listener value) throws Exception {
            binds++; listener = value;
            if (bindError != null) throw new IOException(bindError);
            if (connect) value.connected(); return accepted;
        }
        public boolean verifyBinder() { verifies++; if (onVerify != null) onVerify.run(); return binder; }
        public void unbind() throws Exception {
            unbinds++; if (onUnbind != null) onUnbind.run();
            if (cleanupError != null) throw new IOException(cleanupError);
        }
    }
    private static final ContactsBindingProbe.Control IDLE = new ContactsBindingProbe.Control() { public void check() { } };
    private static JSONObject run(Platform p, Clock c) throws Exception { return ContactsBindingProbe.run(p, IDLE, c, 10000); }
    private static void state(JSONObject value, String state) throws Exception {
        assertEquals(state, value.getString("state"));
        assertEquals("BINDING_VERIFIED".equals(state), value.getBoolean("ok"));
        assertFalse(value.getBoolean("contacts_requested"));
        assertFalse(value.getBoolean("contactValuesRead"));
        assertFalse(value.getBoolean("listComplete"));
        assertEquals(0, value.getInt("bindFlags"));
    }

    @Test public void successRequiresBinderAndCompletedUnbind() throws Exception {
        Platform p = new Platform(); JSONObject value = run(p, new Clock());
        state(value, "BINDING_VERIFIED"); assertTrue(value.getBoolean("unbindConfirmed"));
        assertEquals(1, p.binds); assertEquals(1, p.verifies); assertEquals(1, p.unbinds);
        assertTrue(value.toString().getBytes("UTF-8").length < 8192);
    }
    @Test public void falseBindIsNotEmptyListOrSuccessAndStillReleasesDispatcher() throws Exception {
        Platform p = new Platform(); p.accepted = false; p.connect = false;
        JSONObject value = run(p, new Clock()); state(value, "CONTACTS_BIND_REJECTED");
        assertFalse(value.getBoolean("bindAccepted")); assertEquals(1, p.unbinds); assertEquals(0, p.verifies);
    }
    @Test public void trueBindWithoutBinderHasDistinctTimeout() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock();
        JSONObject value = run(p, c); state(value, "CONTACTS_TIMEOUT");
        assertTrue(value.getBoolean("bindAccepted")); assertFalse(value.getBoolean("messengerBinderVerified"));
        assertEquals(5000, c.time); assertEquals(1, p.unbinds);
    }
    @Test public void overallDeadlineWinsOverBindingBudget() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock(); c.time = 9900;
        state(run(p, c), "CONTACTS_TIMEOUT"); assertEquals(10000, c.time); assertEquals(1, p.unbinds);
    }
    @Test public void cancellationBeforePrepareNeverBinds() throws Exception {
        Platform p = new Platform();
        JSONObject value = ContactsBindingProbe.run(p, () -> { throw new InterruptedException(); }, new Clock(), 10000);
        state(value, "CONTACTS_CANCELLED"); assertEquals(0, p.prepares); assertEquals(0, p.unbinds);
        assertTrue(value.isNull("unbindConfirmed"));
    }
    @Test public void cancellationWhileWaitingUnbindsBeforeReturning() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock();
        JSONObject value = ContactsBindingProbe.run(p, () -> { if (c.time >= 50) throw new IOException("CONTACTS_CANCELLED"); }, c, 10000);
        state(value, "CONTACTS_CANCELLED"); assertEquals(50, c.time); assertEquals(1, p.unbinds);
        assertTrue(value.getBoolean("unbindConfirmed"));
    }
    @Test public void binderArrivingAtDeadlineCannotPass() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock();
        c.onPause = () -> { if (c.time == 100) p.listener.connected(); };
        state(ContactsBindingProbe.run(p, IDLE, c, 100), "CONTACTS_TIMEOUT"); assertEquals(0, p.verifies);
    }
    @Test public void binderAtBindingDeadlineCannotUseUnusedOverallBudget() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock();
        c.onPause = () -> { if (c.time == 5000) p.listener.connected(); };
        state(run(p, c), "CONTACTS_TIMEOUT"); assertEquals(0, p.verifies); assertEquals(1, p.unbinds);
    }
    @Test public void deathBeforeConnectionIsImmediateFailureWithCleanup() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock();
        c.onPause = () -> p.listener.failed("CONTACTS_SERVICE_DIED");
        state(run(p, c), "CONTACTS_SERVICE_DIED"); assertEquals(25, c.time); assertEquals(1, p.unbinds);
    }
    @Test public void deathDuringBinderVerificationCannotPass() throws Exception {
        Platform p = new Platform(); p.onVerify = () -> p.listener.failed("CONTACTS_SERVICE_DIED");
        state(run(p, new Clock()), "CONTACTS_SERVICE_DIED"); assertEquals(1, p.unbinds);
    }
    @Test public void deathDuringUnbindRemainsFailureEvenWhenUnbindReturns() throws Exception {
        Platform p = new Platform(); p.onUnbind = () -> p.listener.failed("CONTACTS_SERVICE_DIED");
        JSONObject value = run(p, new Clock()); state(value, "CONTACTS_SERVICE_DIED"); assertTrue(value.getBoolean("unbindConfirmed"));
    }
    @Test public void reconnectDoesNotClearPriorDeathOrReplayBind() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock c = new Clock();
        c.onPause = () -> { p.listener.failed("CONTACTS_SERVICE_DIED"); p.listener.connected(); };
        state(run(p, c), "CONTACTS_SERVICE_DIED"); assertEquals(1, p.binds); assertEquals(0, p.verifies);
    }
    @Test public void binderMismatchStillUnbinds() throws Exception {
        Platform p = new Platform(); p.binder = false;
        state(run(p, new Clock()), "CONTACTS_BINDER_MISMATCH"); assertEquals(1, p.unbinds);
    }
    @Test public void securityOrBindingExceptionStillAttemptsRelease() throws Exception {
        Platform p = new Platform(); p.bindError = "private details must not escape";
        JSONObject value = run(p, new Clock()); state(value, "CONTACTS_BIND_CHECK_FAILED");
        assertFalse(value.toString().contains("private details")); assertEquals(1, p.unbinds);
    }
    @Test public void failedPreparationNeverCallsBind() throws Exception {
        Platform p = new Platform(); p.prepareError = "CONTACTS_INSTALLED_APK_MISMATCH";
        state(run(p, new Clock()), "CONTACTS_INSTALLED_APK_MISMATCH"); assertEquals(0, p.binds); assertEquals(0, p.unbinds);
    }
    @Test public void unbindFailureOverridesSuccessAndRetainsPrimaryState() throws Exception {
        Platform p = new Platform(); p.cleanupError = "private unbind failure";
        JSONObject value = run(p, new Clock()); state(value, "CONTACTS_UNBIND_UNCONFIRMED");
        assertEquals("BINDING_VERIFIED", value.getString("beforeCleanupState")); assertFalse(value.getBoolean("unbindConfirmed"));
    }
    @Test public void unbindFailureOverridesTimeoutWithoutLosingTimeoutEvidence() throws Exception {
        Platform p = new Platform(); p.connect = false; p.cleanupError = "failed";
        JSONObject value = run(p, new Clock()); state(value, "CONTACTS_UNBIND_UNCONFIRMED");
        assertEquals("CONTACTS_TIMEOUT", value.getString("beforeCleanupState")); assertEquals(1, p.unbinds);
    }
    @Test public void cancellationDuringCleanupCannotProduceSuccess() throws Exception {
        Platform p = new Platform(); final boolean[] cancel = {false}; p.onUnbind = () -> cancel[0] = true;
        JSONObject value = ContactsBindingProbe.run(p, () -> { if (cancel[0]) throw new InterruptedException(); }, new Clock(), 10000);
        state(value, "CONTACTS_CANCELLED"); assertTrue(value.getBoolean("unbindConfirmed"));
    }
}
