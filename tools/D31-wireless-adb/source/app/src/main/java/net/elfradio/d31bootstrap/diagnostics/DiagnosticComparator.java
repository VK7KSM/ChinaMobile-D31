package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static net.elfradio.d31bootstrap.diagnostics.DiagnosticContract.*;

/** 无状态三方离线比较，差异仅为观察事实，不作为根因裁定。 */
public final class DiagnosticComparator {
    private static final String[] SIDES = {"board", "firmware", "target"};
    private static final String[] ROLES = {"BOARD", "FIRMWARE", "TARGET"};

    public JSONObject compare(DiagnosticManifest board, DiagnosticManifest firmware,
                              DiagnosticManifest target, DiagnosticRules rules, long nowMs) throws JSONException {
        if (board == null || firmware == null || target == null || rules == null || nowMs < 0) {
            throw invalid("INVALID_COMPARISON_ARGUMENT");
        }
        DiagnosticManifest[] manifests = {board, firmware, target};
        JSONArray blockers = bindings(manifests, rules, nowMs);
        boolean comparable = blockers.length() == 0;
        Set<String> paths = new TreeSet<String>();
        Set<String> scopePaths = new TreeSet<String>(rules.scopes);
        JSONObject references = new JSONObject();
        for (int i = 0; i < manifests.length; i++) {
            DiagnosticManifest m = manifests[i];
            paths.addAll(m.entries.keySet());
            scopePaths.addAll(m.scopes.keySet());
            JSONObject reference = new JSONObject(m.raw.toString());
            reference.remove("entries");
            references.put(SIDES[i], reference);
        }
        if (paths.size() > MAX_ENTRIES) throw invalid("COMPARISON_LIMIT");
        JSONArray scopeRows = new JSONArray();
        int scopesVerified = 0, scopesFailed = 0, scopesUnchecked = 0;
        boolean inventoryComplete = comparable;
        for (DiagnosticManifest m : manifests) inventoryComplete &= m.inventoryComplete();
        for (String scopePath : scopePaths) {
            JSONObject scopeEvidence = new JSONObject();
            boolean complete = comparable, failed = false;
            for (int i = 0; i < manifests.length; i++) {
                JSONObject evidence = manifests[i].scopes.get(scopePath);
                if (evidence == null) evidence = new JSONObject().put("state", "NOT_CHECKED")
                        .put("reason", "SCOPE_NOT_DECLARED").put("source", manifests[i].raw.getString("snapshotId"));
                scopeEvidence.put(SIDES[i], detached(evidence));
                complete &= "COMPLETE".equals(evidence.optString("state"));
                failed |= "READ_FAILED".equals(evidence.optString("state"));
            }
            String status = failed ? "READ_FAILED" : complete ? "VERIFIED" : "NOT_CHECKED";
            if (failed) scopesFailed++; else if (complete) scopesVerified++; else scopesUnchecked++;
            scopeRows.put(new JSONObject().put("path", scopePath).put("status", status).put("evidence", scopeEvidence));
        }
        JSONArray rows = new JSONArray();
        Set<String> usedRules = new TreeSet<String>();
        int verified = 0, unchecked = 0, failed = 0, abnormal = 0, allowed = 0, same = 0;
        for (String path : paths) {
            JSONArray fields = new JSONArray();
            JSONObject[] presence = new JSONObject[3];
            for (int i = 0; i < 3; i++) presence[i] = manifests[i].presence(path);
            JSONObject presenceResult = compareField(path, "presence", presence, manifests, rules, comparable, usedRules);
            fields.put(presenceResult);
            boolean allPresent = true;
            for (JSONObject p : presence) allPresent &= "OBSERVED".equals(p.optString("state")) && "PRESENT".equals(p.optString("value"));
            Set<String> names = new TreeSet<String>();
            boolean anyPresent = false;
            for (int i = 0; i < 3; i++) {
                anyPresent |= "PRESENT".equals(presence[i].optString("value"));
                JSONObject entry = manifests[i].entries.get(path);
                if (entry != null) {
                    Iterator<String> iterator = object(entry, "fields").keys();
                    while (iterator.hasNext()) names.add(iterator.next());
                }
            }
            if (anyPresent) names.addAll(Arrays.asList(REQUIRED_FIELDS));
            if (names.size() > 48) throw invalid("COMPARISON_LIMIT");
            for (String name : names) {
                JSONObject[] values = new JSONObject[3];
                for (int i = 0; i < 3; i++) values[i] = manifests[i].fieldEvidence(path, name);
                JSONObject result = compareField(path, name, values, manifests, rules, comparable, usedRules);
                // 确认缺失的一方没有元数据；保留其他存在方的比较，不把缺失对象的元数据当采集缺口。
                result.put("presenceDependent", !allPresent);
                fields.put(result);
            }
            boolean readFailed = false, unknown = !comparable, hasAbnormal = false, hasAllowed = false;
            for (int i = 0; i < fields.length(); i++) {
                JSONObject f = fields.getJSONObject(i);
                String status = f.getString("classification");
                if (f.optBoolean("presenceDependent", false)) {
                    // 其他方的文件缺失不能掩盖存在方的元数据读取失败。
                    for (int side = 0; side < 3; side++) {
                        if (!"PRESENT".equals(presence[side].optString("value"))) continue;
                        String state = object(object(f, "evidence"), SIDES[side]).optString("state");
                        readFailed |= state.equals("READ_FAILED");
                        boolean applicable = state.equals("NOT_APPLICABLE") && rules.acceptsNotApplicable(path,
                                f.getString("field"), manifests[side].fieldEvidence(path, "type"));
                        unknown |= !state.equals("OBSERVED") && !applicable;
                    }
                } else {
                    readFailed |= f.getBoolean("readFailed");
                    unknown |= f.getBoolean("incomplete");
                }
                hasAbnormal |= status.equals("ANOMALOUS_DIFFERENCE");
                hasAllowed |= status.equals("ALLOWED_DIFFERENCE");
            }
            String coverage = readFailed ? "READ_FAILED" : unknown ? "NOT_CHECKED" : "VERIFIED";
            if (readFailed) failed++; else if (unknown) unchecked++; else verified++;
            if (hasAbnormal) abnormal++;
            if (hasAllowed) allowed++;
            if (!readFailed && !unknown && !hasAbnormal && !hasAllowed) same++;
            String classification = readFailed ? "READ_FAILED" : unknown ? "INSUFFICIENT_EVIDENCE"
                    : hasAbnormal ? "ANOMALOUS_DIFFERENCE" : hasAllowed ? "ALLOWED_DIFFERENCE" : "SAME";
            rows.put(new JSONObject().put("path", path).put("coverage", coverage).put("classification", classification)
                    .put("hasAnomalousDifference", hasAbnormal).put("hasAllowedDifference", hasAllowed)
                    .put("fields", fields).put("rootCause", "NOT_ESTABLISHED")
                    .put("impact", hasAbnormal ? "REQUIRES_ANALYSIS" : "NOT_ASSESSED")
                    .put("nextAction", readFailed || unknown ? "COLLECT_MISSING_READ_ONLY_EVIDENCE"
                            : hasAbnormal ? "REVIEW_SOURCES_AND_LIFECYCLE_EVIDENCE" : "VERIFY_RUNTIME_SEPARATELY"));
        }
        boolean complete = inventoryComplete && scopesUnchecked == 0 && scopesFailed == 0
                && failed == 0 && unchecked == 0 && !paths.isEmpty();
        String outcome = !comparable ? "NOT_COMPARABLE" : !complete ? "INSUFFICIENT_EVIDENCE"
                : abnormal > 0 ? "DIFFERENCES" : allowed > 0 ? "ALLOWED_DIFFERENCES" : "MATCH_WITHIN_SCOPE";
        JSONArray unused = new JSONArray();
        for (JSONObject rule : rules.allowances.values()) if (!usedRules.contains(rule.getString("id"))) unused.put(rule.getString("id"));
        for (JSONObject rule : rules.notApplicable.values()) if (!usedRules.contains(rule.getString("id"))) unused.put(rule.getString("id"));
        return new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("engineVersion", "1.0.0")
                .put("comparisonExtent", "SINGLE_BOUNDED_BATCH").put("wholeSystemCoverage", "NOT_ESTABLISHED")
                .put("aggregationStatus", "NOT_IMPLEMENTED").put("limits", limits())
                .put("rulesVersion", rules.raw.getString("rulesVersion")).put("evaluatedAtMs", nowMs)
                .put("references", references).put("rules", rules.toJson()).put("blockers", blockers)
                .put("scope", scopeRows).put("scopeCounts", counts(scopePaths.size(), scopesVerified, scopesUnchecked, scopesFailed))
                .put("counts", counts(paths.size(), verified, unchecked, failed).put("same", same)
                        .put("abnormal", abnormal).put("allowed", allowed))
                .put("inventoryTotalKnown", inventoryComplete).put("evidenceComplete", complete)
                .put("offlineComparison", outcome).put("systemConsistency", "NOT_ASSESSED")
                .put("runtimeVerification", "NOT_PERFORMED").put("rootCause", "NOT_ESTABLISHED")
                .put("unusedRuleIds", unused).put("items", rows);
    }

    private JSONArray bindings(DiagnosticManifest[] manifests, DiagnosticRules rules, long nowMs) throws JSONException {
        JSONArray blockers = new JSONArray();
        Set<String> ids = new TreeSet<String>();
        for (int i = 0; i < manifests.length; i++) {
            DiagnosticManifest m = manifests[i];
            if (!ROLES[i].equals(m.raw.getString("role"))) block(blockers, SIDES[i], "ROLE_MISMATCH");
            if (!ids.add(m.raw.getString("snapshotId"))) block(blockers, SIDES[i], "DUPLICATE_SNAPSHOT_ID");
            for (String key : new String[]{"baselineId", "baselineRevision", "firmwareId"}) {
                if (!equal(m.raw.get(key), rules.raw.get(key))) block(blockers, SIDES[i], "BINDING_MISMATCH:" + key);
            }
            if (!equal(m.raw.get("context"), rules.raw.get("context"))) block(blockers, SIDES[i], "CONTEXT_MISMATCH");
            if (!equal(m.raw.get("build"), manifests[1].raw.get("build"))) block(blockers, SIDES[i], "BUILD_MISMATCH");
            if (!m.scopes.keySet().equals(rules.scopes)) block(blockers, SIDES[i], "SCOPE_MISMATCH");
            long captured = m.raw.getLong("capturedAtMs");
            if (captured > nowMs || nowMs > m.raw.getLong("validUntilMs")
                    || nowMs - captured > rules.raw.getLong("maxSnapshotAgeMs")) block(blockers, SIDES[i], "STALE_OR_FUTURE_SNAPSHOT");
        }
        if (!equal(manifests[0].raw.get("snapshotId"), rules.raw.get("boardSnapshotId"))) block(blockers, "board", "BOARD_SNAPSHOT_MISMATCH");
        if (!equal(manifests[1].raw.get("snapshotId"), rules.raw.get("firmwareSnapshotId"))) block(blockers, "firmware", "FIRMWARE_SNAPSHOT_MISMATCH");
        return blockers;
    }

    private JSONObject compareField(String path, String field, JSONObject[] values, DiagnosticManifest[] manifests,
                                    DiagnosticRules rules, boolean comparable, Set<String> usedRules) throws JSONException {
        JSONObject evidence = new JSONObject();
        boolean[] known = new boolean[3];
        boolean readFailed = false, incomplete = !comparable;
        for (int i = 0; i < 3; i++) {
            evidence.put(SIDES[i], detached(values[i]));
            String state = values[i].getString("state");
            known[i] = state.equals("OBSERVED");
            if (state.equals("NOT_APPLICABLE") && rules.acceptsNotApplicable(path, field, manifests[i].fieldEvidence(path, "type"))) {
                known[i] = true;
                JSONObject applicability = rules.notApplicable.get(DiagnosticRules.key(path, field));
                if (comparable && applicability != null) usedRules.add(applicability.getString("id"));
            }
            readFailed |= state.equals("READ_FAILED");
            incomplete |= !known[i];
        }
        String bt = pair(values, known, 0, 2, comparable), bf = pair(values, known, 0, 1, comparable), ft = pair(values, known, 1, 2, comparable);
        boolean difference = bt.equals("DIFFERENT") || bf.equals("DIFFERENT") || ft.equals("DIFFERENT");
        JSONObject rule = comparable && !incomplete ? rules.allowance(path, field, values) : null;
        if (rule != null) usedRules.add(rule.getString("id"));
        String classification = !comparable ? "NOT_COMPARABLE" : difference
                ? (rule == null ? "ANOMALOUS_DIFFERENCE" : "ALLOWED_DIFFERENCE")
                : readFailed ? "READ_FAILED" : incomplete ? "INSUFFICIENT_EVIDENCE" : "SAME";
        String relation = incomplete ? "PARTIAL_EVIDENCE" : !difference ? "ALL_EQUAL"
                : bf.equals("SAME") ? "TARGET_ONLY" : bt.equals("SAME") ? "FIRMWARE_ONLY"
                : ft.equals("SAME") ? "BOARD_ONLY" : "ALL_DIFFERENT";
        JSONObject result = new JSONObject().put("field", field).put("evidence", evidence)
                .put("pairs", new JSONObject().put("boardTarget", bt).put("boardFirmware", bf).put("firmwareTarget", ft))
                .put("classification", classification).put("relation", relation).put("incomplete", incomplete)
                .put("readFailed", readFailed).put("category", category(field));
        if (rule != null) result.put("allowance", detached(rule));
        JSONObject applicability = rules.notApplicable.get(DiagnosticRules.key(path, field));
        if (applicability != null) result.put("applicabilityRule", detached(applicability));
        return result;
    }

    private static String pair(JSONObject[] values, boolean[] known, int a, int b, boolean comparable) throws JSONException {
        if (!comparable) return "NOT_COMPARABLE";
        if (!known[a] || !known[b]) return "UNKNOWN";
        if (!values[a].getString("state").equals(values[b].getString("state"))) return "DIFFERENT";
        return values[a].getString("state").equals("NOT_APPLICABLE") || equal(values[a].opt("value"), values[b].opt("value"))
                ? "SAME" : "DIFFERENT";
    }

    private static String category(String field) {
        if (field.equals("presence")) return "PRESENCE";
        if (field.equals("sha256")) return "CONTENT";
        if (field.equals("activeSource") || field.equals("mountSource") || field.equals("activation")) return "ACTIVE_SOURCE";
        if (field.startsWith("semantic.")) return "SEMANTIC";
        return "METADATA";
    }

    private static JSONObject counts(int total, int verified, int unchecked, int failed) throws JSONException {
        return new JSONObject().put("total", total).put("verified", verified).put("unchecked", unchecked).put("failed", failed);
    }

    private static void block(JSONArray blockers, String side, String code) throws JSONException {
        blockers.put(new JSONObject().put("side", side).put("code", code));
    }

    private static JSONObject detached(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }
}
