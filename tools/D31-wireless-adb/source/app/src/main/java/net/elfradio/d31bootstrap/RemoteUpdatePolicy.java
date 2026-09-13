package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;

/** 沿用现有发布签名合同，D31制品与D22分别验证。 */
final class RemoteUpdatePolicy {
    static final String PACKAGE = "net.elfradio.d31bootstrap";
    static final String CERT = "9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e";
    static final long MAX_BYTES = 64L * 1024 * 1024;

    static PublicKey trustedKey() throws Exception {
        try (InputStream in = RemoteUpdatePolicy.class.getResourceAsStream("/update-public.pem")) {
            if (in == null) throw new IOException("发布公钥缺失");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            String pem = new String(out.toByteArray(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    android.util.Base64.decode(pem, android.util.Base64.DEFAULT)));
        }
    }

    static JSONObject validate(JSONObject offer, PublicKey key, String device, long now, boolean resuming) throws Exception {
        String raw = offer.getString("manifest_raw"), hex = offer.getString("signature");
        if (raw.length() > 16000 || !hex.matches("[0-9a-fA-F]{512}")) throw new IOException("签名清单格式无效");
        byte[] signature = new byte[hex.length() / 2];
        for (int i = 0; i < signature.length; i++) signature[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(key);
        verifier.update(raw.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(signature)) throw new IOException("发布清单签名不符");
        JSONObject m = new JSONObject(raw);
        if (!PACKAGE.equals(m.optString("package")) || !"d31".equals(m.optString("channel"))
                || !"mdl_d31".equals(m.optString("model_id")) || !CERT.equals(m.optString("certSha256")))
            throw new IOException("不是原签名D31制品");
        long code = integer(m, "versionCode"), size = integer(m, "size"), expires = integer(m, "expires_at");
        if (code <= 0 || code > Integer.MAX_VALUE || size <= 0 || size > MAX_BYTES
                || m.optString("versionName").isEmpty() || m.getString("versionName").length() > 128
                || !m.optString("sha256").matches("[0-9a-f]{64}")) throw new IOException("制品元数据无效");
        String release = m.getString("job_id");
        if (!release.matches("[A-Za-z0-9_-]{1,96}")) throw new IOException("发布编号无效");
        URI url = new URI(m.getString("url"));
        if (!"https".equals(url.getScheme()) || !"v.elfradio.net".equals(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null
                || url.getQuery() != null || url.getFragment() != null
                || !("/api/elfremote/apk/" + release).equals(url.getRawPath())) throw new IOException("制品下载地址无效");
        if (m.has("device_id") && !device.equals(m.getString("device_id"))) throw new IOException("制品设备不匹配");
        String id = offer.optString("task_id", release);
        if (!id.matches("[A-Za-z0-9_-]{1,96}")) throw new IOException("更新任务编号无效");
        if (offer.has("task_id")) {
            if (!id.startsWith("update-") || !device.equals(offer.optString("task_device_id")))
                throw new IOException("更新任务设备不匹配");
            long taskExpiry = integer(offer, "task_expires_at");
            if (!resuming && taskExpiry <= now) throw new IOException("更新任务过期");
        }
        if (!resuming && expires <= now) throw new IOException("发布清单过期");
        return m.put("task_id", id);
    }

    private static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Number)) throw new IOException("清单数值类型无效");
        Number number = (Number) value;
        if (number.doubleValue() != (double) number.longValue()) throw new IOException("清单数值非整数");
        return number.longValue();
    }

    static boolean matches(JSONObject manifest, JSONObject archive) {
        return PACKAGE.equals(archive.optString("package")) && CERT.equals(archive.optString("certSha256"))
                && manifest.optInt("versionCode", -1) == archive.optInt("versionCode", -2)
                && manifest.optString("versionName").equals(archive.optString("versionName"))
                && manifest.optLong("size", -1) == archive.optLong("size", -2)
                && manifest.optString("sha256").equals(archive.optString("sha256"));
    }

    static boolean hasSpace(long free, long target, long backup) {
        return free >= 0 && target > 0 && target <= MAX_BYTES && backup > 0 && backup <= MAX_BYTES
                && free >= target * 3 + backup + 4L * 1024 * 1024;
    }
}
