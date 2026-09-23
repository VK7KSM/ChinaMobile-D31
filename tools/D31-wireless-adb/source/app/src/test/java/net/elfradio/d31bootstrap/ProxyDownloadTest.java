package net.elfradio.d31bootstrap;

import org.junit.Test;
import static org.junit.Assert.*;

/** 下载地址校验：与远程桌面中继地址同一套口径，漏一项就能把设备引到别的服务器去取「配置」。 */
public class ProxyDownloadTest {
    private static final String CONFIG = "https://v.elfradio.net/api/elfremote/proxy-config/task-1?device_id=dev_abc&token=" + "f".repeat(64);

    @Test public void acceptsTheConfigDownloadShape() throws Exception {
        assertEquals(CONFIG, ProxyDownload.validate(CONFIG, ProxyDownload.CONFIG_PATH));
        assertEquals(CONFIG.replace("v.elfradio.net", "v.elfradio.net:443"),
                ProxyDownload.validate(CONFIG.replace("v.elfradio.net", "v.elfradio.net:443"), ProxyDownload.CONFIG_PATH));
    }

    @Test public void rejectsEverythingThatIsNotThisServersOneTimeEndpoint() {
        rejects(CONFIG.replace("https://", "http://"));
        rejects(CONFIG.replace("v.elfradio.net", "evil.example"));
        rejects(CONFIG.replace("v.elfradio.net", "v.elfradio.net:8443"));
        rejects(CONFIG.replace("https://", "https://u@"));
        rejects(CONFIG + "#frag");
        rejects(CONFIG.replace("proxy-config", "apk"));
        rejects(CONFIG.replace("&token=", "&tok="));
        rejects(CONFIG + "&extra=1");
        rejects("https://v.elfradio.net/api/elfremote/proxy-config/task-1");
        rejects("https://v.elfradio.net/api/elfremote/proxy-config/task-1?device_id=dev_abc&token=short");
        rejects(CONFIG.replace("task-1", "task 1"));
        rejects(null);
        rejects("https://v.elfradio.net/api/elfremote/proxy-config/task-1?device_id=dev&token=" + "f".repeat(64));
    }

    @Test public void oneTimeTokenLeavesTheQueryStringAndGoesIntoTheHeader() {
        // 查询串会进各种访问日志，一次性下载令牌不该留在那里（web-dev F6）。
        String[] split = ProxyDownload.splitToken(CONFIG);
        assertEquals("https://v.elfradio.net/api/elfremote/proxy-config/task-1?device_id=dev_abc", split[0]);
        assertEquals("f".repeat(64), split[1]);
        // 顺序反过来也要摘干净，且不能把 device_id 一起丢掉。
        split = ProxyDownload.splitToken("https://v.elfradio.net/x?token=abc&device_id=dev_abc");
        assertEquals("https://v.elfradio.net/x?device_id=dev_abc", split[0]);
        assertEquals("abc", split[1]);
        // 只有令牌一项时问号也要去掉，不能留下空查询串。
        split = ProxyDownload.splitToken("https://v.elfradio.net/x?token=abc");
        assertEquals("https://v.elfradio.net/x", split[0]);
        assertEquals("abc", split[1]);
        // 本来就没有令牌（服务端改完之后的形状）原样放行，不臆造。
        split = ProxyDownload.splitToken("https://v.elfradio.net/api/elfremote/apk/job-1");
        assertEquals("https://v.elfradio.net/api/elfremote/apk/job-1", split[0]);
        assertEquals("", split[1]);
    }

    @Test public void configUrlIsAcceptedWithOrWithoutTheTokenParameter() throws Exception {
        // 服务端过渡期仍下发带 token 的地址；改完之后会只剩 device_id，两种都要收。
        String withoutToken = "https://v.elfradio.net/api/elfremote/proxy-config/task-1?device_id=dev_abc";
        assertEquals(withoutToken, ProxyDownload.validate(withoutToken, ProxyDownload.CONFIG_PATH));
        assertEquals(CONFIG, ProxyDownload.validate(CONFIG, ProxyDownload.CONFIG_PATH));
        // 但 device_id 仍然必须有，且不接受别的参数。
        rejects("https://v.elfradio.net/api/elfremote/proxy-config/task-1?token=" + "f".repeat(64));
        rejects(withoutToken + "&relay=evil.example");
    }

    @Test public void coreDownloadIsTheApkEndpointWithoutAnyQuery() throws Exception {
        String core = "https://v.elfradio.net/api/elfremote/apk/d31-proxy-core-1";
        assertEquals(core, ProxyDownload.validate(core, "/api/elfremote/apk/d31-proxy-core-1", false));
        try { ProxyDownload.validate(core + "?device_id=dev&token=" + "f".repeat(64), "/api/elfremote/apk/d31-proxy-core-1", false); fail(); }
        catch (java.io.IOException expected) { assertEquals("PROXY_DOWNLOAD_URL_REJECTED", expected.getMessage()); }
    }

    private static void rejects(String url) {
        try { ProxyDownload.validate(url, ProxyDownload.CONFIG_PATH); fail("应当拒绝：" + url); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().startsWith("PROXY_DOWNLOAD_URL_")); }
    }
}
