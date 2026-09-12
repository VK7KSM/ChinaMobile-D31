package net.elfradio.d31bootstrap.management;

import org.json.JSONObject;

/** 每事务独立进程的循环；先发身份心跳再尝试日志锁，不能依赖主core的tick。 */
public final class NetworkRecoveryLoop {
    public interface Host {
        long elapsed();
        String boot() throws Exception;
        void heartbeat() throws Exception;
        JSONObject query() throws Exception;
        default boolean recoveryReady(long remainingMs) throws Exception { return true; }
        JSONObject recover() throws Exception;
        void settled(JSONObject result) throws Exception;
        void pause() throws Exception;
        default void pause(long maximumMs) throws Exception { pause(); }
    }
    public static JSONObject run(Host host, String armedBoot, long deadline) throws Exception {
        long began = host.elapsed(), last = began;
        for (;;) {
            host.heartbeat(); // 此调用禁止取Journal锁，握手在父进程持锁期间完成。
            JSONObject state = host.query();
            String phase = state.optString("state");
            long now = host.elapsed();
            if (now < began || now - began >= 150000) return new JSONObject().put("state", "NEEDS_ATTENTION")
                    .put("reason", "GUARD_BUDGET_EXHAUSTED").put("restored", false);
            if (NetworkChangeTransaction.settled(phase)) {
                try { host.settled(state); return state; }
                catch (NetworkChangeTransaction.Busy busy) { host.pause(); continue; }
            }
            boolean newBoot = !armedBoot.equals(host.boot());
            boolean due = now < last || newBoot || now >= deadline;
            last = now;
            if (!"UNKNOWN".equals(phase) && !"ABSENT".equals(phase)
                    && (due || !"AWAITING_CONFIRM".equals(phase))) {
                // 新启动的Android网络服务尚不可读时，仅等待，不把暂时未知写成永久失败。
                if (newBoot) {
                    boolean ready = host.recoveryReady(150000 - (now - began));
                    host.heartbeat();
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NETWORK_RECOVERY_CANCELLED");
                    long after = host.elapsed();
                    if (after < began || after - began >= 150000) continue;
                    if (!ready) { host.pause(150000 - (after - began)); continue; }
                }
                if (host.elapsed() < began || host.elapsed() - began >= 150000) continue;
                state = host.recover();
                if (NetworkChangeTransaction.settled(state.optString("state"))) {
                    try { host.settled(state); return state; }
                    catch (NetworkChangeTransaction.Busy busy) { host.pause(); continue; }
                }
                if ("NEEDS_ATTENTION".equals(state.optString("state"))) return state;
            }
            // 损坏不是忙；立即保留现场退出。忙则有界等待父进程释放日志锁。
            if ("UNKNOWN".equals(phase) && !"STORE_BUSY".equals(state.optString("reason"))) return state;
            host.pause();
        }
    }
    private NetworkRecoveryLoop() { }
}
