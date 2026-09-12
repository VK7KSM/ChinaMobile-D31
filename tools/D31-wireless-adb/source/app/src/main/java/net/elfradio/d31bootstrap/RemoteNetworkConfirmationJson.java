package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.management.NetworkConfirmationDispatch;
import net.elfradio.d31bootstrap.management.NetworkRecoveryDispatch;

/** 冻结v1合同；使用org.json结构API，另加词法门统一Android/JVM的严格性。 */
final class RemoteNetworkConfirmationJson {
    private static final long SAFE_INTEGER = 9007199254740991L;
    private static final String UUID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private RemoteNetworkConfirmationJson() { }

    static JSONObject encode(String device, String token, String cloud, String digest,
                             NetworkConfirmationDispatch.Request request) throws Exception {
        NetworkRecoveryDispatch.Binding b = request.original;
        if (!RemoteProtocol.localJobId(device, cloud).equals(b.taskId)
                || b.apkSha256 == null || !b.apkSha256.matches("[a-f0-9]{64}")
                || b.bootId == null || !b.bootId.matches(UUID) || request.nonce == null || !request.nonce.matches(UUID)
                || b.startedElapsed < 0 || b.startedElapsed > SAFE_INTEGER
                || b.deadlineElapsed <= b.startedElapsed || b.deadlineElapsed > SAFE_INTEGER
                || b.deadlineElapsed - b.startedElapsed < 10000 || b.deadlineElapsed - b.startedElapsed > 120000
                || b.lastElapsed < b.startedElapsed || request.issuedElapsed < b.lastElapsed
                || request.issuedElapsed >= b.deadlineElapsed || b.before == b.target) throw invalid();
        JSONObject binding = new JSONObject().put("version", 1).put("request_digest", digest)
                .put("task_id", b.taskId).put("apk_sha256", b.apkSha256).put("key", "wifi_enabled")
                .put("before", b.before).put("target", b.target).put("boot_id", b.bootId)
                .put("started_elapsed", b.startedElapsed).put("deadline_elapsed", b.deadlineElapsed)
                .put("window_ms", b.deadlineElapsed - b.startedElapsed).put("last_elapsed", b.lastElapsed)
                .put("issued_elapsed", request.issuedElapsed).put("nonce", request.nonce);
        return new JSONObject().put("device_id", device).put("token", token).put("task_id", cloud)
                .put("state", "running").put("action", "network-confirmation").put("network_confirmation", binding);
    }

    static NetworkConfirmationDispatch.Receipt decode(String text, String device, String cloud, String digest,
                                                       NetworkConfirmationDispatch.Request request) throws Exception {
        if (text == null || text.length() > RemoteNetworkConfirmationSource.MAX_BYTES) throw invalid();
        new Grammar(text).validate();
        JSONObject root = new JSONObject(text);
        keys(root, "ok", "device_id", "task_id", "request_digest", "network_confirmation");
        equal(root, "ok", true); equal(root, "device_id", device); equal(root, "task_id", cloud);
        equal(root, "request_digest", digest);
        Object value = root.get("network_confirmation");
        if (!(value instanceof JSONObject)) throw invalid();
        JSONObject r = (JSONObject) value;
        keys(r, "version", "decision", "task_id", "apk_sha256", "key", "before", "target", "boot_id",
                "started_elapsed", "deadline_elapsed", "window_ms", "issued_elapsed", "nonce");
        NetworkRecoveryDispatch.Binding b = request.original;
        integer(r, "version", 1); equal(r, "decision", "allow");
        equal(r, "task_id", b.taskId); equal(r, "apk_sha256", b.apkSha256); equal(r, "key", "wifi_enabled");
        equal(r, "before", b.before); equal(r, "target", b.target); equal(r, "boot_id", b.bootId);
        integer(r, "started_elapsed", b.startedElapsed); integer(r, "deadline_elapsed", b.deadlineElapsed);
        integer(r, "window_ms", b.deadlineElapsed - b.startedElapsed); integer(r, "issued_elapsed", request.issuedElapsed);
        equal(r, "nonce", request.nonce);
        return new NetworkConfirmationDispatch.Receipt(b.taskId, b.apkSha256, b.bootId, b.target,
                b.startedElapsed, b.deadlineElapsed, request.nonce, request.issuedElapsed);
    }

    static String string(JSONObject object, String key, String pattern) throws IOException {
        Object value = object == null ? null : object.opt(key);
        if (!(value instanceof String) || !((String) value).matches(pattern)) throw invalid();
        return (String) value;
    }
    private static void keys(JSONObject object, String... names) throws IOException {
        if (object.length() != names.length) throw invalid();
        for (String name : names) if (!object.has(name)) throw invalid();
    }
    private static void equal(JSONObject object, String name, Object expected) throws IOException {
        if (!expected.equals(object.opt(name))) throw invalid();
    }
    private static void integer(JSONObject object, String name, long expected) throws IOException {
        Object value = object.opt(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        long n = ((Number) value).longValue();
        if (n < 0 || n > SAFE_INTEGER || n != expected) throw invalid();
    }
    private static IOException invalid() { return RemoteNetworkConfirmationSource.failure("CONTRACT"); }

    // 本合同只含对象、字符串、布尔和非负整数。拒绝其余类型、重复键和宽松JSON扩展。
    private static final class Grammar {
        private final String text;
        private int at;
        Grammar(String text) { this.text = text; }
        void validate() throws Exception { object(0); space(); if (at != text.length()) throw invalid(); }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        void need(char ch) throws IOException { space(); if (at >= text.length() || text.charAt(at++) != ch) throw invalid(); }
        void object(int depth) throws Exception {
            if (depth > 2) throw invalid();
            need('{'); space();
            Set<String> keys = new HashSet<>();
            if (at < text.length() && text.charAt(at) == '}') { at++; return; }
            while (true) {
                String key = string(); if (!keys.add(key)) throw invalid();
                need(':'); space();
                if (at >= text.length()) throw invalid();
                char c = text.charAt(at);
                if (c == '{') object(depth + 1);
                else if (c == '"') string();
                else if (text.startsWith("true", at)) at += 4;
                else if (text.startsWith("false", at)) at += 5;
                else number();
                space();
                if (at < text.length() && text.charAt(at) == '}') { at++; return; }
                need(',');
            }
        }
        String string() throws Exception {
            space(); int start = at; need('"'); boolean ended = false;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') { ended = true; break; }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (at >= text.length()) throw invalid();
                    char escape = text.charAt(at++);
                    if (escape == 'u') {
                        for (int n = 0; n < 4; n++) {
                            if (at >= text.length() || "0123456789abcdefABCDEF".indexOf(text.charAt(at++)) < 0) throw invalid();
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw invalid();
                }
            }
            if (!ended) throw invalid();
            String decoded = new JSONObject("{\"s\":" + text.substring(start, at) + "}").getString("s");
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(i))) throw invalid();
                } else if (Character.isLowSurrogate(c)) throw invalid();
            }
            return decoded;
        }
        void number() throws IOException {
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (at == start || at - start > 16 || (at - start > 1 && text.charAt(start) == '0')) throw invalid();
            try { if (Long.parseLong(text.substring(start, at)) > SAFE_INTEGER) throw invalid(); }
            catch (NumberFormatException invalid) { throw invalid(); }
        }
    }
}
