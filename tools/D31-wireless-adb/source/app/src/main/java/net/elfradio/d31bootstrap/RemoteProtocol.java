package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class RemoteProtocol {
    static final String BASE = "https://v.elfradio.net";
    private RemoteProtocol() { }

    static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }

    static JSONObject ethernetIdentity(String mac, String assignment) throws Exception {
        String value = mac.trim().toLowerCase(Locale.US);
        if (!"0".equals(assignment.trim()) || !value.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
                || (Integer.parseInt(value.substring(0, 2), 16) & 3) != 0
                || "00:00:00:00:00:00".equals(value)) throw new IOException("有线出厂地址不可确认");
        return new JSONObject().put("variant", "d31").put("kind", "ethernet_factory_mac")
                .put("source", "sysfs_eth0_permanent").put("value", value);
    }

    static String utc(long time) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(time));
    }

    static String localJobId(String deviceId, String cloudId) throws Exception {
        if (!cloudId.matches("[A-Za-z0-9_-]{1,96}")) throw new IOException("任务号无效");
        return hash(deviceId + ":" + cloudId);
    }

    static JSONObject commandRequest(String deviceId, JSONObject task, long now) throws Exception {
        return commandRequest(deviceId, task, now, System.getenv("CLASSPATH"));
    }

    static JSONObject commandRequest(String deviceId, JSONObject task, long now, String apk) throws Exception {
        String id = localJobId(deviceId, task.getString("id"));
        if (task.optBoolean("cancel_requested")) throw new IOException("任务已取消，未执行");
        if (task.optLong("expires_at", 0) <= now) throw new IOException("任务已过期，未执行");
        JSONObject params = task.getJSONObject("params");
        if("configure_sip".equals(task.optString("type"))){
            return new JSONObject().put("id",id).put("command",RemoteSip.command(apk,id,task.getString("id"),params)).put("timeout",45);
        }
        if("restart_adbd".equals(task.optString("type"))) {
            return new JSONObject().put("id",id).put("command",RemoteAdbMaintenance.restartCommand()).put("timeout",10);
        }
        if ("file_manage".equals(task.optString("type"))) {
            String command = RemoteFileCommand.command(apk, id, params);
            RescueJobs.validate(id, command, 120);
            return new JSONObject().put("id", id).put("command", command).put("timeout",120);
        }
        if (!"root_exec".equals(task.optString("type"))) throw new IOException("本批不支持此任务类型");
        String cwd = params.optString("cwd", "/");
        if (!cwd.startsWith("/") || cwd.length() > 1024 || cwd.indexOf('\0') >= 0)
            throw new IOException("工作目录无效");
        String command = params.getString("command");
        if (command.trim().isEmpty()) throw new IOException("命令为空");
        command = "cd " + RescueFiles.quote(cwd) + " || exit 125\n" + command;
        int timeout = params.optInt("timeout", 30);
        RescueJobs.validate(id, command, timeout);
        return new JSONObject().put("id", id).put("command", command).put("timeout", timeout);
    }

    static void validateConnection(JSONObject config) throws Exception {
        String username = config.optString("username");
        if (!config.optBoolean("tls") || !config.optString("host").matches("[a-zA-Z0-9.-]{1,253}")
                || config.optInt("port") < 1 || config.optInt("port") > 65535
                || !username.matches("d_[a-f0-9]{64}") || !username.equals(config.optString("client_id"))
                || !("elfremote/" + username + "/notify").equals(config.optString("topic"))
                || config.optString("password").length() < 16 || config.optString("password").length() > 512)
            throw new IOException("推送配置无效");
    }
}
