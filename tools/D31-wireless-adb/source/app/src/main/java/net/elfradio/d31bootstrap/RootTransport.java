package net.elfradio.d31bootstrap;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class RootTransport {
    static final class Unavailable extends IOException {
        Unavailable(Exception cause) { super(cause); }
    }
    interface Route { Result run(String command, int timeoutMillis) throws Exception; }
    static final class Result {
        final int exit;
        final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
    }
    // 只有明确尚未提交的连接失败才允许切换通道。
    static Result choose(String command, int timeoutMillis, Route... routes) throws Exception {
        Unavailable last = null;
        for (Route route : routes) {
            try { return route.run(command, timeoutMillis); }
            catch (Unavailable error) { last = error; }
        }
        throw last == null ? new IOException("没有可用命令通道") : last;
    }
    static Result execute(String command, int timeoutMillis) throws Exception {
        return choose(command, timeoutMillis, RootTransport::http, RootTransport::local);
    }
    private static Result http(String command, int timeoutMillis) throws Exception {
        try {
            JSONObject health = request("GET", "/health", null, 1000);
            if (!"d31-root-rescue".equals(health.optString("service")) || health.optInt("uid", -1) != 0)
                throw new IOException("不是root急救服务");
        } catch (Exception error) { throw new Unavailable(error); }
        String id = "apk-" + UUID.randomUUID();
        int seconds = Math.min(120, Math.max(1, (timeoutMillis + 999) / 1000));
        String body = new JSONObject().put("id", id).put("command", command).put("timeout", seconds).toString();
        long deadline = System.nanoTime() + (seconds * 1000L + 4000) * 1000000L;
        JSONObject state = null;
        try { state = request("POST", "/exec", body, 1500); }
        catch (Unavailable notSent) { throw notSent; }
        catch (Exception uncertain) { /* 回执丢失后只查询，不重发。 */ }
        while (System.nanoTime() < deadline) {
            if (state != null) {
                String status = state.optString("state");
                if ("completed".equals(status))
                    return new Result(state.optInt("exit_code", -1), state.optString("output"));
                if ("timed_out".equals(status) || "failed".equals(status) || "interrupted".equals(status))
                    return new Result(-1, state.toString());
                if (state.has("http_error") && state.optInt("http_error") != 404)
                    return new Result(-1, "命令服务拒绝请求：" + state);
            }
            Thread.sleep(150);
            try { state = request("GET", "/jobs/" + id, null, 1000); }
            catch (IOException error) { state = null; }
        }
        return new Result(-1, "任务结果未确认，不自动重复执行。任务号：" + id);
    }
    static JSONObject request(String method, String path, String body, int timeout) throws Exception {
        try (Socket socket = new Socket()) {
            try { socket.connect(new InetSocketAddress("127.0.0.1", 8765), timeout); }
            catch (IOException error) { throw new Unavailable(error); }
            socket.setSoTimeout(timeout);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String headers = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:8765\r\n"
                    + "Connection: close\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = socket.getInputStream().read(buffer)) != -1) {
                if (response.size() + n > 610000) throw new IOException("命令响应过大");
                response.write(buffer, 0, n);
            }
            String text = response.toString("UTF-8");
            int split = text.indexOf("\r\n\r\n");
            if (split < 0) throw new IOException("命令响应不完整");
            int code = Integer.parseInt(text.substring(0, text.indexOf("\r\n")).split(" ")[1]);
            if (code != 200 && code != 202)
                return new JSONObject().put("http_error", code).put("message", text.substring(split + 4));
            return new JSONObject(text.substring(split + 4));
        }
    }
    private static Result local(String command, int timeoutMillis) throws Exception {
        LocalSocket socket = new LocalSocket();
        try {
            try { socket.connect(new LocalSocketAddress("/dev/socket/d31-system-actions", LocalSocketAddress.Namespace.FILESYSTEM)); }
            catch (IOException error) { throw new Unavailable(error); }
            int seconds = Math.min(120, Math.max(1, (timeoutMillis + 999) / 1000));
            socket.setSoTimeout((seconds + 3) * 1000);
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 32768) throw new IOException("命令长度不合法");
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(bytes.length | 0x80000000); out.writeInt(seconds);
            out.write(bytes); out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int exit = in.readInt(), count = in.readInt();
            if (count < 0 || count > 65536) throw new IOException("本机命令响应长度不合法");
            byte[] result = new byte[count]; in.readFully(result);
            return new Result(exit, new String(result, StandardCharsets.UTF_8));
        } finally { socket.close(); }
    }
}
