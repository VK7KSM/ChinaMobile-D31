package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.repair.RepairPlan;

/** 请求绑定方案摘要；沿用已有维护入口，不增加账户或口令。 */
final class RemoteRepairRequest {
    final String operation, task, digest;
    final RepairPlan plan;
    RemoteRepairRequest(JSONObject input) throws Exception {
        if (input == null || input.toString().length() > 60000) throw new IOException("修复请求过大");
        operation = input.getString("operation");
        if (!Arrays.asList("submit", "step", "run", "query").contains(operation)) throw new IOException("修复操作无效");
        Set<String> keys = new HashSet<>(Arrays.asList("operation", "task_id", "plan_sha256"));
        if (operation.equals("submit")) keys.add("plan");
        Iterator<String> names = input.keys();
        while (names.hasNext()) if (!keys.contains(names.next())) throw new IOException("未支持的修复字段");
        task = input.getString("task_id"); digest = input.getString("plan_sha256");
        if (!task.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}") || !digest.matches("[a-f0-9]{64}"))
            throw new IOException("修复编号或方案摘要无效");
        plan = operation.equals("submit") ? RepairPlan.fromJson(input.getJSONObject("plan")) : null;
        if (plan != null && (!task.equals(plan.taskId) || !digest.equals(plan.sha256())))
            throw new IOException("请求与实际修复方案不符");
    }
    void matches(JSONObject snapshot) throws Exception {
        if (!task.equals(snapshot.getString("task_id")) || !digest.equals(snapshot.getString("plan_sha256")))
            throw new IOException("存档方案与请求不符");
    }
}
