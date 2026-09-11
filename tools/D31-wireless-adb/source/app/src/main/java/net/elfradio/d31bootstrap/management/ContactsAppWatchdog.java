package net.elfradio.d31bootstrap.management;

import org.json.JSONObject;

/** 无Android依赖的截止决策；执行接口只允许取消标志与本进程退出。 */
final class ContactsAppWatchdog {
    static final String PROCESS = "net.elfradio.d31bootstrap:contacts";
    static final long HARD_MS = ContactsAppContract.WORK_MS + 1000;
    enum Action { NONE, CANCEL, EXIT_SELF, REFUSE_EXIT }
    interface Actions { void flagCancellation(); void exitSelf(); }

    /** 锁内仅转移状态；退出认领不可撤销，完成旧请求也不能重新开放接纳。 */
    static final class Lifecycle<T> {
        private final Object lifeLock = new Object();
        private T current;
        private boolean exiting;
        T get() { synchronized (lifeLock) { return current; } }
        boolean accept(T endpoint) {
            synchronized (lifeLock) {
                if (endpoint == null || exiting || current != null) return false;
                current = endpoint; return true;
            }
        }
        void complete(T endpoint) {
            synchronized (lifeLock) { if (current == endpoint) current = null; }
        }
        void complete(T endpoint, boolean cleanupConfirmed) {
            if (cleanupConfirmed) complete(endpoint);
        }
        boolean claimExit(T endpoint) {
            synchronized (lifeLock) {
                if (endpoint == null || exiting || current != endpoint) return false;
                exiting = true; return true;
            }
        }
    }

    /** 业务失败可以已清理，业务返回也可以残留；仅实际释放证据允许下一请求。 */
    static boolean cleanupConfirmed(JSONObject result) {
        if (result == null || !Boolean.TRUE.equals(result.opt("remoteOutcomeKnown"))) return false;
        Object requested = result.opt("bindingRequested");
        if (!Boolean.FALSE.equals(requested)
                && !(Boolean.TRUE.equals(requested) && Boolean.TRUE.equals(result.opt("unbindConfirmed")))) return false;
        return !"NEXUI_APP_LOCAL_METADATA".equals(result.optString("kind"))
                || Boolean.TRUE.equals(result.opt("replyChannelClosed"));
    }

    static Action decision(long started, long now, boolean completed, boolean identityVerified, String actualProcessName) {
        if (completed || started < 0 || now < started || now - started < ContactsAppContract.WORK_MS) return Action.NONE;
        if (now - started < HARD_MS) return Action.CANCEL;
        return identityVerified && PROCESS.equals(actualProcessName) ? Action.EXIT_SELF : Action.REFUSE_EXIT;
    }

    static void apply(Action action, Actions target) {
        if (action == Action.NONE) return;
        target.flagCancellation();
        if (action == Action.EXIT_SELF) target.exitSelf();
    }

    static String processName() {
        try {
            Object value = Class.forName("android.app.ActivityThread").getMethod("currentProcessName").invoke(null);
            return value instanceof String ? (String) value : null;
        } catch (Exception unavailable) { return null; }
    }
    private ContactsAppWatchdog() { }
}
