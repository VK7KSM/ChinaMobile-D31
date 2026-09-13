package net.elfradio.d31bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** 只校验既有请求解析器能消费生成件，不调用生产执行入口。 */
public final class RepairReviewRequestMain {
    public static void main(String[] args) throws Exception {
        String digest = null;
        String[] operations = {"submit", "run", "query"};
        for (int i = 0; i < operations.length; i++) {
            RemoteRepairRequest request = new RemoteRepairRequest(new JSONObject(new String(
                    Files.readAllBytes(Paths.get(args[0], operations[i] + "-request.json")), StandardCharsets.UTF_8)));
            if (!request.operation.equals(operations[i])) throw new AssertionError("操作不匹配");
            if (i == 0) {
                digest = request.digest;
                byte[] target = Files.readAllBytes(Paths.get(args[0], "target-payload.sh"));
                if (!request.plan.changes.get(0).artifact.equals("start-script")
                        || request.plan.changes.get(0).targetBytes != target.length)
                    throw new AssertionError("载荷落点不匹配");
            } else if (!digest.equals(request.digest)) throw new AssertionError("请求摘要不一致");
        }
        System.out.println("三份生成请求已通过原RemoteRepairRequest解析；未执行修复。");
    }
}
