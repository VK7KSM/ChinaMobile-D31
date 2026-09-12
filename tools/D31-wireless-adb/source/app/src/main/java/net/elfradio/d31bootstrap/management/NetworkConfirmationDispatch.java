package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** 与普通任务领取分开的单次确认调度合同；没有生产Web任务名或默认HTTP实现。 */
public final class NetworkConfirmationDispatch {
    public static final class Request {
        public final NetworkRecoveryDispatch.Binding original;
        public final String nonce;
        public final long issuedElapsed;
        Request(NetworkRecoveryDispatch.Binding original, String nonce, long issued) {
            this.original = original; this.nonce = nonce; this.issuedElapsed = issued;
        }
    }
    public static final class Receipt {
        public final String taskId, apkSha256, bootId, nonce;
        public final boolean target;
        public final long startedElapsed, deadlineElapsed, issuedElapsed;
        public Receipt(String task, String hash, String boot, boolean target, long start, long deadline,
                       String nonce, long issued) {
            taskId = task; apkSha256 = hash; bootId = boot; this.target = target;
            startedElapsed = start; deadlineElapsed = deadline; this.nonce = nonce; issuedElapsed = issued;
        }
        boolean matches(Request request) {
            NetworkRecoveryDispatch.Binding b = request.original;
            return b.taskId.equals(taskId) && b.apkSha256.equals(apkSha256) && b.bootId.equals(bootId)
                    && b.target == target && b.startedElapsed == startedElapsed && b.deadlineElapsed == deadlineElapsed
                    && request.nonce.equals(nonce) && request.issuedElapsed == issuedElapsed;
        }
    }
    public interface Source {
        /** 必须验证身份、管理链路及精确请求绑定；普通HTTP成功不能返回Receipt。
         * 调用必须可取消且不超过maxWaitMs；无确认返回null。此接口没有生产实现。 */
        Receipt fetchVerified(Request request, long maxWaitMs) throws Exception;
    }
    public interface Completion {
        JSONObject confirm(NetworkRecoveryDispatch.Binding binding, NetworkChangeTransaction.Confirmation proof) throws Exception;
    }
    private final NetworkRecoveryDispatch recovery;
    private final NetworkChangeTransaction.Clock clock;
    private final Completion completion;
    public NetworkConfirmationDispatch(NetworkRecoveryDispatch recovery, NetworkChangeTransaction.Clock clock, Completion completion) {
        if (recovery == null || clock == null || completion == null) throw new IllegalArgumentException("确认依赖缺失");
        this.recovery = recovery; this.clock = clock; this.completion = completion;
    }
    /** 由独立于普通云任务忙槽的确认执行者调用；不持Journal锁联网，不启动/重发原任务。 */
    public JSONObject poll(String task, Source source) throws Exception {
        if (source == null) throw new IOException("NETWORK_CONFIRM_SOURCE_REQUIRED");
        checkInterrupted();
        NetworkRecoveryDispatch.Binding binding = recovery.awaiting(task);
        if (binding == null) return new JSONObject().put("confirmation", "NOT_WAITING").put("network_write", false);
        long issued = clock.elapsedMillis();
        if (!valid(binding, issued)) return rejected("EXPIRED_OR_OTHER_BOOT");
        Request request = new Request(binding, UUID.randomUUID().toString(), issued);
        Receipt receipt;
        try { receipt = source.fetchVerified(request, Math.min(3000, binding.deadlineElapsed - issued)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        catch (Exception unavailable) { return rejected("SOURCE_UNAVAILABLE"); }
        checkInterrupted();
        if (receipt == null || !receipt.matches(request)) return rejected("UNVERIFIED_OR_MISMATCHED");
        long received = clock.elapsedMillis();
        if (!valid(binding, received) || received < issued) return rejected("EXPIRED_OR_OTHER_BOOT");
        return completion.confirm(binding, (id, target, start, now) -> {
            checkInterrupted();
            return binding.taskId.equals(id) && binding.target == target && binding.startedElapsed == start
                    && now >= issued && valid(binding, now);
        });
    }
    private boolean valid(NetworkRecoveryDispatch.Binding binding, long now) throws Exception {
        return binding.bootId.equals(clock.bootId()) && now >= binding.lastElapsed && now < binding.deadlineElapsed;
    }
    private static JSONObject rejected(String reason) throws Exception {
        return new JSONObject().put("confirmation", "NOT_ACCEPTED").put("reason", reason).put("network_write", false);
    }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NETWORK_CONFIRM_CANCELLED");
    }
}
