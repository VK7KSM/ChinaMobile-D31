package net.elfradio.d31bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 代理核心以 root 执行，来源只能是签过名的清单。这里钉住：不验签不下载、清单被改不下载、
 * 不是本通道不下载、下载地址不是本服务器的 apk 口不下载。
 */
public class ProxyCoreManifestTest {
    private static final long NOW = 1789800000000L;
    static final KeyPair KEYS;
    static {
        try { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KEYS = generator.generateKeyPair(); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    static JSONObject manifest() throws Exception {
        return new JSONObject().put("channel", ProxyCoreManifest.CHANNEL).put("package", ProxyCoreManifest.PACKAGE)
                .put("model_id", "mdl_d31").put("versionCode", 11931).put("versionName", "1.19.31")
                .put("size", 21808349).put("sha256", "a".repeat(64)).put("expires_at", NOW + 86400000L)
                .put("job_id", "d31-proxy-core-1-19-31").put("url", "https://v.elfradio.net/api/elfremote/apk/d31-proxy-core-1-19-31");
    }

    static JSONObject signed(JSONObject manifest, KeyPair keys) throws Exception {
        String raw = manifest.toString();
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(keys.getPrivate());
        signer.update(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : signer.sign()) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return new JSONObject().put("manifest_raw", raw).put("signature", hex.toString());
    }

    private static void rejects(String code, JSONObject core) throws Exception {
        try { ProxyCoreManifest.parse(core, KEYS.getPublic(), NOW); fail("应当拒绝：" + code); }
        catch (java.io.IOException expected) { assertEquals(code, expected.getMessage()); }
    }

    @Test public void acceptsASignedManifestForThisChannel() throws Exception {
        ProxyCoreManifest parsed = ProxyCoreManifest.parse(signed(manifest(), KEYS), KEYS.getPublic(), NOW);
        assertEquals("1.19.31", parsed.version);
        assertEquals(21808349L, parsed.size);
        assertEquals("a".repeat(64), parsed.sha256);
        assertEquals("https://v.elfradio.net/api/elfremote/apk/d31-proxy-core-1-19-31", parsed.url);
    }

    @Test public void tamperedOrForeignSignaturesAreRefused() throws Exception {
        JSONObject core = signed(manifest(), KEYS);
        // 改一个字节：哈希换掉但签名没换。
        rejects("PROXY_CORE_SIGNATURE_INVALID", new JSONObject(core.toString()).put("manifest_raw",
                core.getString("manifest_raw").replace("a".repeat(64), "b".repeat(64))));
        // 别人的钥匙签的。
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        rejects("PROXY_CORE_SIGNATURE_INVALID", signed(manifest(), generator.generateKeyPair()));
        rejects("PROXY_CORE_SIGNATURE_FORMAT", new JSONObject(core.toString()).put("signature", "zz"));
        rejects("PROXY_CORE_MISSING", null);
    }

    @Test public void manifestMustBeThisChannelAndUnexpired() throws Exception {
        rejects("PROXY_CORE_CHANNEL_INVALID", signed(manifest().put("channel", "d31"), KEYS));
        rejects("PROXY_CORE_CHANNEL_INVALID", signed(manifest().put("package", "net.elfradio.d31bootstrap"), KEYS));
        rejects("PROXY_CORE_MANIFEST_EXPIRED", signed(manifest().put("expires_at", NOW), KEYS));
        rejects("PROXY_CORE_VERSION_INVALID", signed(manifest().put("versionName", "latest"), KEYS));
        rejects("PROXY_CORE_METADATA_INVALID", signed(manifest().put("size", ProxyCoreManifest.MAX_BYTES + 1), KEYS));
        rejects("PROXY_CORE_JOB_INVALID", signed(manifest().put("job_id", "bad id"), KEYS));
    }

    @Test public void downloadAddressMustBeThisServersApkEndpointForTheSameJob() throws Exception {
        rejects("PROXY_DOWNLOAD_URL_REJECTED", signed(manifest().put("url", "https://evil.example/api/elfremote/apk/d31-proxy-core-1-19-31"), KEYS));
        rejects("PROXY_DOWNLOAD_URL_REJECTED", signed(manifest().put("url", "https://v.elfradio.net/api/elfremote/apk/other-job"), KEYS));
        rejects("PROXY_DOWNLOAD_URL_REJECTED", signed(manifest().put("url", "https://v.elfradio.net/api/elfremote/apk/d31-proxy-core-1-19-31?x=1"), KEYS));
        rejects("PROXY_DOWNLOAD_URL_REJECTED", signed(manifest().put("url", "http://v.elfradio.net/api/elfremote/apk/d31-proxy-core-1-19-31"), KEYS));
    }
}
