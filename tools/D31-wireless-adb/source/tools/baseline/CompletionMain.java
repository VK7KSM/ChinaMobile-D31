package net.elfradio.d31bootstrap.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** 离线旁注；原比较器与清单保持不变，不提供修复或成功判定。 */
public final class CompletionMain {
    static final String ROOT = "/data/local/d31-startup-handover/";
    static final String[] LEAVES = {"factory-init-required", "factory-init-complete",
            "factory-runtime-complete", "start.sh", "handover.jar"};
    static final String SCRIPT = "35a9963961dc7e30c9d9be2b0e5ebf37783582c8db78d5ca2f799f577da6e615";
    static final String JAR = "1dc959ec6e6513d48b8894042c347b749057690dd2674eea69ff639b84794978";
    static final String HISTORICAL_JAR = "3e12fe7cdf595483d66b72a9a3ab371a17ee58b2c801155c0b24f4f5481d85ec";
    private static final String[] EXPLANATIONS = {
        "三个标记均有缺失证据；不能据此判定曾否初始化或初始化失败。",
        "仅运行阶段标记存在；不能证明第一阶段完成历史或推断应补标记。",
        "第一阶段标记存在、请求和运行阶段标记缺失；仅满足运行阶段源码标记门。",
        "两阶段标记存在、请求缺失；不能证明当前组件、权限或私人配置仍符合期望。",
        "仅请求标记存在；仅满足初始化请求的源码标记门，不表示正在执行。",
        "请求及运行阶段标记存在、第一阶段标记缺失；来源与执行次序不能由标记确定。",
        "请求及第一阶段标记并存；可能尚未删除请求、删除失败或另次请求，不可据此重跑。",
        "三个标记并存；不能确定历史次序，不可据此重跑或删除标记。"
    };

    private static Object pointer(DiagnosticManifest m, String path, String field) throws Exception {
        JSONArray entries = m.raw.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (path.equals(entry.getString("path")) && (field.equals("presence")
                    || entry.getJSONObject("fields").has(field)))
                return "/entries/" + i + (field.equals("presence") ? "/presence" : "/fields/" + field);
        }
        return JSONObject.NULL;
    }

    private static JSONObject fact(DiagnosticManifest m, int index, int leaf) throws Exception {
        String path = ROOT + LEAVES[leaf];
        JSONObject p = m.presence(path), type = m.fieldEvidence(path, "type");
        boolean known = "OBSERVED".equals(p.getString("state"));
        String presence = known ? p.getString("value") : "UNKNOWN";
        String shape = "OBSERVED".equals(type.getString("state")) ? type.getString("value") : "UNKNOWN";
        String marker = presence.equals("ABSENT") ? "ABSENT" : presence.equals("PRESENT")
                && shape.equals("file") ? "REGULAR_FILE" : "UNKNOWN";
        Object scopePointer = JSONObject.NULL;
        JSONArray scopes = m.raw.getJSONArray("scope");
        for (int i = 0; i < scopes.length(); i++)
            if (DiagnosticContract.within(path, scopes.getJSONObject(i).getString("path"))) scopePointer = "/scope/" + i;
        JSONObject result = new JSONObject().put("path", path).put("inputIndex", index)
                .put("presence", presence).put("presenceState", p.getString("state"))
                .put("presencePointer", pointer(m, path, "presence")).put("scopePointer", scopePointer)
                .put("inferredAbsence", "ABSENT_FROM_COMPLETE_ENUMERATION".equals(p.optString("reason")))
                .put("type", shape).put("typeState", type.getString("state"))
                .put("typePointer", pointer(m, path, "type")).put("markerFact", marker);
        if (leaf >= 3) {
            JSONObject hash = m.fieldEvidence(path, "sha256");
            String expected = leaf == 3 ? SCRIPT : JAR;
            String binding = marker.equals("REGULAR_FILE") && "OBSERVED".equals(hash.getString("state"))
                    ? (expected.equals(hash.getString("value")) ? "MATCH" : "MISMATCH") : "UNKNOWN";
            result.put("sha256State", hash.getString("state")).put("sha256Pointer", pointer(m, path, "sha256"))
                    .put("expectedSha256", expected).put("consumerBinding", binding);
            result.put("knownSource", binding.equals("MATCH") ? "FORMAL_1_4_4"
                    : leaf == 4 && binding.equals("MISMATCH") && HISTORICAL_JAR.equals(hash.optString("value"))
                    ? "HISTORICAL_20260911_CELLULAR_EMPTY_SIP" : "NOT_IDENTIFIED");
        }
        return result;
    }

    private static JSONObject missing(int leaf) throws Exception {
        JSONObject r = new JSONObject().put("path", ROOT + LEAVES[leaf]).put("inputIndex", JSONObject.NULL)
                .put("presence", "UNKNOWN").put("presenceState", "NOT_CHECKED")
                .put("presencePointer", JSONObject.NULL).put("scopePointer", JSONObject.NULL)
                .put("inferredAbsence", false).put("type", "UNKNOWN").put("typeState", "NOT_CHECKED")
                .put("typePointer", JSONObject.NULL).put("markerFact", "UNKNOWN");
        if (leaf >= 3) r.put("sha256State", "NOT_CHECKED").put("sha256Pointer", JSONObject.NULL)
                .put("expectedSha256", leaf == 3 ? SCRIPT : JAR).put("consumerBinding", "UNKNOWN")
                .put("knownSource", "NOT_IDENTIFIED");
        return r;
    }

    static JSONObject interpret(DiagnosticManifest[] observations, DiagnosticManifest firmware, long now) throws Exception {
        if (observations.length < 1 || observations.length > 5) throw new IllegalArgumentException("采集输入限定一至五份");
        JSONArray coverage = new JSONArray(), facts = new JSONArray(), firmwareFacts = new JSONArray();
        for (DiagnosticManifest m : observations) coverage.put(new DiagnosticCoverageComparison().compare(m, firmware, now));
        for (int leaf = 0; leaf < LEAVES.length; leaf++) {
            JSONObject chosen = null;
            for (int i = 0; i < observations.length; i++) {
                if (observations[i].scopeFor(ROOT + LEAVES[leaf]) == null) continue;
                if (chosen != null) throw new IllegalArgumentException("固定叶路径有多份采集范围，禁止择优或合并");
                chosen = fact(observations[i], i, leaf);
            }
            facts.put(chosen == null ? missing(leaf) : chosen);
            firmwareFacts.put(fact(firmware, 0, leaf));
        }
        String script = facts.getJSONObject(3).getString("consumerBinding");
        String jar = facts.getJSONObject(4).getString("consumerBinding");
        String binding = script.equals("MISMATCH") || jar.equals("MISMATCH") ? "MISMATCH"
                : script.equals("MATCH") && jar.equals("MATCH") ? "MATCH" : "UNKNOWN";
        JSONArray reasons = new JSONArray();
        if (!binding.equals("MATCH")) reasons.put("CONSUMER_BINDING_" + binding);
        if (observations.length != 1) reasons.put("CROSS_BATCH_COMBINATION_NOT_ESTABLISHED");
        int combination = 0;
        for (int i = 0; i < 3; i++) {
            String value = facts.getJSONObject(i).getString("markerFact");
            if (value.equals("UNKNOWN")) reasons.put("MARKER_" + i + "_UNKNOWN");
            combination = combination * 2 + (value.equals("REGULAR_FILE") ? 1 : 0);
        }
        JSONObject meaning = new JSONObject().put("status", "UNKNOWN").put("reasons", reasons)
                .put("combination", JSONObject.NULL).put("shellRequestGate", "UNKNOWN")
                .put("javaApplyRequestGate", "UNKNOWN").put("runtimeMarkerGate", "UNKNOWN")
                .put("explanation", "仅保留逐叶事实；证据不足或消费者不同，不套用正式1.4.4消费者语义。");
        if (reasons.length() == 0) {
            boolean required = (combination & 4) != 0, complete = (combination & 2) != 0, runtime = (combination & 1) != 0;
            meaning.put("status", "CONDITIONAL_SOURCE_PREDICATES_ONLY").put("combination", String.format("%3s", Integer.toBinaryString(combination)).replace(' ', '0'))
                    .put("shellRequestGate", required ? "SATISFIED" : "NOT_SATISFIED")
                    .put("javaApplyRequestGate", required ? "SATISFIED" : "NOT_SATISFIED")
                    .put("runtimeMarkerGate", complete && !runtime ? "SATISFIED" : "NOT_SATISFIED")
                    .put("explanation", EXPLANATIONS[combination]);
        }
        JSONArray stages = new JSONArray(), freshness = new JSONArray();
        for (int i = 0; i < observations.length; i++) {
            DiagnosticManifest m = observations[i];
            String stage = m.raw.getJSONObject("context").getString("stage");
            stages.put(stage.equals("RUNNING") || stage.equals("POST_INSTALL_BEFORE_FIRST_BOOT") ? stage : "OTHER_OR_UNSPECIFIED");
            freshness.put(coverage.getJSONObject(i).getJSONObject("observation").getString("freshness"));
        }
        String fwStage = firmware.raw.getJSONObject("context").getString("stage");
        JSONObject sidecar = new JSONObject().put("itemId", "initialization.completion")
                .put("method", "OFFLINE_FIXED_FIVE_LEAF_FACT_INTERPRETATION_V1")
                .put("observationFacts", facts).put("firmwareFacts", firmwareFacts)
                .put("consumerBinding", binding).put("semantics", meaning)
                .put("observationFreshness", freshness)
                .put("firmwareFreshness", coverage.getJSONObject(0).getJSONObject("firmware").getString("freshness"))
                .put("observationStages", stages).put("firmwareStage", fwStage.equals("POST_INSTALL_BEFORE_FIRST_BOOT")
                        ? fwStage : "OTHER_OR_UNSPECIFIED")
                .put("stagePolicy", "HISTORICAL_FACTS_ONLY_NO_RUNTIME_REPAIR_FROM_PREBOOT_EXPECTATION")
                .put("catalogDisposition", "KEEP_ORIGINAL_CONFIGURATION_GAP")
                .put("atomicSnapshotEstablished", false).put("crossInputIdentityAndBootBinding", "NOT_VERIFIED")
                .put("runtimeVerification", "NOT_PERFORMED").put("systemConsistency", "NOT_ASSESSED")
                .put("repairPlanGenerated", false)
                .put("limitations", new JSONArray().put("标记和摘要只代表采集事实，不证明同一时刻或当前状态。")
                        .put("条件解释不证明脚本运行、恢复分支、禁用门、版本代次、权限或组件实授。")
                        .put("三位顺序为请求、第一阶段、运行阶段；1仅表示普通文件，0仅表示有缺失证据。")
                        .put("标记内容版本不参与消费者门判断；本工具不读取正文、不生成修复。"));
        return new JSONObject().put("coverageReports", coverage).put("completion", sidecar);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("参数：规范化私有输入 新输出 时间毫秒");
        JSONObject input = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        JSONArray array = input.getJSONArray("observations");
        DiagnosticManifest[] observations = new DiagnosticManifest[array.length()];
        for (int i = 0; i < array.length(); i++) observations[i] = DiagnosticManifest.parse(array.getJSONObject(i));
        JSONObject report = interpret(observations, DiagnosticManifest.parse(input.getJSONObject("firmware")), Long.parseLong(args[2]));
        Files.write(Paths.get(args[1]), report.toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
