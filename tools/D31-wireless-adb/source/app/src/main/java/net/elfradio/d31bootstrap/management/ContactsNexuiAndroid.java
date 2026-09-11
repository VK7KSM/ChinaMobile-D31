package net.elfradio.d31bootstrap.management;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 复用冻结原厂ContactObserver请求；不启动服务、不注册变更监听、不请求手动刷新。 */
final class ContactsNexuiAndroid implements ContactsNexui.Transport {
    static final String PACKAGE = "com.starnet.dial";
    static final String SERVICE = "com.starnet.contactservice.remote.RemoteContactService";
    static final String ACTION = "com.starnet.contactservice.RemoteContactService";
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private final Context context;
    private HandlerThread worker;
    private Handler handler;
    private ServiceConnection connection;
    private boolean bound, closed, leased;

    ContactsNexuiAndroid(Context context) throws IOException {
        if (context == null || Looper.myLooper() == Looper.getMainLooper())
            throw new IOException("原厂读取必须在有主Looper派发的进程工作线程执行");
        if (android.os.Build.VERSION.SDK_INT != 23 || android.os.Process.myUid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
            throw new IOException("仅限已授权D31的API23独立root读取");
        this.context = context;
    }

    public synchronized void start(final String source, final ContactsNexui.Receiver receiver) throws Exception {
        if (closed || leased) throw new IOException("读取适配器不可重用");
        ContactsNexui.validateSource(source);
        if (!ACTIVE.compareAndSet(false, true)) throw new IOException("原厂通讯录读取正忙");
        leased = true;
        final ComponentName component = new ComponentName(PACKAGE, SERVICE);
        ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
        if (!info.enabled || !info.exported || !info.applicationInfo.enabled
                || !PACKAGE.equals(info.packageName) || !SERVICE.equals(info.name))
            throw new IOException("原厂服务组件不满足冻结合同");
        worker = new HandlerThread("d31-contacts-read");
        worker.start();
        handler = new Handler(worker.getLooper(), new Handler.Callback() {
            public boolean handleMessage(Message message) {
                synchronized (ContactsNexuiAndroid.this) {
                    if (closed) return true;
                    try {
                        Bundle data = message.getData();
                        Object type = data.get("contact_type"), action = data.get("action_type");
                        Object state = data.get("sendState"), json = data.get("all_contacts");
                        if (!(type instanceof String) || !(action instanceof String)
                                || !(state instanceof Integer) || !(json instanceof String)) {
                            receiver.failed("PROTOCOL_ERROR"); return true;
                        }
                        receiver.frame((String) type, (String) action, (Integer) state, (String) json);
                    } catch (RuntimeException invalid) { receiver.failed("PROTOCOL_ERROR"); }
                }
                return true;
            }
        });
        final Messenger reply = new Messenger(handler);
        connection = new ServiceConnection() {
            private boolean sent;
            public void onServiceConnected(ComponentName name, IBinder binder) {
                synchronized (ContactsNexuiAndroid.this) {
                    if (closed || sent) return;
                    sent = true;
                    if (!component.equals(name)) { receiver.failed("PROTOCOL_ERROR"); return; }
                    try {
                        Bundle data = new Bundle();
                        data.putString("contact_type", source);
                        data.putString("action_type", "all_contacts");
                        Message request = Message.obtain();
                        request.setData(data); request.replyTo = reply;
                        new Messenger(binder).send(request);
                    } catch (Exception unavailable) { receiver.failed("SERVICE_UNAVAILABLE"); }
                }
            }
            public void onServiceDisconnected(ComponentName name) { receiver.failed("SERVICE_DISCONNECTED"); }
        };
        // flags=0仅绑定已经运行的服务；不能照搬原客户端的startService和BIND_AUTO_CREATE。
        bound = context.bindService(new Intent(ACTION).setComponent(component), connection, 0);
        if (!bound) receiver.failed("SERVICE_UNAVAILABLE");
    }

    public void close() throws Exception {
        HandlerThread finishing;
        Exception failure = null;
        synchronized (this) {
            if (closed) return;
            closed = true;
            try {
                // Android可能为返回false的bind建立本地ServiceDispatcher，同样尝试解除。
                if (connection != null) context.unbindService(connection);
            } catch (IllegalArgumentException absent) {
                if (bound) failure = absent;
            } catch (Exception error) { failure = error; }
            if (handler != null) handler.removeCallbacksAndMessages(null);
            finishing = worker;
            if (finishing != null) finishing.quit();
            if (leased) { ACTIVE.set(false); leased = false; }
        }
        if (finishing != null) {
            finishing.join(1000);
            if (finishing.isAlive()) throw new IOException("通讯录读取线程未退出");
        }
        if (failure != null) throw new IOException("原厂服务解绑未确认", failure);
    }
}
