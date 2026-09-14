package net.elfradio.d31bootstrap.repair;

import org.json.JSONArray;
import org.json.JSONObject;

/** 由受信任宿主接线的只读消费者；不接受远程类名、命令或任意路径。 */
public final class RepairConsumer {
    public interface Files {
        byte[] read(String logicalPath, int maxBytes) throws Exception;
    }
    public interface Verifier {
        /** 固定版本化合同编号；续作必须保持一致。 */
        String id();
        /** 必须消费全部变更的实际文件；只能解析，不得启动业务或修改外部状态。 */
        boolean verify(RepairPlan plan, Files files) throws Exception;
    }
    public static final class Result {
        public final boolean passed;
        private final String status;
        private final String observations;
        private Result(boolean passed, String status, JSONArray observations) {
            this.passed = passed; this.status = status; this.observations = observations.toString();
        }
        public static Result notChecked() { return new Result(false, "NOT_CHECKED", new JSONArray()); }
        static Result observed(boolean passed, JSONArray observations) {
            return new Result(passed, passed ? "PASSED" : "FAILED", observations);
        }
        JSONObject json(String id) throws Exception {
            return new JSONObject().put("consumer_id", id).put("status", status)
                    .put("scope", "READ_ONLY_FILE_CONSUMPTION")
                    .put("running_service_effect", "NOT_CHECKED")
                    .put("observed_files", new JSONArray(observations));
        }
    }
    private RepairConsumer() { }
}
