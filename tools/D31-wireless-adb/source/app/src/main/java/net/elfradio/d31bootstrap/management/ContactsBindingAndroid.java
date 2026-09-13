package net.elfradio.d31bootstrap.management;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** 真实APP的组件与绑定适配；原厂启动只由显式LOCAL读取路径调用。 */
final class ContactsBindingAndroid implements ContactsBindingProbe.Platform {
    private final Context app;
    private final String expectedHash;
    private final ComponentName target = new ComponentName(ContactsNexuiAndroid.PACKAGE, ContactsNexuiAndroid.SERVICE);
    private ServiceConnection connection;
    private IBinder binder;
    private IBinder.DeathRecipient death;
    private boolean closed, accepted, linked;
    private int vendorUid = -1;

    ContactsBindingAndroid(Context context, String hash) throws IOException {
        app = context; expectedHash = ContactsAppContract.digest(hash);
    }

    public void prepare(ContactsBindingProbe.Control control) throws Exception {
        ContactsAppContract.application(app); control.check();
        String source = app.getApplicationInfo().sourceDir;
        if (source == null) throw new IOException("CONTACTS_INSTALLED_APK_MISMATCH");
        File file = new File(source);
        long length = file.length(), modified = file.lastModified();
        if (!file.isFile() || length < 1 || length > 64L * 1024 * 1024)
            throw new IOException("CONTACTS_INSTALLED_APK_MISMATCH");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        // 使用路径构造的流拥有FD；不重新引入API23借用FileDescriptor的关闭缺陷。
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = input.read(bytes)) != -1) {
                control.check(); total += n;
                if (total > length) throw new IOException("CONTACTS_INSTALLED_APK_CHANGED");
                digest.update(bytes, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) { hex.append(Character.forDigit((value >>> 4) & 15, 16)); hex.append(Character.forDigit(value & 15, 16)); }
        if (!expectedHash.equals(hex.toString()) || total != length || file.length() != length || file.lastModified() != modified)
            throw new IOException("CONTACTS_INSTALLED_APK_MISMATCH");
        ServiceInfo info = app.getPackageManager().getServiceInfo(target, 0);
        if (!ContactsNexuiAndroid.PACKAGE.equals(info.packageName) || !ContactsNexuiAndroid.SERVICE.equals(info.name)
                || !info.enabled || !info.exported || info.applicationInfo == null || !info.applicationInfo.enabled)
            throw new IOException("CONTACTS_COMPONENT_MISMATCH");
        vendorUid = info.applicationInfo.uid;
        control.check();
    }

    public boolean bind(final ContactsBindingProbe.Listener listener) {
        return bind(listener, 0);
    }

    boolean startVendor() {
        return target.equals(app.startService(new Intent(ContactsNexuiAndroid.ACTION).setComponent(target)));
    }

    int vendorUid() { return vendorUid; }
    synchronized IBinder connectedBinder() { return binder; }

    boolean bindForRead(ContactsBindingProbe.Listener listener) { return bind(listener, Context.BIND_AUTO_CREATE); }

    private boolean bind(final ContactsBindingProbe.Listener listener, int flags) {
        connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (ContactsBindingAndroid.this) {
                    if (closed) return;
                    if (!target.equals(name) || service == null || binder != null) {
                        listener.failed("CONTACTS_BINDER_MISMATCH"); return;
                    }
                    binder = service;
                    death = new IBinder.DeathRecipient() { public void binderDied() { listener.failed("CONTACTS_SERVICE_DIED"); } };
                    try {
                        binder.linkToDeath(death, 0); linked = true;
                        if (!binder.isBinderAlive()) { listener.failed("CONTACTS_SERVICE_DIED"); return; }
                        listener.connected();
                    } catch (Exception failed) { listener.failed("CONTACTS_SERVICE_DIED"); }
                }
            }
            public void onServiceDisconnected(ComponentName name) { listener.failed("CONTACTS_SERVICE_DIED"); }
        };
        accepted = app.bindService(new Intent(ContactsNexuiAndroid.ACTION).setComponent(target), connection, flags);
        return accepted;
    }

    public boolean verifyBinder() throws Exception {
        final IBinder current;
        synchronized (this) { current = binder; }
        return current != null && "android.os.IMessenger".equals(current.getInterfaceDescriptor()) && current.isBinderAlive();
    }

    public void unbind() throws Exception {
        IBinder current; IBinder.DeathRecipient watched; boolean unlink;
        synchronized (this) {
            if (closed) return;
            closed = true; current = binder; watched = death; unlink = linked;
        }
        Exception failure = null;
        try {
            if (connection != null) app.unbindService(connection);
        } catch (IllegalArgumentException absent) {
            if (accepted) failure = absent;
        } catch (Exception failed) { failure = failed; }
        finally {
            try {
                if (unlink && !current.unlinkToDeath(watched, 0) && current.isBinderAlive())
                    throw new IOException("CONTACTS_DEATH_UNLINK_FAILED");
            } catch (Exception failed) { if (failure == null) failure = failed; }
        }
        if (failure != null) throw new IOException("CONTACTS_UNBIND_UNCONFIRMED", failure);
    }
}
