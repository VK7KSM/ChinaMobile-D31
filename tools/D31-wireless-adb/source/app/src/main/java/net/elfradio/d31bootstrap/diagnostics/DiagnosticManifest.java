package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import static net.elfradio.d31bootstrap.diagnostics.DiagnosticContract.*;

/** 验证并隔离输入快照，外部不能通过原对象或返回值修改保存的证据。 */
public final class DiagnosticManifest {
    final JSONObject raw;
    final Map<String, JSONObject> scopes = new TreeMap<String, JSONObject>();
    final Map<String, JSONObject> entries = new TreeMap<String, JSONObject>();

    private DiagnosticManifest(JSONObject input) throws JSONException {
        raw = copy(input);
        keys(raw, "schemaVersion", "role", "snapshotId", "baselineId", "baselineRevision", "firmwareId",
                "build", "collectorVersion", "capturedAtMs", "validUntilMs", "uptimeMs",
                "context", "completeness", "scope", "entries");
        if (number(raw, "schemaVersion") != SCHEMA_VERSION) throw invalid("UNSUPPORTED_SCHEMA");
        choice(raw, "role", "BOARD", "FIRMWARE", "TARGET");
        for (String key : new String[]{"snapshotId", "baselineId", "baselineRevision", "firmwareId",
                "build", "collectorVersion"}) string(raw, key);
        long captured = number(raw, "capturedAtMs");
        if (number(raw, "validUntilMs") < captured) throw invalid("INVALID_TIME_RANGE");
        number(raw, "uptimeMs");
        context(object(raw, "context"));
        String completeness = choice(raw, "completeness", "COMPLETE", "PARTIAL");
        JSONArray scope = array(raw, "scope", MAX_SCOPES);
        if (scope.length() == 0) throw invalid("EMPTY_SCOPE");
        boolean complete = true;
        for (int i = 0; i < scope.length(); i++) {
            JSONObject s = item(scope, i);
            keys(s, "path", "state", "source", "reason");
            String path = path(s, "path");
            String state = choice(s, "state", "COMPLETE", "PARTIAL", "READ_FAILED", "NOT_CHECKED");
            string(s, "source");
            if (!state.equals("COMPLETE")) { complete = false; string(s, "reason"); }
            if (s.has("reason")) string(s, "reason");
            for (String previous : scopes.keySet()) {
                if (within(path, previous) || within(previous, path)) throw invalid("OVERLAPPING_SCOPE");
            }
            scopes.put(path, s);
        }
        if (completeness.equals("COMPLETE") && !complete) throw invalid("CONTRADICTORY_COMPLETENESS");
        JSONArray items = array(raw, "entries", MAX_ENTRIES);
        for (int i = 0; i < items.length(); i++) {
            JSONObject e = item(items, i);
            keys(e, "path", "presence", "fields");
            String path = path(e, "path");
            if (scopeFor(path) == null) throw invalid("PATH_OUTSIDE_SCOPE");
            if (entries.containsKey(path)) throw invalid("DUPLICATE_PATH");
            JSONObject presence = object(e, "presence");
            evidence(presence, true);
            JSONObject fields = object(e, "fields");
            if (fields.length() > 48) throw invalid("INPUT_LIMIT");
            if ((!"OBSERVED".equals(presence.optString("state"))
                    || !"PRESENT".equals(presence.optString("value"))) && fields.length() != 0) {
                throw invalid("FIELDS_WITHOUT_PRESENT_ENTRY");
            }
            Iterator<String> names = fields.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!field(name)) throw invalid("UNKNOWN_COMPARISON_FIELD");
                fieldValue(name, object(fields, name));
            }
            entries.put(path, e);
        }
        // 同一份稳定清单不能同时声明父路径缺失和子路径存在。
        for (Map.Entry<String, JSONObject> entry : entries.entrySet()) {
            if (!"PRESENT".equals(object(entry.getValue(), "presence").optString("value"))) continue;
            String parent = entry.getKey();
            while (parent.lastIndexOf('/') >= 0 && !parent.equals("/")) {
                int slash = parent.lastIndexOf('/');
                parent = slash == 0 ? "/" : parent.substring(0, slash);
                JSONObject ancestor = entries.get(parent);
                if (ancestor != null && "ABSENT".equals(object(ancestor, "presence").optString("value"))) {
                    throw invalid("CONTRADICTORY_PRESENCE");
                }
                if (ancestor != null) {
                    JSONObject type = object(ancestor, "fields").optJSONObject("type");
                    if (type != null && "OBSERVED".equals(type.optString("state"))
                            && ("file".equals(type.optString("value")) || "block".equals(type.optString("value"))
                            || "symlink".equals(type.optString("value")))) throw invalid("CONTRADICTORY_ANCESTOR_TYPE");
                }
            }
        }
    }

    public static DiagnosticManifest parse(JSONObject input) throws JSONException {
        return new DiagnosticManifest(input);
    }

    public JSONObject toJson() throws JSONException { return new JSONObject(raw.toString()); }

    JSONObject scopeFor(String path) {
        for (Map.Entry<String, JSONObject> scope : scopes.entrySet()) {
            if (within(path, scope.getKey())) return scope.getValue();
        }
        return null;
    }

    boolean inventoryComplete() {
        if (!"COMPLETE".equals(raw.optString("completeness"))) return false;
        for (JSONObject scope : scopes.values()) if (!"COMPLETE".equals(scope.optString("state"))) return false;
        // 显式未读到的路径/子目录优先于顶层COMPLETE，不能据此把省略项推导为缺失。
        for (JSONObject entry : entries.values()) {
            if (!"OBSERVED".equals(entry.optJSONObject("presence").optString("state"))) return false;
            JSONObject enumeration = entry.optJSONObject("fields").optJSONObject("semantic.enumeration");
            if (enumeration != null && !"OBSERVED".equals(enumeration.optString("state"))) return false;
        }
        return true;
    }

    JSONObject presence(String path) throws JSONException {
        JSONObject entry = entries.get(path);
        if (entry != null) return object(entry, "presence");
        JSONObject scope = scopeFor(path);
        if (scope != null && "COMPLETE".equals(scope.optString("state")) && inventoryComplete()) {
            return new JSONObject().put("state", "OBSERVED").put("value", "ABSENT")
                    .put("source", scope.getString("source")).put("reason", "ABSENT_FROM_COMPLETE_ENUMERATION");
        }
        return new JSONObject().put("state", scope != null && "READ_FAILED".equals(scope.optString("state"))
                ? "READ_FAILED" : "NOT_CHECKED").put("source", scope == null ? "OUTSIDE_SCOPE" : scope.getString("source"))
                .put("reason", "NO_COMPLETE_ENUMERATION");
    }

    JSONObject fieldEvidence(String path, String field) throws JSONException {
        JSONObject entry = entries.get(path);
        if (entry != null && object(entry, "fields").has(field)) return object(object(entry, "fields"), field);
        return new JSONObject().put("state", "NOT_CHECKED").put("source", raw.getString("snapshotId"))
                .put("reason", "MISSING_FIELD_EVIDENCE");
    }
}
