package net.elfradio.d31bootstrap.management;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 非导出的按需APP服务；启动Intent只握手，UID0控制Binder才能开始原厂绑定。 */
public final class ContactsAppService extends Service {
    private static final ContactsAppContract.Requests REQUESTS = new ContactsAppContract.Requests();
    private static volatile JSONObject lastLocalResult;
    private static final ContactsPageStore pages = new ContactsPageStore(SystemClock::elapsedRealtime);
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ContactsAppWatchdog.Lifecycle<Endpoint> current = new ContactsAppWatchdog.Lifecycle<Endpoint>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<Runnable>(), new ThreadFactory() {
                public Thread newThread(Runnable task) { return new Thread(task, "d31-contacts-bind-check"); }
            });
    private volatile boolean destroyed;
    private volatile boolean identityVerified;
    private int lastStart;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (destroyed) return;
            pages.expire();
            Endpoint endpoint = current.get();
            if (endpoint == null) { stopSelf(lastStart); return; }
            long age = SystemClock.elapsedRealtime() - endpoint.started;
            if (age >= ContactsAppContract.WORK_MS || (!endpoint.used.get() && age >= ContactsAppContract.HANDSHAKE_MS)) endpoint.cancel();
            main.postDelayed(this, 100);
        }
    };

    public void onCreate() {
        super.onCreate();
        pages.expire();
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                while (!destroyed || current.get() != null) {
                    final Endpoint endpoint = current.get();
                    if (endpoint != null) {
                        long now = SystemClock.elapsedRealtime();
                        String process = now - endpoint.started >= ContactsAppWatchdog.HARD_MS
                                ? ContactsAppWatchdog.processName() : null;
                        ContactsAppWatchdog.Action action = ContactsAppWatchdog.decision(endpoint.started, now,
                                current.get() != endpoint, identityVerified, process);
                        ContactsAppWatchdog.apply(action, new ContactsAppWatchdog.Actions() {
                            public void flagCancellation() { endpoint.cancelled.set(true); }
                            public void exitSelf() {
                                // 身份检查与kill均在lifeLock外；认领后即使旧请求完成，也禁止新请求接入。
                                if (identityVerified && ContactsAppWatchdog.PROCESS.equals(ContactsAppWatchdog.processName())
                                        && current.claimExit(endpoint))
                                    android.os.Process.killProcess(android.os.Process.myPid());
                            }
                        });
                    }
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                }
            }
        }, "d31-contacts-deadline");
        watchdog.setDaemon(true); watchdog.start();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        try {
            ContactsAppContract.application(this);
            if (!ContactsAppWatchdog.PROCESS.equals(ContactsAppWatchdog.processName()))
                throw new IOException("CONTACTS_DEDICATED_PROCESS_REQUIRED");
            identityVerified = true;
            if (intent == null || !ContactsAppContract.ACTION.equals(intent.getAction())
                    || !new ComponentName(ContactsAppContract.PACKAGE, ContactsAppContract.SERVICE).equals(intent.getComponent()))
                throw new IOException("CONTACTS_INTENT_INVALID");
            String id = intent.getStringExtra("request_id"), boot = intent.getStringExtra("boot_id");
            long started = intent.getLongExtra("started_elapsed_ms", -1);
            ContactsAppContract.request(id, boot, started, SystemClock.elapsedRealtime(), ContactsAppContract.HANDSHAKE_MS);
            if (!boot.equals(ContactsAppContract.bootId())) throw new IOException("CONTACTS_BOOT_MISMATCH");
            ResultReceiver receiver = intent.getParcelableExtra("reply");
            if (receiver == null) throw new IOException("CONTACTS_REPLY_MISSING");
            Endpoint endpoint = new Endpoint(id, boot, started, receiver);
            endpoint.inspection = intent.getBooleanExtra("inspect_only", false);
            try { REQUESTS.claim(id, boot, started, SystemClock.elapsedRealtime()); }
            catch (Exception refused) { endpoint.finish(failure(ContactsAppContract.code(refused), false)); }
            if (!endpoint.finished.get()) {
                if (!endpoint.inspection && !current.accept(endpoint)) endpoint.finish(failure("CONTACTS_APP_BUSY", false));
                else endpoint.hello();
            }
        } catch (Exception invalid) { /* 未验证的Intent不得触发原厂绑定或回显其内容。 */ }
        main.removeCallbacks(tick); main.postDelayed(tick, 100);
        return START_NOT_STICKY;
    }

    private static JSONObject failure(String code, boolean attempted) {
        try {
            return ContactsAppContract.metadata(code).put("bindingRequested", attempted ? JSONObject.NULL : Boolean.FALSE)
                    .put("unbindConfirmed", JSONObject.NULL).put("remoteOutcomeKnown", !attempted);
        } catch (Exception impossible) { return new JSONObject(); }
    }

    private final class Endpoint extends Binder {
        final String id, boot; final long started; final ResultReceiver receiver;
        final AtomicBoolean used = new AtomicBoolean(), finished = new AtomicBoolean(), cancelled = new AtomicBoolean();
        volatile boolean localRead;
        volatile int pageAction;
        String snapshotId;
        int pageOffset, pageLimit;
        boolean snapshotPublished, localStarted;
        volatile boolean workStarted;
        boolean inspection;
        volatile String executionHash;
        volatile IBinder owner;
        final IBinder.DeathRecipient ownerDeath = new IBinder.DeathRecipient() { public void binderDied() { cancel(); } };
        Endpoint(String id, String boot, long started, ResultReceiver receiver) {
            this.id = id; this.boot = boot; this.started = started; this.receiver = receiver;
        }
        Bundle envelope(JSONObject result) throws Exception {
            Bundle data = new Bundle();
            data.putString("envelope", ContactsAppContract.envelope(id, boot, started, result,
                    pageAction == ContactsAppContract.READ_PAGE ? ContactsAppContract.PAGE_BYTES : ContactsAppContract.MAX_BYTES).toString()); return data;
        }
        void hello() throws Exception { Bundle data = envelope(null); data.putBinder("control", this); data.putInt("app_pid",android.os.Process.myPid()); receiver.send(ContactsAppContract.HELLO, data); }

        protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (Binder.getCallingUid() != 0) return false;
            try {
                data.enforceInterface(ContactsAppContract.DESCRIPTOR);
                if (code == ContactsAppContract.CANCEL) { cancel(); return true; }
                if (inspection) {
                    if (code != ContactsAppContract.INSPECT_LOCAL || !used.compareAndSet(false, true)) return false;
                    String digest = ContactsAppContract.digest(data.readString()), wanted = data.readString();
                    boolean cancelTarget = data.readInt() == 1;
                    if (data.dataAvail() != 0 || wanted == null || !wanted.matches("[a-f0-9-]{36}"))
                        throw new IOException("CONTACTS_QUERY_INVALID");
                    ContactsAppContract.request(id,boot,started,SystemClock.elapsedRealtime(),ContactsAppContract.WORK_MS);
                    JSONObject found = lastLocalResult;
                    Endpoint active = current.get();
                    if (active != null && wanted.equals(active.id) && digest.equals(active.executionHash)) {
                        if (cancelTarget) active.cancel();
                        found = lastLocalResult;
                    }
                    finish(ContactsAppContract.localReceipt(found,wanted,digest)); return true;
                }
                if (code != ContactsAppContract.EXECUTE && code != ContactsAppContract.EXECUTE_LOCAL
                        && code != ContactsAppContract.OPEN_PAGES && code != ContactsAppContract.READ_PAGE
                        && code != ContactsAppContract.CLOSE_PAGES) return false;
                if (finished.get() || !used.compareAndSet(false, true)) return true;
                pageAction = code >= ContactsAppContract.OPEN_PAGES ? code : 0;
                localRead = code == ContactsAppContract.EXECUTE_LOCAL || code == ContactsAppContract.OPEN_PAGES;
                ContactsAppContract.request(id, boot, started, SystemClock.elapsedRealtime(), ContactsAppContract.WORK_MS);
                final String digest = ContactsAppContract.digest(data.readString());
                executionHash = digest;
                owner = data.readStrongBinder();
                if (code == ContactsAppContract.READ_PAGE || code == ContactsAppContract.CLOSE_PAGES) {
                    snapshotId = data.readString(); pageOffset = data.readInt(); pageLimit = data.readInt();
                    ContactsPageCommand.pageArguments(snapshotId, pageOffset, pageLimit);
                }
                if (data.dataAvail() != 0 || owner == null || !boot.equals(ContactsAppContract.bootId()))
                    throw new IOException("CONTACTS_OWNER_INVALID");
                owner.linkToDeath(ownerDeath, 0);
                if (!owner.isBinderAlive()) throw new IOException("CONTACTS_OWNER_DIED");
                worker.execute(new Runnable() { public void run() { execute(digest); } });
            } catch (RejectedExecutionException busy) { finish(failure("CONTACTS_APP_BUSY", false)); }
            catch (Exception invalid) { finish(failure(ContactsAppContract.code(invalid), false)); }
            return true;
        }

        void execute(String digest) {
            workStarted = true;
            JSONObject result = null;
            try {
                ContactsBindingProbe.Control control = new ContactsBindingProbe.Control() { public void check() throws Exception {
                            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("CONTACTS_CANCELLED");
                        } };
                ContactsBindingProbe.Clock clock = new ContactsBindingProbe.Clock() {
                            public long now() { return SystemClock.elapsedRealtime(); }
                            public void pause() throws Exception { Thread.sleep(25); }
                        };
                if (pageAction == ContactsAppContract.OPEN_PAGES) {
                    pages.requireEmpty();
                    try (ContactsLocalRead.Capture capture = new ContactsLocalRead.Capture()) {
                        localStarted = true;
                        result = ContactsLocalRead.run(new ContactsLocalReadAndroid(getApplicationContext(), digest), control, clock,
                                started + ContactsAppContract.WORK_MS, capture);
                        if (result.optBoolean("ok")) {
                            JSONObject descriptor = pages.publish(capture.snapshot, digest, boot, result);
                            capture.take();
                            snapshotPublished = true;
                            result.put("page_snapshot", descriptor);
                            // 即使没有后续请求，也在到期时清除内存中的个人记录。
                            main.postDelayed(pages::expire, ContactsPageStore.TTL_MS);
                        }
                    }
                } else if (pageAction == ContactsAppContract.READ_PAGE || pageAction == ContactsAppContract.CLOSE_PAGES) {
                    SystemManagement.Control pageControl = new SystemManagement.Control() {
                        public void check() throws Exception { control.check(); if (clock.now() >= started + ContactsAppContract.WORK_MS) throw new IOException("CONTACTS_TIMEOUT"); }
                        public void before(JSONObject value) throws Exception { throw new IOException("CONTACTS_WRITES_FORBIDDEN"); }
                    };
                    pageControl.check();
                    JSONObject page = pageAction == ContactsAppContract.READ_PAGE
                            ? pages.page(digest, boot, snapshotId, pageOffset, pageLimit, pageControl) : null;
                    if (pageAction == ContactsAppContract.CLOSE_PAGES) pages.release(digest, boot, snapshotId);
                    result = ContactsPageCommand.receipt("CONTACTS_PAGE_VERIFIED").put("ok", true)
                            .put("snapshot_id", snapshotId).put("snapshot_closed", pageAction == ContactsAppContract.CLOSE_PAGES);
                    if (page != null) result.put("page", page);
                } else result = localRead ? ContactsLocalRead.run(new ContactsLocalReadAndroid(getApplicationContext(), digest), control, clock, started + ContactsAppContract.WORK_MS)
                        : ContactsBindingProbe.run(new ContactsBindingAndroid(getApplicationContext(), digest), control, clock, started + ContactsAppContract.WORK_MS);
            } catch (Exception failed) {
                try { result = localRead && result != null ? result.put("ok", false).put("listComplete", false).put("state", ContactsAppContract.code(failed))
                        : localRead ? ContactsLocalRead.unknown(ContactsAppContract.code(failed), true) : failure(ContactsAppContract.code(failed), true); }
                catch (Exception invalid) { result = failure("CONTACTS_LOCAL_READ_FAILED", true); }
                if (pageAction == ContactsAppContract.OPEN_PAGES && !localStarted) try {
                    result = ContactsLocalRead.unknown(ContactsAppContract.code(failed), false)
                            .put("bindingRequested", false).put("replyChannelClosed", true);
                } catch (Exception ignored) { }
            }
            finish(result);
        }

        void finish(JSONObject result) {
            if (!finished.compareAndSet(false, true)) return;
            if (owner != null) try { owner.unlinkToDeath(ownerDeath, 0); } catch (Exception ignored) { }
            try {
                if (localRead && !"NEXUI_APP_LOCAL_METADATA".equals(result.optString("kind")))
                    result = ContactsLocalRead.unknown(result.optString("state", "CONTACTS_LOCAL_READ_FAILED"), !result.optBoolean("remoteOutcomeKnown", false));
                if (result.optBoolean("ok") && (cancelled.get() || SystemClock.elapsedRealtime() - started >= ContactsAppContract.WORK_MS))
                    result.put("ok", false).put("listComplete", false).put("state", cancelled.get() ? "CONTACTS_CANCELLED" : "CONTACTS_TIMEOUT");
                if (!result.optBoolean("ok") && pageAction != 0) {
                    result.remove("page"); result.remove("page_snapshot");
                    if (snapshotPublished) pages.close();
                    if (pageAction == ContactsAppContract.READ_PAGE && (cancelled.get()
                            || SystemClock.elapsedRealtime() - started >= ContactsAppContract.WORK_MS))
                        try { pages.release(executionHash, boot, snapshotId); } catch (Exception ignored) { }
                }
                result.put("app_pid", android.os.Process.myPid()).put("app_uid", android.os.Process.myUid());
                if (localRead && workStarted) {
                    if (pageAction == ContactsAppContract.OPEN_PAGES) result.put("operation", "read_local_pages");
                    result.put("operation_request_id",id).put("operation_apk_sha256",executionHash);
                    lastLocalResult = new JSONObject(result.toString());
                }
                receiver.send(pageAction == ContactsAppContract.READ_PAGE ? ContactsAppContract.PAGE_RESULT : ContactsAppContract.RESULT, envelope(result));
            } catch (Exception ignored) { }
            // 清理未知保留当前占位，既有专用进程watchdog继续追踪至硬截止。
            finally { current.complete(this, !workStarted || pageAction == ContactsAppContract.READ_PAGE
                    || pageAction == ContactsAppContract.CLOSE_PAGES || ContactsAppWatchdog.cleanupConfirmed(result)); }
        }

        synchronized void cancel() {
            cancelled.set(true);
            // 已执行的任务必须等待worker走完finally解绑，不先发送伪清理完成回执。
            if (!used.get()) finish(failure("CONTACTS_CANCELLED", false));
        }
    }

    public IBinder onBind(Intent intent) { return null; }
    public void onDestroy() {
        destroyed = true; main.removeCallbacks(tick);
        Endpoint endpoint = current.get(); if (endpoint != null) endpoint.cancel();
        worker.shutdown(); super.onDestroy();
    }
}
