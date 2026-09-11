package net.elfradio.d31bootstrap.management;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** root工作线程上的单请求桥。调用者须持有公共维护租约；close只请求取消。 */
public final class ContactsAppBridge implements AutoCloseable {
    private final Context context;
    private final IBinder owner = new Binder();
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Call current;
    public ContactsAppBridge(Context rootSystemContext) { context = rootSystemContext; }

    public JSONObject checkBinding(String verifiedApkSha256, SystemManagement.Control control) throws Exception {
        ContactsAppContract.digest(verifiedApkSha256);
        if (control == null) throw new IOException("CONTACTS_CONTROL_REQUIRED");
        if (!active.compareAndSet(false, true)) throw new IOException("CONTACTS_BRIDGE_BUSY");
        Call call = new Call(); current = call;
        try {
            if (closed) throw new IOException("CONTACTS_BRIDGE_CLOSED");
            return call.run(verifiedApkSha256, control);
        } finally { call.release(); current = null; active.set(false); }
    }

    private final class Call {
        final String id = UUID.randomUUID().toString();
        String boot;
        long started;
        IBinder endpoint;
        JSONObject result;
        boolean cancelled, ended, executed, linked, appStarted, handshake;
        String failure;
        final IBinder.DeathRecipient death = new IBinder.DeathRecipient() {
            public void binderDied() { synchronized (Call.this) { failure = "CONTACTS_APP_DIED"; Call.this.notifyAll(); } }
        };

        JSONObject run(final String digest, SystemManagement.Control control) throws Exception {
            try {
                ContactsAppContract.device();
                if (context == null || android.os.Process.myUid() != 0) throw new IOException("CONTACTS_ROOT_REQUIRED");
                control.check(); boot = ContactsAppContract.bootId(); started = SystemClock.elapsedRealtime();
                final ComponentName target = new ComponentName(ContactsAppContract.PACKAGE, ContactsAppContract.SERVICE);
                ServiceInfo info = context.getPackageManager().getServiceInfo(target, 0);
                final int expectedUid = info.applicationInfo.uid;
                if (!target.getPackageName().equals(info.packageName) || !target.getClassName().equals(info.name)
                        || info.exported || !info.enabled || !info.applicationInfo.enabled || expectedUid < 10000 || expectedUid >= 20000)
                    throw new IOException("CONTACTS_APP_COMPONENT_MISMATCH");
                ResultReceiver receiver = new ResultReceiver(null) {
                    protected void onReceiveResult(int code, Bundle data) {
                        int sender = Binder.getCallingUid();
                        if (sender != expectedUid) return;
                        synchronized (Call.this) {
                            if (ended) return;
                            try {
                                if (data == null || !boot.equals(ContactsAppContract.bootId())) throw new IOException("CONTACTS_REPLY_MISMATCH");
                                JSONObject envelope = ContactsAppContract.reply(sender, expectedUid, id, boot, started,
                                        SystemClock.elapsedRealtime(), data.getString("envelope"));
                                if (code == ContactsAppContract.HELLO && endpoint == null && result == null) {
                                    endpoint = data.getBinder("control");
                                    if (endpoint == null) throw new IOException("CONTACTS_CONTROL_MISSING");
                                    endpoint.linkToDeath(death, 0); linked = true; handshake = true;
                                } else if (code == ContactsAppContract.RESULT && result == null) {
                                    result = envelope.getJSONObject("result");
                                }
                            } catch (Exception invalid) { failure = "CONTACTS_REPLY_INVALID"; }
                            Call.this.notifyAll();
                        }
                    }
                };
                Intent intent = new Intent(ContactsAppContract.ACTION).setComponent(target).putExtra("request_id", id)
                        .putExtra("boot_id", boot).putExtra("started_elapsed_ms", started).putExtra("reply", receiver);
                Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
                synchronized (this) { if (cancelled || closed) throw new IOException("CONTACTS_CANCELLED"); }
                appStarted = true;
                if (!target.equals(ContactsAppContract.start(manager, intent))) throw new IOException("CONTACTS_APP_START_FAILED");
                await(true, control, started + ContactsAppContract.HANDSHAKE_MS);
                synchronized (this) { if (result != null) return decorate(result, digest); }
                control.check();
                synchronized (this) {
                    if (cancelled || closed) throw new IOException("CONTACTS_CANCELLED");
                    Parcel parcel = Parcel.obtain();
                    try {
                        parcel.writeInterfaceToken(ContactsAppContract.DESCRIPTOR); parcel.writeString(digest); parcel.writeStrongBinder(owner);
                        executed = true;
                        if (!endpoint.transact(ContactsAppContract.EXECUTE, parcel, null, IBinder.FLAG_ONEWAY))
                            throw new IOException("CONTACTS_EXECUTE_FAILED");
                    } finally { parcel.recycle(); }
                }
                await(false, control, started + ContactsAppContract.REPLY_MS);
                synchronized (this) { return decorate(result, digest); }
            } catch (Exception failed) {
                String reason = failed instanceof InterruptedException ? "CONTACTS_CANCELLED" : ContactsAppContract.code(failed);
                cancel();
                // 取消后短暂等待APP的解绑回执，不将发送CANCEL当作已经清理。
                boolean interrupted = Thread.interrupted();
                long until = Math.min(started + ContactsAppContract.REPLY_MS, SystemClock.elapsedRealtime() + 2000);
                synchronized (this) {
                    while (executed && result == null && failure == null && SystemClock.elapsedRealtime() < until) {
                        try { wait(25); } catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (interrupted || failed instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (result != null) {
                        JSONObject known = decorate(result, digest);
                        return known.put("ok", false).put("bridgeFailure", reason);
                    }
                    return decorate(ContactsAppContract.metadata(reason).put("bindingRequested", executed ? JSONObject.NULL : Boolean.FALSE)
                            .put("unbindConfirmed", JSONObject.NULL).put("remoteOutcomeKnown", !executed), digest);
                }
            }
        }

        private JSONObject decorate(JSONObject value, String digest) throws Exception {
            if (value == null) throw new IOException("CONTACTS_REPLY_MISSING");
            return value.put("appServiceStartRequested", appStarted).put("bridgeHandshake", handshake)
                    .put("expectedApkSha256", digest).put("contacts_requested", false);
        }

        private void await(boolean hello, SystemManagement.Control control, long deadline) throws Exception {
            for (;;) {
                control.check();
                synchronized (this) {
                    if (closed || cancelled) throw new IOException("CONTACTS_CANCELLED");
                    if (failure != null) throw new IOException(failure);
                    if (result != null || (hello && endpoint != null)) return;
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("CONTACTS_BRIDGE_TIMEOUT");
                    wait(25);
                }
            }
        }

        synchronized void cancel() {
            cancelled = true; notifyAll();
            if (endpoint == null || ended) return;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInterfaceToken(ContactsAppContract.DESCRIPTOR);
                endpoint.transact(ContactsAppContract.CANCEL, parcel, null, IBinder.FLAG_ONEWAY);
            } catch (Exception ignored) { } finally { parcel.recycle(); }
        }
        synchronized void release() {
            if (result == null) cancel();
            ended = true;
            if (linked) try { endpoint.unlinkToDeath(death, 0); } catch (Exception ignored) { }
        }
    }

    public void close() { closed = true; Call call = current; if (call != null) call.cancel(); }
}
