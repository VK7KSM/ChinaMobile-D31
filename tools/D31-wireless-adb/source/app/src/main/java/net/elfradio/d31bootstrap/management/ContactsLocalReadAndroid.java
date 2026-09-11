package net.elfradio.d31bootstrap.management;

import android.content.Context;
import android.os.*;
import java.io.IOException;

/** 原厂Messenger通道；仅发LOCAL/all_contacts，不手动刷新、不注册常驻观察器。 */
final class ContactsLocalReadAndroid implements ContactsLocalRead.Platform {
    private final ContactsBindingAndroid binding;
    private final ContactsLocalRead.Inbox inbox = new ContactsLocalRead.Inbox();
    private HandlerThread thread;
    private Handler handler;
    private boolean requested;
    ContactsLocalReadAndroid(Context context, String hash) throws IOException { binding = new ContactsBindingAndroid(context, hash); }
    public void prepare(ContactsBindingProbe.Control control) throws Exception { binding.prepare(control); }
    public boolean startVendor() { return binding.startVendor(); }
    public boolean bind(ContactsBindingProbe.Listener listener) { return binding.bindForRead(listener); }
    public boolean verifyBinder() throws Exception { return binding.verifyBinder(); }
    public void unbind() throws Exception { binding.unbind(); }

    public void requestLocal(final ContactsNexui.Receiver receiver) throws Exception {
        if (requested) throw new IOException("CONTACTS_DUPLICATE_REQUEST");
        requested = true;
        final int vendorUid = binding.vendorUid();
        if (vendorUid < 0) throw new IOException("CONTACTS_VENDOR_IDENTITY_UNKNOWN");
        thread = new HandlerThread("d31-contacts-local-reply"); thread.start();
        handler = new Handler(thread.getLooper()) {
            public boolean sendMessageAtTime(Message message, long when) {
                if (inbox.closed()) return false;
                if (message.sendingUid != vendorUid) { inbox.close(); receiver.failed("PROTOCOL_ERROR"); return false; }
                int chars = -1;
                try { Object raw = message.getData().get("all_contacts"); if (raw instanceof String) chars = ((String) raw).length(); }
                catch (Exception invalid) { }
                if (!inbox.admit(chars)) { receiver.failed("PROTOCOL_ERROR"); return false; }
                boolean queued = super.sendMessageAtTime(message, when);
                if (!queued) { inbox.consumed(); receiver.failed("PROTOCOL_ERROR"); }
                return queued;
            }
            public void handleMessage(Message message) {
                try {
                    if (inbox.closed()) return;
                    Bundle data = message.getData();
                    Object source = data.get("contact_type"), action = data.get("action_type");
                    Object state = data.get("sendState"), json = data.get("all_contacts");
                    if (!(source instanceof String) || !(action instanceof String) || !(state instanceof Integer) || !(json instanceof String))
                        throw new IOException("CONTACTS_LOCAL_PROTOCOL_ERROR");
                    receiver.frame((String) source, (String) action, (Integer) state, (String) json);
                } catch (Exception invalid) { receiver.failed("PROTOCOL_ERROR"); }
                finally { inbox.consumed(); }
            }
        };
        Bundle request = new Bundle(); request.putString("contact_type", "LOCAL"); request.putString("action_type", "all_contacts");
        Message message = Message.obtain(); message.setData(request); message.replyTo = new Messenger(handler);
        new Messenger(binding.connectedBinder()).send(message);
    }

    public void closeReplies() throws Exception {
        inbox.close();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) {
            thread.quit(); thread.join(500);
            if (thread.isAlive()) throw new IOException("CONTACTS_REPLY_CLOSE_UNCONFIRMED");
        }
    }
}
