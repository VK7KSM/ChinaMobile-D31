package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 合成原厂分片驱动真实收集器和完整生命周期，结果不包含原厂记录。 */
public class ContactsLocalReadTest {
    private static final String ITEM = "{\"mName\":\"PRIVATE_SYNTHETIC_NAME\",\"mNumbers\":[\"PRIVATE_SYNTHETIC_PHONE\"],\"mLookup\":\"PRIVATE_SYNTHETIC_LOOKUP\"}";
    private static final class Clock implements ContactsBindingProbe.Clock {
        long now; Runnable pause;
        public long now() { return now; }
        public void pause() { now += 25; if (pause != null) pause.run(); }
    }
    private static class Platform implements ContactsLocalRead.Platform {
        final List<String> calls = new ArrayList<String>();
        ContactsNexui.Receiver receiver;
        ContactsBindingProbe.Listener listener;
        boolean start = true, bind = true, connect = true, valid = true, end = true, empty;
        String failAt; Runnable afterSend, cleanup;
        void call(String name) throws IOException { calls.add(name); if (name.equals(failAt)) throw new IOException("CONTACTS_SYNTHETIC_ERROR"); }
        public void prepare(ContactsBindingProbe.Control control) throws Exception { call("prepare"); }
        public boolean startVendor() throws Exception { call("start"); return start; }
        public boolean bind(ContactsBindingProbe.Listener listener) throws Exception {
            call("bind"); this.listener = listener; if (connect) listener.connected(); return bind;
        }
        public boolean verifyBinder() throws Exception { call("verify"); return valid; }
        public void requestLocal(ContactsNexui.Receiver receiver) throws Exception {
            this.receiver = receiver; call("request");
            if (end) {
                if (empty) receiver.frame("LOCAL", "all_contacts", 2, "[]");
                else {
                    receiver.frame("LOCAL", "all_contacts", 0, "[" + ITEM + "]");
                    receiver.frame("LOCAL", "all_contacts", 1, "[" + ITEM + "]");
                    receiver.frame("LOCAL", "all_contacts", 2, "[]");
                }
            }
            if (afterSend != null) afterSend.run();
        }
        public void closeReplies() throws Exception { call("closeReplies"); if (cleanup != null) cleanup.run(); }
        public void unbind() throws Exception { call("unbind"); }
    }
    private static JSONObject run(Platform p, Clock clock) throws Exception { return ContactsLocalRead.run(p, () -> {}, clock, 10000); }
    private static void failed(JSONObject value, String state) throws Exception {
        assertEquals(state, value.getString("state")); assertFalse(value.getBoolean("ok")); assertFalse(value.getBoolean("listComplete"));
    }
    private static void privateSafe(JSONObject value) throws Exception {
        String json = value.toString();
        for (String content : new String[]{"PRIVATE_SYNTHETIC", "mName", "mNumbers", "mLookup", "\"items\""}) assertFalse(json.contains(content));
        assertFalse(value.getBoolean("contactValuesEmitted")); assertFalse(value.getBoolean("vendorServiceStopRequested"));
        assertFalse(value.getBoolean("all_sources_complete")); assertTrue(json.getBytes("UTF-8").length < 8192);
    }
    @Test public void actualAccumulatorCompletesLocalAfterStartBindAndOneRequestThenCleanup() throws Exception {
        Platform p = new Platform(); JSONObject value = run(p, new Clock());
        assertEquals("LOCAL_METADATA_VERIFIED", value.getString("state")); assertTrue(value.getBoolean("ok"));
        assertEquals(Arrays.asList("prepare", "start", "bind", "verify", "request", "closeReplies", "unbind"), p.calls);
        assertTrue(value.getBoolean("contacts_requested")); assertTrue(value.getBoolean("contactRequestSent"));
        assertTrue(value.getBoolean("vendorServiceStartAccepted")); assertTrue(value.getBoolean("unbindConfirmed"));
        assertTrue(value.getBoolean("replyChannelClosed")); assertTrue(value.getBoolean("listComplete"));
        assertEquals(2, value.getJSONObject("local").getInt("record_count"));
        assertEquals(3, value.getJSONObject("local").getInt("frames_received")); privateSafe(value);
    }
    @Test public void emptyRequiresAnActualEndFrame() throws Exception {
        Platform p = new Platform(); p.empty = true; JSONObject result = run(p, new Clock());
        assertTrue(result.getBoolean("ok")); assertTrue(result.getJSONObject("local").getBoolean("end_observed"));
        assertEquals(0, result.getJSONObject("local").getInt("record_count"));
        p = new Platform(); p.end = false;
        failed(run(p, new Clock()), "CONTACTS_LOCAL_TIMEOUT");
    }
    @Test public void startRejectedNeverBindsOrSends() throws Exception {
        Platform p = new Platform(); p.start = false; JSONObject result = run(p, new Clock());
        failed(result, "CONTACTS_VENDOR_START_REJECTED"); assertFalse(result.getBoolean("contacts_requested"));
        assertEquals(Arrays.asList("prepare", "start", "closeReplies"), p.calls);
    }
    @Test public void startExceptionRemainsUnknownAndDoesNotRetry() throws Exception {
        Platform p = new Platform(); p.failAt = "start"; JSONObject result = run(p, new Clock());
        failed(result, "CONTACTS_SYNTHETIC_ERROR"); assertTrue(result.isNull("vendorServiceStartAccepted"));
        assertEquals(Arrays.asList("prepare", "start", "closeReplies"), p.calls);
    }
    @Test public void bindFalseCleansDispatcherWithoutRequestOrVendorStop() throws Exception {
        Platform p = new Platform(); p.bind = false; JSONObject result = run(p, new Clock());
        failed(result, "CONTACTS_BIND_REJECTED"); assertTrue(result.getBoolean("unbindConfirmed"));
        assertFalse(p.calls.contains("request")); privateSafe(result);
    }
    @Test public void missingBinderTimesOutInsteadOfRepeatingFlagsZeroProbe() throws Exception {
        Platform p = new Platform(); p.connect = false; Clock clock = new Clock(); JSONObject result = run(p, clock);
        failed(result, "CONTACTS_TIMEOUT"); assertEquals(5000, clock.now); assertEquals(1, result.getInt("bindFlags"));
        assertTrue(result.getBoolean("vendorServiceStartRequested")); assertFalse(p.calls.contains("request"));
    }
    @Test public void wrongBinderCannotReceiveLocalRequest() throws Exception {
        Platform p = new Platform(); p.valid = false;
        failed(run(p, new Clock()), "CONTACTS_BINDER_MISMATCH"); assertFalse(p.calls.contains("request")); assertTrue(p.calls.contains("unbind"));
    }
    @Test public void requestExceptionDoesNotRetryAndPreservesUnknownSend() throws Exception {
        Platform p = new Platform(); p.failAt = "request"; JSONObject result = run(p, new Clock());
        failed(result, "CONTACTS_SYNTHETIC_ERROR"); assertTrue(result.isNull("contactRequestSent"));
        assertEquals(1, java.util.Collections.frequency(p.calls, "request")); assertTrue(result.getBoolean("unbindConfirmed"));
    }
    @Test public void wrongSourceOrMalformedPayloadCannotPass() throws Exception {
        for (String json : new String[]{"[", "[7]", "{}"}) {
            Platform p = new Platform(); p.end = false; p.afterSend = () -> p.receiver.frame("LOCAL", "all_contacts", 2, json);
            failed(run(p, new Clock()), "CONTACTS_LOCAL_PROTOCOL_ERROR");
        }
        Platform p = new Platform(); p.end = false;
        p.afterSend = () -> p.receiver.frame("BLUETOOTH", "all_contacts", 2, "[" + ITEM + "]");
        JSONObject value = run(p, new Clock()); failed(value, "CONTACTS_LOCAL_PROTOCOL_ERROR"); privateSafe(value);
    }
    @Test public void partialWithoutEndHasCountsButNoCompleteClaim() throws Exception {
        Platform p = new Platform(); p.end = false;
        p.afterSend = () -> p.receiver.frame("LOCAL", "all_contacts", 0, "[" + ITEM + "]");
        JSONObject value = run(p, new Clock()); failed(value, "CONTACTS_LOCAL_TIMEOUT");
        assertEquals(1, value.getJSONObject("local").getInt("record_count")); privateSafe(value);
    }
    @Test public void deathAfterEndStillFailsWholeOperation() throws Exception {
        Platform p = new Platform(); p.afterSend = () -> p.listener.failed("CONTACTS_SERVICE_DIED");
        JSONObject value = run(p, new Clock()); failed(value, "CONTACTS_SERVICE_DIED");
        assertTrue(value.getJSONObject("local").getBoolean("end_observed")); assertTrue(value.getBoolean("unbindConfirmed"));
    }
    @Test public void replyCleanupFailureCannotSkipUnbindOrPass() throws Exception {
        Platform p = new Platform(); p.failAt = "closeReplies"; JSONObject result = run(p, new Clock());
        failed(result, "CONTACTS_REPLY_CLOSE_UNCONFIRMED"); assertTrue(result.getBoolean("unbindConfirmed"));
        assertFalse(result.getBoolean("replyChannelClosed"));
    }
    @Test public void unbindFailureRetainsEndEvidenceButDoesNotComplete() throws Exception {
        Platform p = new Platform(); p.failAt = "unbind"; JSONObject result = run(p, new Clock());
        failed(result, "CONTACTS_UNBIND_UNCONFIRMED"); assertTrue(result.getJSONObject("local").getBoolean("list_complete"));
        assertEquals("LOCAL_METADATA_VERIFIED", result.getString("beforeCleanupState")); assertFalse(result.getBoolean("unbindConfirmed"));
    }
    @Test public void cleanupFailureKeepsAdmissionClosedUntilDedicatedWatchdogExit() throws Exception {
        for (String failure : new String[]{"closeReplies", "unbind"}) {
            Platform p = new Platform(); p.failAt = failure;
            ContactsAppWatchdog.Lifecycle<Object> life = new ContactsAppWatchdog.Lifecycle<Object>();
            Object request = new Object(); assertTrue(life.accept(request));
            JSONObject result = run(p, new Clock());
            assertFalse(result.getBoolean("ok"));
            life.complete(request, ContactsAppWatchdog.cleanupConfirmed(result));
            assertSame(request, life.get()); assertFalse(life.accept(new Object()));
            assertEquals(ContactsAppWatchdog.Action.EXIT_SELF, ContactsAppWatchdog.decision(0,
                    ContactsAppWatchdog.HARD_MS, life.get() != request, true, ContactsAppWatchdog.PROCESS));
            assertTrue(life.claimExit(request));
        }
    }
    @Test public void cleanedBusinessFailureAllowsNextRequestWithoutKillingProcess() throws Exception {
        Platform p = new Platform(); p.valid = false;
        ContactsAppWatchdog.Lifecycle<Object> life = new ContactsAppWatchdog.Lifecycle<Object>();
        Object old = new Object(), next = new Object(); assertTrue(life.accept(old));
        JSONObject result = run(p, new Clock()); assertFalse(result.getBoolean("ok"));
        life.complete(old, ContactsAppWatchdog.cleanupConfirmed(result));
        assertTrue(life.accept(next)); assertFalse(life.claimExit(old));
    }
    @Test public void cancellationBeforePrepareDoesNotStartVendor() throws Exception {
        Platform p = new Platform(); JSONObject value = ContactsLocalRead.run(p, () -> { throw new InterruptedException(); }, new Clock(), 10000);
        failed(value, "CONTACTS_CANCELLED"); assertFalse(p.calls.contains("start")); assertFalse(value.getBoolean("contacts_requested"));
    }
    @Test public void cancellationDuringReceivePreservesPartialMetadataAndCleans() throws Exception {
        Platform p = new Platform(); p.end = false; Clock clock = new Clock();
        p.afterSend = () -> p.receiver.frame("LOCAL", "all_contacts", 0, "[" + ITEM + "]");
        JSONObject value = ContactsLocalRead.run(p, () -> { if (clock.now >= 50) throw new InterruptedException(); }, clock, 10000);
        failed(value, "CONTACTS_CANCELLED"); assertEquals(1, value.getJSONObject("local").getInt("record_count"));
        assertTrue(value.getBoolean("unbindConfirmed")); privateSafe(value);
    }
    @Test public void cancellationDuringCleanupCannotReturnSuccess() throws Exception {
        Platform p = new Platform(); final boolean[] cancel = {false}; p.cleanup = () -> cancel[0] = true;
        JSONObject result = ContactsLocalRead.run(p, () -> { if (cancel[0]) throw new InterruptedException(); }, new Clock(), 10000);
        failed(result, "CONTACTS_CANCELLED"); assertTrue(result.getBoolean("unbindConfirmed"));
    }
    @Test public void receiverBudgetLimitCannotBeFullList() throws Exception {
        Platform p = new Platform(); p.end = false;
        p.afterSend = () -> { for (int i = 0; i < 130; i++) p.receiver.frame("LOCAL", "all_contacts", i == 0 ? 0 : 1, "[]"); };
        JSONObject result = run(p, new Clock()); failed(result, "CONTACTS_LOCAL_FRAME_LIMIT");
        assertEquals(128, result.getJSONObject("local").getInt("frames_received"));
    }
    @Test public void boundedInboxClosesOnOverflowAndCannotReopen() {
        ContactsLocalRead.Inbox inbox = new ContactsLocalRead.Inbox();
        for (int i = 0; i < 128; i++) assertTrue(inbox.admit(2));
        assertFalse(inbox.admit(2)); assertTrue(inbox.closed());
        inbox.consumed(); assertFalse(inbox.admit(2));
        inbox = new ContactsLocalRead.Inbox(); assertTrue(inbox.admit(2)); inbox.consumed(); assertTrue(inbox.admit(2));
        inbox.close(); assertFalse(inbox.admit(2));
    }
    @Test public void inboxRejectsOversizedAndCumulativePayloadBeforeHandlerQueue() {
        ContactsLocalRead.Inbox inbox = new ContactsLocalRead.Inbox();
        assertFalse(inbox.admit(131073));
        inbox = new ContactsLocalRead.Inbox();
        for (int i = 0; i < 8; i++) { assertTrue(inbox.admit(131072)); inbox.consumed(); }
        assertFalse(inbox.admit(1)); assertTrue(inbox.closed());
        assertFalse(new ContactsLocalRead.Inbox().admit(-1));
    }
    @Test public void lostAppReceiptDoesNotClaimNoVendorStartOrNoRead() throws Exception {
        JSONObject result = ContactsLocalRead.unknown("CONTACTS_APP_DIED", true);
        assertTrue(result.isNull("vendorServiceStartRequested")); assertTrue(result.isNull("contacts_requested"));
        assertTrue(result.isNull("unbindConfirmed")); assertFalse(result.getBoolean("remoteOutcomeKnown")); privateSafe(result);
    }
    @Test public void cliExplicitlySelectsReadWithoutChangingOldProbe() throws Exception {
        String hash = new String(new char[64]).replace('\0', 'a');
        assertEquals(hash, ContactsAppCommand.validate(new String[]{"read-local-metadata", hash}));
        assertEquals(hash, ContactsAppCommand.validate(new String[]{"metadata", hash}));
        assertEquals(hash,ContactsAppCommand.validate(new String[]{"read-local-metadata",hash,"local-operation-1"}));
        try { ContactsAppCommand.validate(new String[]{"metadata", hash, "local-operation-1"}); fail(); } catch (IOException expected) { }
        try { ContactsAppCommand.validate(new String[]{"read-local-metadata", hash, "../other"}); fail(); } catch (IOException expected) { }
    }
}
