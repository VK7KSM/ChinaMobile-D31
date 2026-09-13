package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;

/** 显式LOCAL读取闭环；只返回冻结收集器的元数据，原厂记录在结束时丢弃。 */
final class ContactsLocalRead {
    static final class Capture implements AutoCloseable {
        ContactsNexui.Snapshot snapshot;
        ContactsNexui.Snapshot take() { ContactsNexui.Snapshot value = snapshot; snapshot = null; return value; }
        public void close() { if (snapshot != null) snapshot.close(); snapshot = null; }
    }
    interface Platform extends ContactsBindingProbe.Platform {
        boolean startVendor() throws Exception;
        void requestLocal(ContactsNexui.Receiver receiver) throws Exception;
        void closeReplies() throws Exception;
    }

    static JSONObject metadata(String state) throws Exception {
        return ContactsAppContract.metadata(state).put("kind", "NEXUI_APP_LOCAL_METADATA")
                .put("operation", "read_local_metadata").put("contact_type", "LOCAL").put("bindFlags", 1)
                .put("vendorServiceStopRequested", false).put("all_sources_complete", false)
                .put("contactDataReadOnly", true).put("vendorLifecycleRestored", false).put("vendorStartupEffects", "NOT_VERIFIED");
    }

    static JSONObject unknown(String state, boolean executionAttempted) throws Exception {
        Object unknown = executionAttempted ? JSONObject.NULL : Boolean.FALSE;
        return metadata(state).put("vendorServiceStartRequested", unknown).put("vendorServiceStartAccepted", unknown)
                .put("bindingRequested", unknown).put("contacts_requested", unknown).put("contactRequestSent", unknown)
                .put("contactValuesRead", unknown).put("unbindConfirmed", JSONObject.NULL)
                .put("replyChannelClosed", JSONObject.NULL).put("remoteOutcomeKnown", !executionAttempted);
    }

    private static final class Observation implements ContactsBindingProbe.Listener {
        boolean connected; String failed;
        public synchronized void connected() { connected = true; }
        public synchronized void failed(String code) { if (failed == null) failed = code; }
        synchronized boolean ready() throws IOException { if (failed != null) throw new IOException(failed); return connected; }
    }

    static JSONObject run(Platform platform, final ContactsBindingProbe.Control cancel,
            final ContactsBindingProbe.Clock clock, final long deadline) throws Exception {
        return run(platform, cancel, clock, deadline, null);
    }
    static JSONObject run(Platform platform, final ContactsBindingProbe.Control cancel,
            final ContactsBindingProbe.Clock clock, final long deadline, Capture capture) throws Exception {
        final long began = clock.now();
        ContactsBindingProbe.Control guard = new ContactsBindingProbe.Control() {
            public void check() throws Exception { cancel.check(); if (clock.now() >= deadline) throw new IOException("CONTACTS_TIMEOUT"); }
        };
        Observation observation = new Observation();
        boolean prepared = false, startAttempt = false, startReturned = false, started = false, bindAttempt = false, bound = false;
        boolean binder = false, requested = false, sent = false, unbound = false, repliesClosed = false;
        String state = "CONTACTS_LOCAL_READ_FAILED", cleanupState = null;
        ContactsNexui.Accumulator accumulator = null;
        JSONObject local = null;
        long readBegan = 0, waitMs = 0;
        try {
            guard.check(); platform.prepare(guard); prepared = true; guard.check();
            startAttempt = true; started = platform.startVendor(); startReturned = true;
            if (!started) throw new IOException("CONTACTS_VENDOR_START_REJECTED");
            guard.check(); bindAttempt = true; bound = platform.bind(observation);
            if (!bound) throw new IOException("CONTACTS_BIND_REJECTED");
            long bindDeadline = Math.min(deadline, clock.now() + ContactsAppContract.BIND_MS);
            while (!observation.ready()) {
                guard.check(); if (clock.now() >= bindDeadline) throw new IOException("CONTACTS_TIMEOUT");
                clock.pause();
            }
            guard.check(); if (clock.now() >= bindDeadline) throw new IOException("CONTACTS_TIMEOUT");
            binder = platform.verifyBinder();
            if (!binder) throw new IOException("CONTACTS_BINDER_MISMATCH");
            guard.check(); observation.ready();
            waitMs = Math.min(ContactsNexui.WAIT_MS, deadline - clock.now());
            if (waitMs < 1) throw new IOException("CONTACTS_TIMEOUT");
            readBegan = clock.now();
            accumulator = new ContactsNexui.Accumulator("LOCAL", System.nanoTime(), waitMs);
            guard.check();
            requested = true; platform.requestLocal(accumulator); sent = true;
            for (;;) {
                guard.check(); observation.ready();
                synchronized (accumulator) {
                    if (accumulator.done) break;
                    if (clock.now() - readBegan >= waitMs) { accumulator.stop("TIMEOUT"); break; }
                }
                clock.pause();
            }
            guard.check(); observation.ready();
            synchronized (accumulator) {
                try (ContactsNexui.Snapshot snapshot = accumulator.snapshot(clock.now() - readBegan)) {
                    local = snapshot.metadata().put("effective_wait_budget_ms", waitMs);
                    if (capture != null && local.getBoolean("list_complete")) {
                        capture.snapshot = accumulator.snapshot(clock.now() - readBegan);
                        local = capture.snapshot.metadata().put("effective_wait_budget_ms", waitMs);
                    }
                }
            }
            state = local.getBoolean("list_complete") ? "LOCAL_METADATA_VERIFIED" : "CONTACTS_LOCAL_" + local.getString("status");
        } catch (Exception failed) {
            state = failed instanceof InterruptedException ? "CONTACTS_CANCELLED" : ContactsAppContract.code(failed);
        } finally {
            // 接收通道和绑定分别清理；其中一个失败不跳过另一个。
            try { platform.closeReplies(); repliesClosed = true; }
            catch (Exception failed) { cleanupState = state; state = "CONTACTS_REPLY_CLOSE_UNCONFIRMED"; }
            if (bindAttempt) {
                try { platform.unbind(); unbound = true; }
                catch (Exception failed) { if (cleanupState == null) cleanupState = state; state = "CONTACTS_UNBIND_UNCONFIRMED"; }
            }
            if (accumulator != null) {
                try {
                    if (local == null) synchronized (accumulator) {
                        if (!accumulator.done) accumulator.stop(state);
                        try (ContactsNexui.Snapshot snapshot = accumulator.snapshot(clock.now() - readBegan)) {
                            local = snapshot.metadata().put("effective_wait_budget_ms", waitMs);
                        }
                    }
                } finally { accumulator.discard(); }
            }
        }
        if ("LOCAL_METADATA_VERIFIED".equals(state)) {
            try { guard.check(); observation.ready(); }
            catch (Exception failed) { state = failed instanceof InterruptedException ? "CONTACTS_CANCELLED" : ContactsAppContract.code(failed); }
        }
        JSONObject result = metadata(state).put("ok", "LOCAL_METADATA_VERIFIED".equals(state))
                .put("appIdentityMatched", prepared).put("installedApkHashMatched", prepared).put("componentMatched", prepared)
                .put("vendorServiceStartRequested", startAttempt)
                .put("vendorServiceStartAccepted", startAttempt && !startReturned ? JSONObject.NULL : Boolean.valueOf(started))
                .put("bindingRequested", bindAttempt).put("bindAccepted", bound).put("messengerBinderVerified", binder)
                .put("contacts_requested", requested).put("contactRequestSent", requested && !sent ? JSONObject.NULL : Boolean.valueOf(sent))
                .put("contactValuesRead", requested ? (local != null && local.getInt("frames_received") > 0 ? Boolean.TRUE : JSONObject.NULL) : Boolean.FALSE)
                .put("unbindAttempted", bindAttempt).put("unbindConfirmed", bindAttempt ? Boolean.valueOf(unbound) : JSONObject.NULL)
                .put("replyChannelClosed", repliesClosed).put("listComplete", "LOCAL_METADATA_VERIFIED".equals(state))
                .put("remoteOutcomeKnown", true).put("elapsedMs", Math.max(0, clock.now() - began));
        if (local != null) result.put("local", local);
        if (cleanupState != null) result.put("beforeCleanupState", cleanupState);
        return result;
    }

    /** Handler入队前应用完整分片总预算，处理过的帧也不返还预算。 */
    static final class Inbox {
        private int pending, frames, chars;
        private boolean closed;
        synchronized boolean admit(int frameChars) {
            if (closed) return false;
            if (frameChars < 0 || frameChars > ContactsNexui.MAX_FRAME_CHARS || frames >= ContactsNexui.MAX_FRAMES
                    || chars + frameChars > ContactsNexui.MAX_TOTAL_CHARS) { closed = true; return false; }
            pending++; frames++; chars += frameChars; return true;
        }
        synchronized void consumed() { if (pending > 0) pending--; }
        synchronized boolean closed() { return closed; }
        synchronized void close() { closed = true; }
    }
    private ContactsLocalRead() { }
}
