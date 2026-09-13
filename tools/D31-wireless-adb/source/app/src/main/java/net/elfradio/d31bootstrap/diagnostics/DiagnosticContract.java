package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** 第1版纯离线合同的公共验证，不依赖平台或输入输出操作。 */
public final class DiagnosticContract {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ENTRIES = 4096;
    public static final int MAX_SCOPES = 32;
    public static final int MAX_RULES = 2048;
    public static final int MAX_STRING = 4096;
    public static final int MAX_NODES = 600000;
    public static final int MAX_TEXT_CHARS = 8 * 1024 * 1024;
    static final String[] REQUIRED_FIELDS = {"type", "sha256", "mode", "uid", "gid",
            "link", "selinux", "xattrs", "activeSource", "mountSource", "activation"};
    static final String[] CONTEXT_FIELDS = {"model", "hardwareClass", "firmwareFamily",
            "stage", "network", "sim", "storage"};
    private static final Set<String> FIELDS = new HashSet<String>(Arrays.asList(REQUIRED_FIELDS));

    private DiagnosticContract() { }

    public static final class Invalid extends IllegalArgumentException {
        public final String code;
        Invalid(String code) { super(code); this.code = code; }

        /** 拒绝比较时返回可保存的结构化结果，未知项目总数不以零代替。 */
        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("schemaVersion", SCHEMA_VERSION).put("offlineComparison", "NOT_COMPARED")
                    .put("systemConsistency", "NOT_ASSESSED").put("code", code)
                    .put("comparisonExtent", "SINGLE_BOUNDED_BATCH").put("aggregationStatus", "NOT_IMPLEMENTED")
                    .put("requiresPartitionAggregation", code.equals("INPUT_LIMIT") || code.equals("COMPARISON_LIMIT"))
                    .put("counts", new JSONObject().put("total", JSONObject.NULL).put("verified", 0)
                            .put("unchecked", JSONObject.NULL).put("failed", JSONObject.NULL)
                            .put("abnormal", JSONObject.NULL).put("allowed", JSONObject.NULL))
                    .put("limits", limits());
        }
    }

    static JSONObject limits() throws JSONException {
        return new JSONObject().put("maxEntriesPerManifestAndUnion", MAX_ENTRIES).put("maxScopes", MAX_SCOPES)
                .put("maxRules", MAX_RULES).put("maxStringChars", MAX_STRING).put("maxNodes", MAX_NODES)
                .put("maxTextChars", MAX_TEXT_CHARS).put("maxDepth", 12).put("maxFieldsPerEntryAndUnion", 48);
    }

    static Invalid invalid(String code) { return new Invalid(code); }

    // 先限制对象图，再序列化，避免循环引用、超长字符串和深层容器进入复制与比较。
    static JSONObject copy(JSONObject input) throws JSONException {
        if (input == null) throw invalid("NULL_INPUT");
        budget(input, 0, new long[2]);
        return new JSONObject(input.toString());
    }

    private static void budget(Object value, int depth, long[] used) throws JSONException {
        if (depth > 12 || ++used[0] > MAX_NODES) throw invalid("INPUT_LIMIT");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                budget(key, depth + 1, used);
                budget(object.get(key), depth + 1, used);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > MAX_ENTRIES) throw invalid("INPUT_LIMIT");
            for (int i = 0; i < array.length(); i++) budget(array.get(i), depth + 1, used);
        } else if (value instanceof String) {
            String s = (String) value;
            used[1] += s.length();
            if (s.length() > MAX_STRING || used[1] > MAX_TEXT_CHARS) throw invalid("INPUT_LIMIT");
            for (int i = 0; i < s.length(); i++) {
                if (Character.isISOControl(s.charAt(i))) throw invalid("CONTROL_CHARACTER");
            }
        } else if (value != JSONObject.NULL && !(value instanceof Boolean)
                && !(value instanceof Integer) && !(value instanceof Long)
                && !(value instanceof Short) && !(value instanceof Byte)) {
            throw invalid("UNSUPPORTED_VALUE");
        }
    }

    static void keys(JSONObject o, String... allowed) {
        Set<String> names = new HashSet<String>(Arrays.asList(allowed));
        Iterator<String> iterator = o.keys();
        while (iterator.hasNext()) if (!names.contains(iterator.next())) throw invalid("UNKNOWN_FIELD");
    }

    static JSONObject object(JSONObject o, String key) {
        Object value = o.opt(key);
        if (!(value instanceof JSONObject)) throw invalid("OBJECT_REQUIRED");
        return (JSONObject) value;
    }

    static JSONArray array(JSONObject o, String key, int max) {
        Object value = o.opt(key);
        if (!(value instanceof JSONArray)) throw invalid("ARRAY_REQUIRED");
        JSONArray array = (JSONArray) value;
        if (array.length() > max) throw invalid("INPUT_LIMIT");
        return array;
    }

    static JSONObject item(JSONArray array, int index) {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) throw invalid("OBJECT_REQUIRED");
        return (JSONObject) value;
    }

    static String string(JSONObject o, String key) {
        Object value = o.opt(key);
        if (!(value instanceof String) || ((String) value).trim().length() == 0) {
            throw invalid("STRING_REQUIRED");
        }
        return (String) value;
    }

    static long number(JSONObject o, String key) {
        Object value = o.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid("INTEGER_REQUIRED");
        long n = ((Number) value).longValue();
        if (n < 0) throw invalid("NEGATIVE_NUMBER");
        return n;
    }

    static String choice(JSONObject o, String key, String... values) {
        String value = string(o, key);
        if (!Arrays.asList(values).contains(value)) throw invalid("INVALID_ENUM");
        return value;
    }

    static String path(JSONObject o, String key) {
        String path = string(o, key);
        if (!path.startsWith("/") || path.contains("//") || path.contains("\\")
                || (path.length() > 1 && path.endsWith("/"))) throw invalid("INVALID_PATH");
        for (String part : path.split("/")) {
            if (part.equals(".") || part.equals("..")) throw invalid("INVALID_PATH");
        }
        return path;
    }

    static boolean within(String path, String root) {
        return root.equals("/") || path.equals(root) || path.startsWith(root + "/");
    }

    static boolean field(String name) {
        return FIELDS.contains(name) || name.matches("semantic\\.[A-Za-z][A-Za-z0-9_.-]{0,63}");
    }

    static void context(JSONObject context) {
        keys(context, CONTEXT_FIELDS);
        for (String field : CONTEXT_FIELDS) string(context, field);
    }

    static void evidence(JSONObject e, boolean presence) {
        keys(e, "state", "value", "source", "reason");
        String state = choice(e, "state", "OBSERVED", "NOT_APPLICABLE", "READ_FAILED",
                "NOT_CHECKED", "UNSTABLE", "REDACTED");
        string(e, "source");
        if (state.equals("OBSERVED")) {
            if (!e.has("value") || e.isNull("value")) throw invalid("VALUE_REQUIRED");
            if (presence) choice(e, "value", "PRESENT", "ABSENT");
        } else {
            string(e, "reason");
            if (e.has("value")) throw invalid("VALUE_WITHOUT_OBSERVATION");
            if (presence && state.equals("NOT_APPLICABLE")) throw invalid("INVALID_PRESENCE");
        }
        if (e.has("reason")) string(e, "reason");
    }

    static void fieldValue(String field, JSONObject evidence) {
        evidence(evidence, false);
        if (!"OBSERVED".equals(evidence.optString("state"))) return;
        Object value = evidence.opt("value");
        if (field.equals("xattrs")) {
            if (!(value instanceof JSONObject)) throw invalid("INVALID_XATTRS");
            JSONObject attrs = (JSONObject) value;
            if (attrs.length() > 32) throw invalid("INPUT_LIMIT");
            Iterator<String> names = attrs.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (name.trim().length() == 0 || !(attrs.opt(name) instanceof String)) {
                    throw invalid("INVALID_XATTRS");
                }
            }
        } else if (field.equals("uid") || field.equals("gid")) {
            if (number(evidence, "value") > 4294967295L) throw invalid("INVALID_OWNER");
        } else if (field.startsWith("semantic.")) {
            if (!(value instanceof String) && !(value instanceof Boolean)
                    && !(value instanceof Integer) && !(value instanceof Long)) throw invalid("INVALID_SEMANTIC_VALUE");
        } else {
            String s = string(evidence, "value");
            if (field.equals("type")) choice(evidence, "value", "file", "directory", "symlink", "block", "semantic");
            if (field.equals("sha256") && !s.matches("[0-9a-f]{64}")) throw invalid("INVALID_SHA256");
            if (field.equals("mode") && !s.matches("[0-7]{4}")) throw invalid("INVALID_MODE");
        }
    }

    static boolean equal(Object a, Object b) throws JSONException {
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject left = (JSONObject) a, right = (JSONObject) b;
            if (left.length() != right.length()) return false;
            Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !equal(left.get(key), right.get(key))) return false;
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) return ((Number) a).longValue() == ((Number) b).longValue();
        return a != null && a.equals(b);
    }
}
