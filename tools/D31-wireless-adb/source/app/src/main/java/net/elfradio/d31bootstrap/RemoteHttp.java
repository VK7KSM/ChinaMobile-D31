package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class RemoteHttp {
    static final class Rejected extends IOException {
        final int status;
        final String reason;
        final long retryAfterMillis;
        Rejected(int status, String reason) {
            this(status, reason, 0);
        }
        Rejected(int status, String reason, long retryAfterMillis) {
            super("HTTP " + status);
            this.status = status;
            this.reason = reason;
            this.retryAfterMillis = Math.max(0, Math.min(86400000L, retryAfterMillis));
        }
    }

    static JSONObject cloud(String path, JSONObject body) throws Exception {
        if (!path.startsWith("/api/")) throw new IOException("接口路径无效");
        JSONObject reply = request(RemoteProtocol.BASE + path, body, false);
        if (!reply.optBoolean("ok")) throw new IOException("服务端未确认请求");
        return reply;
    }

    static JSONObject local(String path, JSONObject body) throws Exception {
        if (!path.equals("/health") && !path.equals("/exec") && !path.matches("/jobs/[a-f0-9]{64}"))
            throw new IOException("本地接口路径无效");
        return request("http://127.0.0.1:8765" + path, body, true);
    }

    private static JSONObject request(String url, JSONObject body, boolean local) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        return request(c, body, local);
    }

    static JSONObject request(HttpURLConnection c, JSONObject body, boolean local) throws Exception {
        try {
            c.setConnectTimeout(local ? 3000 : 15000);
            c.setReadTimeout(local ? 5000 : 20000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            if (local && status == 404) return null;
            if (status < 200 || status >= 300) {
                String reason = "";
                long delay = retryAfterDelay(c.getHeaderField("Retry-After"), System.currentTimeMillis());
                try { reason = new JSONObject(read(c.getErrorStream())).optString("msg"); }
                catch (Exception ignored) { }
                throw new Rejected(status, reason, delay);
            }
            return new JSONObject(read(c.getInputStream()));
        } finally { c.disconnect(); }
    }

    static long retryAfterDelay(String value, long now) {
        if (value == null) return 0;
        value = value.trim();
        if (value.matches("[0-9]+")) {
            // 超长十进制也按最大等待处理，不让数值溢出变成立即重试。
            if (value.length() > 9) return 86400000L;
            return Math.min(86400L, Long.parseLong(value)) * 1000L;
        }
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT")); format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(value, position);
        if (date == null || position.getIndex() != value.length() || date.getTime() <= now) return 0;
        return Math.min(86400000L, date.getTime() - now);
    }

    private static String read(InputStream source) throws Exception {
        if (source == null) return "";
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 600000) throw new IOException("接口响应超限");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
