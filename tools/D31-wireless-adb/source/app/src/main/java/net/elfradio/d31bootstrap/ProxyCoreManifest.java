package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import org.json.JSONObject;

/**
 * 按需下载的代理核心：`configure_proxy` 任务参数里的 `core` 段，只有 `manifest_raw` 与 `signature` 两项。
 *
 * 这个文件以 root 执行。服务端回的 sha256 挡不住服务端本身被改，所以清单必须由离线私钥签名、
 * 设备用内置公钥验签——与 APK 更新清单同一把钥匙、同一段校验。下载地址、大小、哈希、版本
 * 全在签过名的清单里，参数不另带副本：副本是可以被改的，清单改了签名就不过。
 */
final class ProxyCoreManifest {
    static final String CHANNEL = "d31-proxy-core";
    static final String PACKAGE = "net.elfradio.d31.proxycore";
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final String VERSION = "^v?[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]{1,16})?$";

    final String version, sha256, url, jobId;
    final long size;

    private ProxyCoreManifest(String version, long size, String sha256, String url, String jobId) {
        this.version = version; this.size = size; this.sha256 = sha256; this.url = url; this.jobId = jobId;
    }

    static ProxyCoreManifest parse(JSONObject core, PublicKey key, long now) throws Exception {
        if (core == null) throw new IOException("PROXY_CORE_MISSING");
        String raw = core.optString("manifest_raw"), hex = core.optString("signature");
        if (raw.isEmpty() || raw.length() > 16000 || !hex.matches("[0-9a-fA-F]{512}")) throw new IOException("PROXY_CORE_SIGNATURE_FORMAT");
        byte[] signature = new byte[hex.length() / 2];
        for (int i = 0; i < signature.length; i++) signature[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(key);
        verifier.update(raw.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(signature)) throw new IOException("PROXY_CORE_SIGNATURE_INVALID");
        JSONObject m = new JSONObject(raw);
        if (!CHANNEL.equals(m.optString("channel")) || !PACKAGE.equals(m.optString("package")) || !"mdl_d31".equals(m.optString("model_id")))
            throw new IOException("PROXY_CORE_CHANNEL_INVALID");
        String version = m.optString("versionName"), sha = m.optString("sha256"), jobId = m.optString("job_id");
        long size = m.optLong("size"), expires = m.optLong("expires_at");
        if (!version.matches(VERSION) || version.length() > 32) throw new IOException("PROXY_CORE_VERSION_INVALID");
        if (!sha.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_BYTES) throw new IOException("PROXY_CORE_METADATA_INVALID");
        if (!jobId.matches("[A-Za-z0-9_-]{1,96}")) throw new IOException("PROXY_CORE_JOB_INVALID");
        if (expires <= now) throw new IOException("PROXY_CORE_MANIFEST_EXPIRED");
        // 与 APK 更新一致：下载口就是 /api/elfremote/apk/<job_id>，job_id 本身是凭据，不带查询串。
        String url = ProxyDownload.validate(m.optString("url"), "/api/elfremote/apk/" + java.util.regex.Pattern.quote(jobId), false);
        return new ProxyCoreManifest(version, size, sha, url, jobId);
    }
}
