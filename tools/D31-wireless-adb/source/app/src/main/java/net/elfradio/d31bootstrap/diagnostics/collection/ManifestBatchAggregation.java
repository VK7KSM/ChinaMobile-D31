package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;

/** 只聚合计划闭合程度；不制造超大manifest，不把分批观察升级成同一时刻或全系统一致。 */
public final class ManifestBatchAggregation {
    private ManifestBatchAggregation() { }

    static boolean contentClosed(JSONObject m) throws Exception {
        if (!m.getString("completeness").equals("COMPLETE")) return false;
        JSONArray scopes = m.getJSONArray("scope"), entries = m.getJSONArray("entries");
        if (entries.length() == 0) return false;
        for (int i = 0; i < scopes.length(); i++) {
            JSONObject scope = scopes.getJSONObject(i); boolean listed = false;
            if (!scope.getString("state").equals("COMPLETE")) return false;
            for (int j = 0; j < entries.length(); j++)
                listed |= entries.getJSONObject(j).getString("path").equals(scope.getString("path"));
            if (!listed) return false;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i), p = e.getJSONObject("presence"), fields = e.getJSONObject("fields");
            if (!p.getString("state").equals("OBSERVED") || !p.optString("value").equals("PRESENT")) return false;
            for (String f : Arrays.asList("type", "mode", "uid", "gid"))
                if (!observed(fields, f)) return false;
            String type = fields.getJSONObject("type").getString("value");
            if (type.equals("file") && !observed(fields, "sha256")) return false;
            if (type.equals("symlink") && !observed(fields, "link")) return false;
            if (!Arrays.asList("file", "directory", "symlink").contains(type)) return false;
            JSONObject enumeration = fields.optJSONObject("semantic.enumeration");
            if (enumeration != null && !enumeration.optString("state").equals("OBSERVED")) return false;
        }
        return true;
    }
    private static boolean observed(JSONObject fields, String name) {
        JSONObject e = fields.optJSONObject(name); return e != null && e.optString("state").equals("OBSERVED");
    }

    public static JSONObject summarize(CollectionPlan plan) throws Exception {
        JSONArray roots = new JSONArray(); int done = 0, blocked = 0;
        for (CollectionPlan.Node n : plan.nodes.values()) {
            if (n.phase.equals("DONE")) done++;
            if (n.phase.equals("BLOCKED")) blocked++;
        }
        JSONArray declared = plan.config.getJSONArray("roots"); boolean closed = true;
        for (int i = 0; i < declared.length(); i++) {
            CollectionPlan.Node n = plan.nodes.get(declared.getString(i)); boolean complete = n.phase.equals("DONE");
            closed &= complete;
            roots.put(new JSONObject().put("path", n.path).put("inventory", complete ? "CLOSED_WITHIN_PLAN" : "INCOMPLETE"));
        }
        return new JSONObject().put("schemaVersion", 1).put("planSha256", CollectionPlan.digest(plan.config))
                .put("inventory", closed ? "CLOSED_WITHIN_PLAN" : "INCOMPLETE").put("roots", roots)
                .put("tasks", plan.nodes.size()).put("done", done).put("blocked", blocked)
                .put("pending", plan.nodes.size() - done - blocked).put("runnable", plan.next() != null)
                .put("originalCompleteBatches", plan.completeBatches).put("originalPartialBatches", plan.partialBatches)
                .put("readBytes", plan.readBytes).put("elapsedMs", plan.elapsedMs)
                .put("metadataNotCollected", new JSONArray(plan.completeBatches + plan.partialBatches == 0
                        ? Arrays.asList("selinux", "xattrs", "activeSource", "mountSource", "activation") : plan.metadataGaps))
                .put("userDataEquality", "NOT_REQUIRED_NOT_COLLECTED").put("partitionCoverage", "NOT_ASSESSED")
                .put("configurationSemantics", "NOT_ASSESSED").put("wholeSystemCoverage", "NOT_ESTABLISHED")
                .put("observationWindow", "PER_BATCH_READS_WITH_EXPANDED_DIRECTORY_PRE_POST_SEALS")
                .put("dynamicProductFilesEquality", "NOT_ASSESSED")
                .put("atomicSnapshot", false).put("systemConsistency", "NOT_ASSESSED").put("runtimeVerification", "NOT_PERFORMED")
                .put("repairPlanGenerated", false);
    }
}
