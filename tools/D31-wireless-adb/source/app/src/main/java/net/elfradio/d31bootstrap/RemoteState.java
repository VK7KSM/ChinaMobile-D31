package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

final class RemoteState {
    private final File file;
    private JSONObject state;

    RemoteState(File directory) throws Exception {
        file = new File(directory, "identity.json");
        if (file.exists()) {
            state = new JSONObject(RescueFiles.read(file, 32000));
            if (!state.optString("token").matches("[a-f0-9]{64}")) throw new IOException("原凭据损坏，保留现场");
        } else {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            StringBuilder token = new StringBuilder();
            for (byte b : bytes) token.append(String.format(Locale.US, "%02x", b & 255));
            state = new JSONObject().put("token", token.toString());
            RescueFiles.write(file, state.toString());
        }
    }

    synchronized JSONObject snapshot() throws Exception { return new JSONObject(state.toString()); }
    synchronized void put(String key, Object value) throws Exception {
        JSONObject next = snapshot(); next.put(key, value);
        RescueFiles.write(file, next.toString()); state = next;
    }

    synchronized void notice(JSONObject notice, long now) throws Exception {
        if (notice == null || !"status_request".equals(notice.optString("type"))
                || !notice.optString("request_id").matches("[A-Za-z0-9_-]{1,128}")
                || notice.optLong("expires_at_ms") <= now
                || notice.optLong("version") <= state.optLong("ack_version")) return;
        JSONObject old = state.optJSONObject("notice");
        if (old == null || old.optLong("version") < notice.optLong("version")) put("notice", notice);
    }

    synchronized void acknowledge(JSONObject report) throws Exception {
        long version = report.optLong("_notice_version");
        if (version <= state.optLong("ack_version")) return;
        JSONObject next = snapshot(); next.put("ack_version", version);
        JSONObject notice = next.optJSONObject("notice");
        if (notice != null && notice.optLong("version") <= version) next.remove("notice");
        RescueFiles.write(file, next.toString()); state = next;
    }
}
