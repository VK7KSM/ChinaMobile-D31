package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static net.elfradio.d31bootstrap.diagnostics.DiagnosticContract.*;

/** 逐路径、逐字段规则，绑定已审阅的开发板与固件清单，不提供目录排除规则。 */
public final class DiagnosticRules {
    final JSONObject raw;
    final Set<String> scopes = new java.util.TreeSet<String>();
    final Map<String, JSONObject> allowances = new TreeMap<String, JSONObject>();
    final Map<String, JSONObject> notApplicable = new TreeMap<String, JSONObject>();

    private DiagnosticRules(JSONObject input) throws JSONException {
        raw = copy(input);
        keys(raw, "schemaVersion", "rulesVersion", "baselineId", "baselineRevision", "boardSnapshotId",
                "firmwareId", "firmwareSnapshotId", "context", "scope", "maxSnapshotAgeMs",
                "allowances", "notApplicable");
        if (number(raw, "schemaVersion") != SCHEMA_VERSION) throw invalid("UNSUPPORTED_SCHEMA");
        for (String key : new String[]{"rulesVersion", "baselineId", "baselineRevision", "boardSnapshotId",
                "firmwareId", "firmwareSnapshotId"}) string(raw, key);
        if (number(raw, "maxSnapshotAgeMs") == 0) throw invalid("INVALID_MAX_AGE");
        context(object(raw, "context"));
        JSONArray scope = array(raw, "scope", MAX_SCOPES);
        if (scope.length() == 0) throw invalid("EMPTY_SCOPE");
        for (int i = 0; i < scope.length(); i++) {
            String path = path(new JSONObject().put("path", scope.get(i)), "path");
            if (path.indexOf('*') >= 0 || path.indexOf('?') >= 0) throw invalid("WILDCARD_RULE");
            for (String previous : scopes) {
                if (within(path, previous) || within(previous, path)) throw invalid("OVERLAPPING_SCOPE");
            }
            scopes.add(path);
        }
        Set<String> ids = new HashSet<String>();
        readRules(array(raw, "allowances", MAX_RULES), allowances, ids, true);
        readRules(array(raw, "notApplicable", MAX_RULES), notApplicable, ids, false);
        if (allowances.size() + notApplicable.size() > MAX_RULES) throw invalid("INPUT_LIMIT");
        for (String key : allowances.keySet()) {
            if (notApplicable.containsKey(key)) throw invalid("CONFLICTING_RULE");
        }
    }

    private void readRules(JSONArray rules, Map<String, JSONObject> destination,
                           Set<String> ids, boolean allowance) throws JSONException {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = item(rules, i);
            if (allowance) keys(rule, "id", "path", "field", "reason", "basis", "board", "firmware", "target");
            else keys(rule, "id", "path", "field", "reason", "basis");
            if (!ids.add(string(rule, "id"))) throw invalid("DUPLICATE_RULE_ID");
            string(rule, "reason");
            string(rule, "basis");
            String path = path(rule, "path"), field = string(rule, "field");
            if (path.contains("*") || path.contains("?")) throw invalid("WILDCARD_RULE");
            if (!field(field) && !field.equals("presence")) throw invalid("UNKNOWN_COMPARISON_FIELD");
            if (!allowance && (field.equals("type") || field.equals("presence"))) throw invalid("INVALID_APPLICABILITY_RULE");
            boolean contained = false;
            for (String root : scopes) if (within(path, root)) contained = true;
            if (!contained) throw invalid("RULE_OUTSIDE_SCOPE");
            String key = key(path, field);
            if (destination.containsKey(key)) throw invalid("DUPLICATE_RULE_FIELD");
            if (allowance) {
                for (String side : new String[]{"board", "firmware", "target"}) {
                    JSONObject check = new JSONObject().put("state", "OBSERVED").put("source", "RULE")
                            .put("value", rule.get(side));
                    if (field.equals("presence")) evidence(check, true);
                    else fieldValue(field, check);
                }
                if (equal(rule.get("board"), rule.get("firmware")) && equal(rule.get("board"), rule.get("target"))) {
                    throw invalid("RULE_WITHOUT_DIFFERENCE");
                }
            }
            destination.put(key, rule);
        }
    }

    public static DiagnosticRules parse(JSONObject input) throws JSONException { return new DiagnosticRules(input); }
    public JSONObject toJson() throws JSONException { return new JSONObject(raw.toString()); }

    static String key(String path, String field) { return path + "\n" + field; }

    JSONObject allowance(String path, String field, JSONObject[] evidence) throws JSONException {
        JSONObject rule = allowances.get(key(path, field));
        if (rule == null) return null;
        String[] sides = {"board", "firmware", "target"};
        for (int i = 0; i < sides.length; i++) {
            if (!"OBSERVED".equals(evidence[i].optString("state"))
                    || !equal(rule.get(sides[i]), evidence[i].opt("value"))) return null;
        }
        return rule;
    }

    boolean acceptsNotApplicable(String path, String field, JSONObject type) {
        if (!"OBSERVED".equals(type.optString("state"))) return false;
        String kind = type.optString("value");
        if (field.equals("type") || field.equals("presence")) return false;
        if (field.equals("sha256") && (kind.equals("file") || kind.equals("block"))) return false;
        if (field.equals("link") && kind.equals("symlink")) return false;
        if ((field.equals("mode") || field.equals("uid") || field.equals("gid") || field.equals("selinux"))
                && !kind.equals("semantic")) return false;
        if (field.equals("link") && !kind.equals("symlink")) return true;
        if (field.equals("sha256") && (kind.equals("directory") || kind.equals("symlink"))) return true;
        return notApplicable.containsKey(key(path, field));
    }
}
