package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import net.elfradio.d31bootstrap.management.ContactsPageCommand;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** LOCAL生产任务适配；复用原任务及APP按需桥，不另建采集器、缓存或重试队列。 */
public final class RemoteContactsTask {
    public static final String TYPE = "contacts_page";
    public static final String CAPABILITY = "managed_contacts_page_v1", RESULT_KEY = "contacts_page";
    public static final int TIMEOUT_SECONDS = 20, MAX_RESULT_CHARS = 16000, MAX_RESULT_BYTES = 65536;
    private static final String UUID = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}";
    interface Operation { JSONObject execute(String[] arguments) throws Exception; }

    public static JSONObject validate(JSONObject params) throws Exception {
        require(params != null, "CONTACTS_TASK_PARAMS_INVALID");
        String action = string(params, "action");
        JSONObject value = new JSONObject().put("action", action);
        if ("open".equals(action)) {
            keys(params, "action", "source");
            require("LOCAL".equals(string(params, "source")), "CONTACTS_TASK_SOURCE_UNSUPPORTED");
            value.put("source", "LOCAL");
        }
        else if ("page".equals(action)) {
            keys(params, "action", "snapshot_id", "offset", "limit");
            value.put("snapshot_id", snapshotId(params)).put("offset", integer(params, "offset", 0, 4096))
                    .put("limit", integer(params, "limit", 1, 32));
        } else if ("close".equals(action)) {
            keys(params, "action", "snapshot_id"); value.put("snapshot_id", snapshotId(params));
        } else throw new IOException("CONTACTS_TASK_ACTION_UNSUPPORTED");
        return value;
    }

    public static JSONObject validateParams(JSONObject params) throws Exception { return validate(params); }

    /** localTaskId必须使用现有RemoteProtocol.localJobId结果，不接受用户生成的临时操作号。 */
    public static String command(String apk, String localTaskId, JSONObject params) throws Exception {
        identity(localTaskId, apkHash(apk));
        JSONObject request = validateParams(params);
        String action = request.getString("action"), hash = apkHash(apk);
        String command = "CLASSPATH='" + apk + "' /system/bin/app_process /system/bin "
                + "net.elfradio.d31bootstrap.RemoteContactsTask " + hash + " " + localTaskId + " " + action;
        if (!"open".equals(action)) command += " " + request.getString("snapshot_id");
        if ("page".equals(action)) command += " " + request.getInt("offset") + " " + request.getInt("limit");
        return command;
    }

    private static String apkHash(String apk) throws IOException {
        require(apk != null && apk.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk"),
                "CONTACTS_TASK_ACTIVE_APK_INVALID");
        return apk.split("/")[5];
    }

    private static String[] arguments(JSONObject params, String id, String hash) throws Exception {
        String action = params.getString("action");
        if ("open".equals(action)) return new String[] {action, hash, id};
        String snapshot = params.getString("snapshot_id");
        return "page".equals(action) ? new String[] {action, hash, snapshot,
                String.valueOf(params.getInt("offset")), String.valueOf(params.getInt("limit"))}
                : new String[] {action, hash, snapshot};
    }

    static JSONObject execute(String id, String hash, JSONObject params, Operation operation) throws Exception {
        identity(id, hash); JSONObject request = validateParams(params);
        JSONObject data;
        try {
            require(!Thread.currentThread().isInterrupted(), "CONTACTS_TASK_CANCELLED");
            data = adapt(request, id, hash, operation.execute(arguments(request, id, hash)));
        } catch (Exception failure) { data = failure(request, id, code(failure)); }
        JSONObject envelope = envelope(id, hash, request, data);
        if (!bounded(envelope.toString() + "\n"))
            envelope = envelope(id, hash, request, failure(request, id, "CONTACTS_TASK_RESULT_LIMIT"));
        return envelope;
    }

    /** 从已持久保存的原根任务outcome读取；不会执行、查询其他任务或打开新快照。 */
    public static JSONObject readResult(JSONObject params, String localTaskId, String apk, JSONObject outcome) throws Exception {
        JSONObject request = validateParams(params); String hash = apkHash(apk); identity(localTaskId, hash);
        require(outcome != null && localTaskId.equals(outcome.opt("id"))
                && "completed".equals(outcome.opt("state")) && Boolean.FALSE.equals(outcome.opt("truncated"))
                && outcome.opt("output") instanceof String, "CONTACTS_TASK_OUTCOME_INCOMPLETE");
        String text = outcome.getString("output");
        require(bounded(text), "CONTACTS_TASK_RESULT_LIMIT");
        JSONTokener parser = new JSONTokener(text);
        Object parsed = parser.nextValue();
        require(parsed instanceof JSONObject && parser.nextClean() == 0, "CONTACTS_TASK_REPLY_INVALID");
        JSONObject envelope = (JSONObject) parsed;
        keys(envelope, "schema_version", "local_task_id", "apk_sha256", "params", "data");
        require(integer(envelope, "schema_version", 1, 1) == 1
                && localTaskId.equals(envelope.opt("local_task_id")) && hash.equals(envelope.opt("apk_sha256"))
                && RemoteProtocol.sameJson(request, validateParams(envelope.getJSONObject("params"))), "CONTACTS_TASK_RESULT_MISMATCH");
        JSONObject data = envelope.getJSONObject("data"); validateData(request, localTaskId, data);
        int exit = integer(outcome, "exit_code", 0, 1);
        require(exit == (Boolean.TRUE.equals(data.opt("ok")) ? 0 : 1), "CONTACTS_TASK_EXIT_MISMATCH");
        return new JSONObject(data.toString());
    }

    public static JSONObject result(JSONObject params, String localTaskId, String apk, JSONObject outcome) throws Exception {
        return new JSONObject().put(RESULT_KEY, readResult(params, localTaskId, apk, outcome));
    }

    /** 公共拒绝、取消及缺失回执路径也返回同一包装；不从非法参数虚构快照绑定。 */
    public static JSONObject failureResult(JSONObject params, String errorCode) throws Exception {
        boolean valid = true;
        try { validate(params); } catch (Exception invalid) { valid = false; }
        Object requestedAction = params == null ? null : params.opt("action");
        String action = "open".equals(requestedAction) || "page".equals(requestedAction) || "close".equals(requestedAction)
                ? (String) requestedAction : "invalid";
        JSONObject data = new JSONObject().put("schema_version", 1).put("action", action).put("ok", false)
                .put("read_only", true).put("source", "nexui_messenger").put("contact_type", "LOCAL")
                .put("code", valid ? code(new IOException(errorCode)) : "CONTACTS_TASK_PARAMS_INVALID");
        if ("page".equals(action) || "close".equals(action)) {
            Object snapshot = params.opt("snapshot_id");
            if (snapshot instanceof String && ((String) snapshot).matches(UUID)) data.put("snapshot_id", snapshot);
        }
        return new JSONObject().put(RESULT_KEY, data);
    }

    private static JSONObject envelope(String id, String hash, JSONObject params, JSONObject data) throws Exception {
        return new JSONObject().put("schema_version", 1).put("local_task_id", id).put("apk_sha256", hash)
                .put("params", new JSONObject(params.toString())).put("data", data);
    }
    private static JSONObject base(JSONObject params, String id) throws Exception {
        JSONObject data = new JSONObject().put("schema_version", 1).put("action", params.getString("action"))
                .put("source", "nexui_messenger").put("contact_type", "LOCAL").put("read_only", true);
        if (!"open".equals(params.getString("action"))) data.put("snapshot_id", params.getString("snapshot_id"));
        return data;
    }
    private static JSONObject failure(JSONObject params, String id, String code) throws Exception {
        return base(params, id).put("ok", false).put("code", code);
    }
    private static String code(Exception error) {
        String code = error.getMessage();
        if (code != null && code.matches("APP_OPERATION_[A-Z0-9_]{1,80}")) return "CONTACTS_" + code;
        return code != null && code.matches("CONTACTS_[A-Z0-9_]{1,96}") ? code : "CONTACTS_TASK_FAILED";
    }

    private static JSONObject adapt(JSONObject params, String id, String hash, JSONObject raw) throws Exception {
        require(raw != null && raw.opt("ok") instanceof Boolean, "CONTACTS_TASK_REPLY_INVALID");
        if (!raw.getBoolean("ok")) return failure(params, id, code(new IOException(raw.optString("state"))));
        require(bounded(raw.toString()), "CONTACTS_TASK_RESULT_LIMIT");
        require(Boolean.TRUE.equals(raw.opt("readOnly")) && Boolean.TRUE.equals(raw.opt("maintenanceGatePassed"))
                && Boolean.TRUE.equals(raw.opt("activeApkHashMatched")) && hash.equals(raw.opt("expectedApkSha256"))
                && "LOCAL".equals(raw.opt("contact_type")) && "com.starnet.dial".equals(raw.opt("ownerPackage"))
                && Boolean.FALSE.equals(raw.opt("all_sources_complete")), "CONTACTS_TASK_REPLY_SCOPE");
        String action = params.getString("action");
        JSONObject data = base(params, id).put("ok", true);
        if ("open".equals(action)) {
            require("NEXUI_APP_LOCAL_METADATA".equals(raw.opt("kind")) && "read_local_pages".equals(raw.opt("operation"))
                    && id.equals(raw.opt("operation_id")) && Boolean.TRUE.equals(raw.opt("remoteOutcomeKnown"))
                    && Boolean.TRUE.equals(raw.opt("unbindConfirmed")) && Boolean.TRUE.equals(raw.opt("replyChannelClosed")),
                    "CONTACTS_TASK_OPEN_NOT_RELEASED");
            JSONObject op = raw.getJSONObject("app_operation");
            require(id.equals(op.opt("operation_id")) && "read-local-pages".equals(op.opt("operation"))
                    && hash.equals(op.opt("apk_sha256")) && "RELEASED".equals(op.opt("state"))
                    && Boolean.TRUE.equals(op.opt("reservation_released")) && "MATCHED_RELEASE_RECEIPT".equals(op.opt("release_reason"))
                    && Boolean.FALSE.equals(op.opt("managed_media")) && Boolean.FALSE.equals(op.opt("network_write"))
                    && string(op, "record_boot_id").matches(UUID) && op.getString("record_boot_id").equals(op.opt("current_boot_id"))
                    && string(op, "operation_request_id").matches(UUID) && op.getString("operation_request_id").equals(raw.opt("operation_request_id")),
                    "CONTACTS_TASK_OPEN_NOT_RELEASED");
            JSONObject snapshot = raw.getJSONObject("page_snapshot"); descriptor(snapshot);
            require(Boolean.FALSE.equals(snapshot.opt("contact_values_emitted")) && !snapshot.has("items")
                    && snapshot.getString("snapshot_id").equals(raw.getJSONObject("local").opt("snapshot_id")), "CONTACTS_TASK_SNAPSHOT_MISMATCH");
            data = new JSONObject(snapshot.toString()).put("schema_version", 1).put("action", action);
        } else {
            require("NEXUI_APP_LOCAL_PAGE".equals(raw.opt("kind")) && "nexui_messenger".equals(raw.opt("source"))
                    && Boolean.FALSE.equals(raw.opt("vendorRequestSent"))
                    && params.getString("snapshot_id").equals(raw.opt("snapshot_id")), "CONTACTS_TASK_SNAPSHOT_MISMATCH");
            if ("close".equals(action)) {
                require(Boolean.TRUE.equals(raw.opt("snapshot_closed")) && !raw.has("page"), "CONTACTS_TASK_CLOSE_UNCONFIRMED");
                data.put("snapshot_closed", true);
            } else {
                require(Boolean.FALSE.equals(raw.opt("snapshot_closed")), "CONTACTS_TASK_SNAPSHOT_MISMATCH");
                JSONObject page = raw.getJSONObject("page"); descriptor(page);
                require(params.getString("snapshot_id").equals(page.opt("snapshot_id")), "CONTACTS_TASK_SNAPSHOT_MISMATCH");
                int offset = params.getInt("offset"), limit = params.getInt("limit"), total = page.getInt("record_count");
                JSONArray items = page.getJSONArray("items");
                int next = integer(page, "next_offset", offset, total);
                require(integer(page, "offset", 0, total) == offset && items.length() <= limit && next == offset + items.length()
                        && Boolean.valueOf(next < total).equals(page.opt("has_more"))
                        && Boolean.valueOf(next == total).equals(page.opt("page_complete"))
                        && Boolean.valueOf(items.length() > 0).equals(page.opt("contact_values_emitted"))
                        && (next == total || items.length() > 0) && "SAME_SNAPSHOT_ONLY".equals(page.opt("cursor_scope")), "CONTACTS_TASK_CURSOR_INVALID");
                for (int i = 0; i < items.length(); i++) require(items.opt(i) instanceof JSONObject, "CONTACTS_TASK_ITEMS_INVALID");
                data = new JSONObject(page.toString()).put("schema_version", 1).put("action", action);
            }
        }
        validateData(params, id, data); return data;
    }

    private static void descriptor(JSONObject value) throws Exception {
        require(Boolean.TRUE.equals(value.opt("ok")) && Boolean.TRUE.equals(value.opt("read_only"))
                && "LOCAL".equals(value.opt("contact_type")) && "nexui_messenger".equals(value.opt("source"))
                && "com.starnet.dial".equals(value.opt("owner_package")) && "COMPLETED".equals(value.opt("status"))
                && Boolean.TRUE.equals(value.opt("list_complete")) && Boolean.TRUE.equals(value.opt("end_observed"))
                && "SELECTED_SOURCE_ALL_CONTACTS_REPLY".equals(value.opt("completion_scope"))
                && Boolean.FALSE.equals(value.opt("all_sources_complete"))
                && "NOT_PROVIDED_BY_VENDOR".equals(value.opt("snapshot_consistency"))
                && "IMMUTABLE_RECEIVED_REPLY".equals(value.opt("page_consistency"))
                && "APP_PROCESS_MEMORY".equals(value.opt("storage")) && Boolean.FALSE.equals(value.opt("cross_boot"))
                && Boolean.FALSE.equals(value.opt("cross_process_restart")), "CONTACTS_TASK_DESCRIPTOR_INVALID");
        snapshotId(value); integer(value, "record_count", 0, 4096);
        integer(value, "retention_ms", 120000, 120000); integer(value, "max_page_records", 32, 32);
        wholeNumber(value, "sampled_at_ms", 1, 8640000000000000L);
    }

    private static void validateData(JSONObject params, String id, JSONObject value) throws Exception {
        String action = params.getString("action");
        require(integer(value, "schema_version", 1, 1) == 1 && action.equals(value.opt("action"))
                && "nexui_messenger".equals(value.opt("source")) && "LOCAL".equals(value.opt("contact_type"))
                && Boolean.TRUE.equals(value.opt("read_only")) && value.opt("ok") instanceof Boolean, "CONTACTS_TASK_RESULT_MISMATCH");
        if (!"open".equals(action)) require(params.getString("snapshot_id").equals(value.opt("snapshot_id")), "CONTACTS_TASK_RESULT_MISMATCH");
        if (!value.getBoolean("ok")) {
            if ("open".equals(action)) keys(value, "schema_version", "action", "source", "contact_type", "read_only", "ok", "code");
            else keys(value, "schema_version", "action", "source", "contact_type", "read_only", "ok", "code", "snapshot_id");
            require(string(value, "code").matches("CONTACTS_[A-Z0-9_]{1,96}"), "CONTACTS_TASK_ERROR_INVALID");
        } else if ("open".equals(action)) {
            descriptor(value); snapshotFields(value, false);
            require(Boolean.FALSE.equals(value.opt("contact_values_emitted")), "CONTACTS_TASK_SNAPSHOT_MISMATCH");
        } else if ("close".equals(action)) {
            keys(value, "schema_version", "action", "source", "contact_type", "read_only", "ok", "snapshot_id", "snapshot_closed");
            require(Boolean.TRUE.equals(value.opt("snapshot_closed")), "CONTACTS_TASK_CLOSE_UNCONFIRMED");
        } else {
            descriptor(value); snapshotFields(value, true);
            int offset = integer(value, "offset", 0, 4096), total = integer(value, "record_count", offset, 4096);
            int limit = params.getInt("limit"), next = integer(value, "next_offset", offset, total);
            JSONArray items = value.getJSONArray("items");
            require(offset == params.getInt("offset") && items.length() <= limit
                    && next == offset + items.length() && Boolean.valueOf(next < total).equals(value.opt("has_more"))
                    && Boolean.valueOf(next == total).equals(value.opt("page_complete"))
                    && Boolean.valueOf(items.length() > 0).equals(value.opt("contact_values_emitted"))
                    && "SAME_SNAPSHOT_ONLY".equals(value.opt("cursor_scope"))
                    && (next == total || items.length() > 0), "CONTACTS_TASK_CURSOR_INVALID");
            for (int i = 0; i < items.length(); i++) {
                require(items.opt(i) instanceof JSONObject && items.getJSONObject(i).toString().length() <= 8192, "CONTACTS_TASK_ITEMS_INVALID");
                itemDepth(items.getJSONObject(i), 0);
            }
        }
    }

    private static void snapshotFields(JSONObject value, boolean page) throws Exception {
        Set<String> allowed = new HashSet<>(Arrays.asList("schema_version", "action", "ok", "read_only", "source",
                "contact_type", "owner_package", "snapshot_id", "status", "sampled_at_ms", "record_count",
                "end_observed", "list_complete", "contact_values_emitted", "completion_scope", "all_sources_complete",
                "snapshot_consistency", "retention_ms", "max_page_records", "page_consistency", "storage",
                "cross_process_restart", "cross_boot", "elapsed_ms", "frames_received", "received_chars", "start_observed",
                "max_records", "max_frames", "max_received_chars", "wait_budget_ms", "android_equivalence", "service_implementation"));
        if (page) allowed.addAll(Arrays.asList("items", "offset", "next_offset", "has_more", "page_complete", "cursor_scope"));
        Iterator<String> it = value.keys();
        while (it.hasNext()) require(allowed.contains(it.next()), "CONTACTS_TASK_FIELDS_INVALID");
        String[] fields = {"elapsed_ms", "frames_received", "received_chars", "max_records", "max_frames", "max_received_chars", "wait_budget_ms"};
        int[] limits = {120000, 128, 1048576, 4096, 128, 1048576, 120000};
        for (int i = 0; i < fields.length; i++) if (value.has(fields[i])) wholeNumber(value, fields[i], 0, limits[i]);
        if (value.has("start_observed")) require(value.opt("start_observed") instanceof Boolean, "CONTACTS_TASK_DESCRIPTOR_INVALID");
        if (value.has("android_equivalence")) require("NOT_VERIFIED".equals(value.opt("android_equivalence")), "CONTACTS_TASK_DESCRIPTOR_INVALID");
        if (value.has("service_implementation")) require("NOT_DECRYPTED".equals(value.opt("service_implementation")), "CONTACTS_TASK_DESCRIPTOR_INVALID");
    }

    private static void itemDepth(Object value, int depth) throws Exception {
        require(depth <= 16, "CONTACTS_TASK_ITEMS_INVALID");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) itemDepth(object.get(keys.next()), depth + 1);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) itemDepth(array.get(i), depth + 1);
        }
    }

    private static String snapshotId(JSONObject value) throws Exception {
        String id = string(value, "snapshot_id"); require(id.matches(UUID), "CONTACTS_TASK_SNAPSHOT_INVALID"); return id;
    }
    private static String string(JSONObject value, String key) throws Exception {
        Object item = value.opt(key); require(item instanceof String, "CONTACTS_TASK_PARAMS_INVALID"); return (String) item;
    }
    private static int integer(JSONObject value, String key, int min, int max) throws Exception {
        return (int) wholeNumber(value, key, min, max);
    }
    private static long wholeNumber(JSONObject value, String key, long min, long max) throws Exception {
        Object number = value.opt(key);
        require(number instanceof Integer || number instanceof Long, "CONTACTS_TASK_INTEGER_INVALID");
        long n = ((Number) number).longValue(); require(n >= min && n <= max, "CONTACTS_TASK_INTEGER_INVALID"); return n;
    }
    private static void keys(JSONObject value, String... allowed) throws Exception {
        Set<String> keys = new HashSet<>(Arrays.asList(allowed)); require(value.length() == keys.size(), "CONTACTS_TASK_FIELDS_INVALID");
        Iterator<String> it = value.keys(); while (it.hasNext()) require(keys.contains(it.next()), "CONTACTS_TASK_FIELDS_INVALID");
    }
    private static boolean bounded(String value) {
        return value.length() <= MAX_RESULT_CHARS && value.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES;
    }
    private static void identity(String id, String hash) throws IOException {
        require(id != null && id.matches("[a-f0-9]{64}") && hash != null && hash.matches("[a-f0-9]{64}"), "CONTACTS_TASK_IDENTITY_INVALID");
    }
    private static void require(boolean condition, String code) throws IOException { if (!condition) throw new IOException(code); }

    public static void main(String[] args) {
        try {
            require(args != null && args.length >= 3, "CONTACTS_TASK_PARAMS_INVALID");
            identity(args[1], args[0]);
            JSONObject params = new JSONObject().put("action", args[2]);
            if ("open".equals(args[2])) {
                require(args.length == 3, "CONTACTS_TASK_PARAMS_INVALID"); params.put("source", "LOCAL");
            }
            else if ("close".equals(args[2])) {
                require(args.length == 4, "CONTACTS_TASK_PARAMS_INVALID"); params.put("snapshot_id", args[3]);
            } else {
                require("page".equals(args[2]) && args.length == 6 && args[4].matches("0|[1-9][0-9]{0,3}")
                        && args[5].matches("[1-9][0-9]?"), "CONTACTS_TASK_PARAMS_INVALID");
                params.put("snapshot_id", args[3]).put("offset", Integer.parseInt(args[4])).put("limit", Integer.parseInt(args[5]));
            }
            JSONObject result = execute(args[1], args[0], params, ContactsPageCommand::execute);
            System.out.println(result.toString()); System.exit(result.getJSONObject("data").getBoolean("ok") ? 0 : 1);
        } catch (Exception failure) { System.err.println(code(failure)); System.exit(1); }
    }
    private RemoteContactsTask() { }
}
