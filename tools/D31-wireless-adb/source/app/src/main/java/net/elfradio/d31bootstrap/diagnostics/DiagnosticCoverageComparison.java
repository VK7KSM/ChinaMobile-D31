package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static net.elfradio.d31bootstrap.diagnostics.DiagnosticContract.*;

/** 两份既有清单的证据覆盖对照；不把历史采集升级成基准或三方诊断。 */
public final class DiagnosticCoverageComparison {
    public JSONObject compare(DiagnosticManifest observation, DiagnosticManifest firmware, long nowMs)
            throws JSONException {
        if (nowMs < 0) throw invalid("NEGATIVE_NUMBER");
        if (!("BOARD".equals(observation.raw.getString("role"))
                || "TARGET".equals(observation.raw.getString("role")))
                || !"FIRMWARE".equals(firmware.raw.getString("role"))) throw invalid("ROLE_MISMATCH");
        Set<String> paths = new TreeSet<String>(observation.entries.keySet());
        paths.addAll(firmware.entries.keySet());
        if (paths.size() > MAX_ENTRIES) throw invalid("COMPARISON_LIMIT");
        JSONArray rows = new JSONArray();
        JSONObject totals = counts();
        Map<String, JSONObject> categories = new TreeMap<String, JSONObject>();
        int common = 0, observationOnly = 0, firmwareOnly = 0;
        Map<String, Integer> oi = indexes(observation), fi = indexes(firmware);
        for (String path : paths) {
            boolean oListed = observation.entries.containsKey(path), fListed = firmware.entries.containsKey(path);
            if (oListed && fListed) common++;
            else if (oListed) observationOnly++;
            else firmwareOnly++;
            JSONObject op = observation.presence(path), fp = firmware.presence(path);
            boolean present = isPresent(op) && isPresent(fp);
            Set<String> names = new TreeSet<String>(Arrays.asList(REQUIRED_FIELDS));
            addNames(names, observation.entries.get(path));
            addNames(names, firmware.entries.get(path));
            if (names.size() > 48) throw invalid("COMPARISON_LIMIT");
            JSONArray fields = new JSONArray();
            fields.put(pair("presence", op, fp, true, pointer(observation, oi, path, "presence"),
                    pointer(firmware, fi, path, "presence"), totals, categories));
            for (String name : names) {
                fields.put(pair(name, observation.fieldEvidence(path, name), firmware.fieldEvidence(path, name),
                        present, pointer(observation, oi, path, "fields/" + name), pointer(firmware, fi, path, "fields/" + name),
                        totals, categories));
            }
            rows.put(new JSONObject().put("path", path).put("observationListed", oListed)
                    .put("firmwareListed", fListed).put("fields", fields));
        }
        JSONObject context = new JSONObject();
        for (String name : CONTEXT_FIELDS) context.put(name,
                equal(observation.raw.getJSONObject("context").get(name), firmware.raw.getJSONObject("context").get(name)));
        JSONObject binding = new JSONObject();
        for (String name : new String[]{"build", "baselineId", "baselineRevision", "firmwareId"})
            binding.put(name, equal(observation.raw.get(name), firmware.raw.get(name)));
        JSONObject groups = new JSONObject();
        for (String name : new String[]{"PRESENCE", "FILE_CONTENT", "METADATA", "CONFIGURATION_SEMANTICS",
                "ACTIVATION", "PERSONAL_DATA"}) groups.put(name, categories.containsKey(name) ? categories.get(name) : counts());
        JSONArray gaps = new JSONArray();
        if (!categories.containsKey("CONFIGURATION_SEMANTICS")) gaps.put("CONFIGURATION_SEMANTICS_NOT_COLLECTED");
        gaps.put("PERSONAL_DATA_VALUES_NOT_COPIED");
        gaps.put("BOARD_BASELINE_APPROVAL_NOT_VERIFIED");
        gaps.put("THREE_PARTY_RULES_NOT_APPLIED");
        gaps.put("FULL_TREE_AND_PARTITION_AGGREGATION_NOT_IMPLEMENTED");
        return new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("engineVersion", "coverage-1.0.0")
                .put("derivedAtMs", nowMs).put("method", "HISTORICAL_TWO_INPUT_EVIDENCE_COVERAGE")
                .put("offlineComparison", "NOT_COMPARED").put("rootCause", "NOT_ESTABLISHED")
                .put("comparisonExtent", "SINGLE_BOUNDED_BATCH").put("wholeSystemCoverage", "NOT_ESTABLISHED")
                .put("aggregationStatus", "NOT_IMPLEMENTED").put("systemConsistency", "NOT_ASSESSED")
                .put("runtimeVerification", "NOT_PERFORMED").put("repairPlanGenerated", false)
                .put("outputPrivacy", "PRIVATE_DERIVED_REPORT_NO_EVIDENCE_VALUES")
                .put("bindingEqual", binding).put("contextEqual", context)
                .put("observation", summary(observation, nowMs)).put("firmware", summary(firmware, nowMs))
                .put("counts", new JSONObject().put("knownPaths", paths.size()).put("intersectionPaths", common)
                        .put("observationOnlyPaths", observationOnly).put("firmwareOnlyPaths", firmwareOnly)
                        .put("inventoryTotalKnown", false).put("fields", totals))
                .put("categories", groups).put("gaps", gaps).put("entries", rows);
    }

    private static JSONObject summary(DiagnosticManifest manifest, long now) throws JSONException {
        JSONArray scopes = new JSONArray();
        for (Map.Entry<String, JSONObject> scope : manifest.scopes.entrySet())
            scopes.put(new JSONObject().put("path", scope.getKey()).put("state", scope.getValue().getString("state")));
        long captured = manifest.raw.getLong("capturedAtMs"), until = manifest.raw.getLong("validUntilMs");
        return new JSONObject().put("role", manifest.raw.getString("role"))
                .put("capturedAtMs", captured).put("validUntilMs", until)
                .put("freshness", now < captured ? "FUTURE" : now > until ? "EXPIRED" : "WITHIN_DECLARED_WINDOW")
                .put("maxSnapshotAgeRuleChecked", false).put("atomicSnapshotEstablished", false)
                .put("completeness", manifest.raw.getString("completeness")).put("scope", scopes);
    }

    private static JSONObject counts() throws JSONException {
        return new JSONObject().put("total", 0).put("SAME", 0).put("DIFFERENT", 0).put("UNKNOWN", 0)
                .put("readFailed", 0).put("notChecked", 0).put("unstable", 0).put("redacted", 0)
                .put("notApplicableUnverified", 0);
    }

    private static void increment(JSONObject count, String key) throws JSONException {
        count.put(key, count.getInt(key) + 1);
    }

    private static JSONObject pair(String field, JSONObject left, JSONObject right, boolean presenceKnown,
                                   Object lp, Object rp, JSONObject totals, Map<String, JSONObject> groups)
            throws JSONException {
        String ls = left.getString("state"), rs = right.getString("state");
        boolean known = presenceKnown && ls.equals("OBSERVED") && rs.equals("OBSERVED");
        String result = known ? (equal(left.get("value"), right.get("value")) ? "SAME" : "DIFFERENT") : "UNKNOWN";
        String base = category(field);
        String group = ls.equals("REDACTED") || rs.equals("REDACTED") ? "PERSONAL_DATA" : base;
        if (!groups.containsKey(group)) groups.put(group, counts());
        JSONArray reasons = new JSONArray();
        for (JSONObject count : new JSONObject[]{totals, groups.get(group)}) {
            increment(count, "total"); increment(count, result);
            for (String[] state : new String[][]{{"READ_FAILED", "readFailed"}, {"NOT_CHECKED", "notChecked"},
                    {"UNSTABLE", "unstable"}, {"REDACTED", "redacted"}, {"NOT_APPLICABLE", "notApplicableUnverified"}}) {
                if (ls.equals(state[0]) || rs.equals(state[0])) increment(count, state[1]);
            }
        }
        if (!presenceKnown) reasons.put("PRESENCE_NOT_CONFIRMED_BOTH_SIDES");
        if (!ls.equals("OBSERVED")) reasons.put("OBSERVATION_" + ls);
        if (!rs.equals("OBSERVED")) reasons.put("FIRMWARE_" + rs);
        if (known) reasons.put("RAW_VALUES_ONLY_NO_ALLOWED_DIFFERENCE_RULES");
        return new JSONObject().put("field", field).put("category", group).put("baseCategory", base)
                .put("pair", result).put("reasons", reasons)
                .put("observation", reference(left, lp)).put("firmware", reference(right, rp));
    }

    private static JSONObject reference(JSONObject evidence, Object pointer) throws JSONException {
        return new JSONObject().put("state", evidence.getString("state")).put("pointer", pointer)
                .put("inferredAbsence", "ABSENT_FROM_COMPLETE_ENUMERATION".equals(evidence.optString("reason")));
    }

    private static String category(String field) {
        if (field.equals("presence")) return "PRESENCE";
        if (field.equals("sha256")) return "FILE_CONTENT";
        if (field.startsWith("semantic.")) return "CONFIGURATION_SEMANTICS";
        if (field.equals("activeSource") || field.equals("mountSource") || field.equals("activation")) return "ACTIVATION";
        return "METADATA";
    }

    private static boolean isPresent(JSONObject presence) {
        return "OBSERVED".equals(presence.optString("state")) && "PRESENT".equals(presence.optString("value"));
    }

    private static void addNames(Set<String> names, JSONObject entry) throws JSONException {
        if (entry == null) return;
        Iterator<String> keys = entry.getJSONObject("fields").keys();
        while (keys.hasNext()) names.add(keys.next());
    }

    private static Map<String, Integer> indexes(DiagnosticManifest manifest) throws JSONException {
        Map<String, Integer> indexes = new TreeMap<String, Integer>();
        JSONArray entries = manifest.raw.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) indexes.put(entries.getJSONObject(i).getString("path"), i);
        return indexes;
    }

    private static Object pointer(DiagnosticManifest manifest, Map<String, Integer> indexes, String path, String suffix)
            throws JSONException {
        if (suffix.startsWith("fields/") && (!manifest.entries.containsKey(path)
                || !manifest.entries.get(path).getJSONObject("fields").has(suffix.substring(7)))) return JSONObject.NULL;
        return indexes.containsKey(path) ? "/entries/" + indexes.get(path) + "/" + suffix : JSONObject.NULL;
    }
}
