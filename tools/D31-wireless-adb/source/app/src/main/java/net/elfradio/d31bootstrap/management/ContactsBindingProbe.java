package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;

/** 可注入的绑定生命周期；结果只能在释放尝试结束后生成。 */
final class ContactsBindingProbe {
    interface Control { void check() throws Exception; }
    interface Clock { long now(); void pause() throws Exception; }
    interface Listener { void connected(); void failed(String code); }
    interface Platform {
        void prepare(Control control) throws Exception;
        boolean bind(Listener listener) throws Exception;
        boolean verifyBinder() throws Exception;
        void unbind() throws Exception;
    }
    private static final class Observation implements Listener {
        private boolean connected;
        private String failure;
        public synchronized void connected() { connected = true; }
        public synchronized void failed(String code) { if (failure == null) failure = code; }
        synchronized boolean ready() throws IOException {
            if (failure != null) throw new IOException(failure);
            return connected;
        }
    }

    static JSONObject run(Platform platform, final Control cancellation, final Clock clock, final long deadline) throws Exception {
        final long began = clock.now();
        final Control guard = new Control() {
            public void check() throws Exception {
                cancellation.check();
                if (clock.now() >= deadline) throw new IOException("CONTACTS_TIMEOUT");
            }
        };
        Observation observation = new Observation();
        boolean prepared = false, attempted = false, accepted = false, binder = false, released = false;
        String state = "CONTACTS_BIND_CHECK_FAILED", primary = null;
        try {
            guard.check(); platform.prepare(guard); prepared = true; guard.check();
            attempted = true; accepted = platform.bind(observation);
            if (!accepted) throw new IOException("CONTACTS_BIND_REJECTED");
            long bindDeadline = Math.min(deadline, clock.now() + ContactsAppContract.BIND_MS);
            while (!observation.ready()) {
                guard.check();
                if (clock.now() >= bindDeadline) throw new IOException("CONTACTS_TIMEOUT");
                clock.pause();
            }
            if (clock.now() >= bindDeadline) throw new IOException("CONTACTS_TIMEOUT");
            guard.check(); binder = platform.verifyBinder();
            if (!binder) throw new IOException("CONTACTS_BINDER_MISMATCH");
            observation.ready(); guard.check();
            state = "BINDING_VERIFIED";
        } catch (Exception error) {
            state = error instanceof InterruptedException ? "CONTACTS_CANCELLED" : ContactsAppContract.code(error);
        } finally {
            if (attempted) {
                try { platform.unbind(); released = true; }
                catch (Exception failed) { primary = state; state = "CONTACTS_UNBIND_UNCONFIRMED"; }
            }
        }
        if ("BINDING_VERIFIED".equals(state)) {
            try { observation.ready(); guard.check(); }
            catch (Exception failed) { state = failed instanceof InterruptedException ? "CONTACTS_CANCELLED" : ContactsAppContract.code(failed); }
        }
        JSONObject value = ContactsAppContract.metadata(state).put("ok", "BINDING_VERIFIED".equals(state))
                .put("appIdentityMatched", prepared).put("installedApkHashMatched", prepared)
                .put("componentMatched", prepared).put("bindingRequested", attempted).put("bindAccepted", accepted)
                .put("messengerBinderVerified", binder).put("unbindAttempted", attempted)
                .put("unbindConfirmed", attempted ? (Object) released : JSONObject.NULL)
                .put("remoteOutcomeKnown", true).put("elapsedMs", Math.max(0, clock.now() - began));
        if (primary != null) value.put("beforeCleanupState", primary);
        return value;
    }
    private ContactsBindingProbe() { }
}
