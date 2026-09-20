package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 设备自助生成管理链接的纯逻辑。
 *
 * 最该钉住的是回体校验：屏幕上显示的是网址、二维码里编码的是 qr_text，
 * 两者不一致就是钓鱼的形状，而扫码的人看的是码不是字。
 */
public class DeviceShareLinkTest {
    private static final long NOW = 1789800000000L;

    private static JSONObject reply() throws Exception {
        return new JSONObject().put("ok", true)
                .put("url", "https://v.elfradio.net/m/VGSR33WWZP2H")
                .put("qr_text", "HTTPS://V.ELFRADIO.NET/M/VGSR33WWZP2H")
                .put("expires_at", NOW + 3600_000L);
    }

    private static void rejects(String why, JSONObject reply) {
        try { DeviceShareLink.parse(reply); fail("应当拒绝：" + why); }
        catch (Exception expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void acceptsAWellFormedReply() throws Exception {
        DeviceShareLink.Result result = DeviceShareLink.parse(reply());
        assertEquals("https://v.elfradio.net/m/VGSR33WWZP2H", result.url);
        assertEquals("HTTPS://V.ELFRADIO.NET/M/VGSR33WWZP2H", result.qrText);
        assertEquals(NOW + 3600_000L, result.expiresAt);
        assertFalse(result.duplicate);
        // 同一个请求编号重试时服务端会标记，界面上要说明是沿用而不是又开了一条。
        assertTrue(DeviceShareLink.parse(reply().put("duplicate", true)).duplicate);
    }

    @Test public void qrTextMustEncodeTheSameAddressThatIsDisplayed() throws Exception {
        rejects("二维码与显示的网址不是同一个",
                reply().put("qr_text", "HTTPS://V.ELFRADIO.NET/M/OTHER1234567"));
        // 缺省时按网址大写推出来，仍然一致。
        JSONObject noQr = reply();
        noQr.remove("qr_text");
        assertEquals("HTTPS://V.ELFRADIO.NET/M/VGSR33WWZP2H", DeviceShareLink.parse(noQr).qrText);
    }

    @Test public void urlMustBeHttpsAndPointAtThisManagementServer() throws Exception {
        rejects("明文", reply().put("url", "http://v.elfradio.net/m/VGSR33WWZP2H")
                .put("qr_text", "HTTP://V.ELFRADIO.NET/M/VGSR33WWZP2H"));
        rejects("别的主机", reply().put("url", "https://evil.example/m/VGSR33WWZP2H")
                .put("qr_text", "HTTPS://EVIL.EXAMPLE/M/VGSR33WWZP2H"));
        rejects("没有网址", reply().put("url", ""));
    }

    @Test public void requestCarriesIdentityAndRejectsMalformedRequestId() throws Exception {
        JSONObject body = DeviceShareLink.request("dev_abc", "0123456789abcdef", "req-1");
        assertEquals("dev_abc", body.getString("device_id"));
        assertEquals("0123456789abcdef", body.getString("token"));
        assertEquals("req-1", body.getString("request_id"));
        // 请求编号是幂等键，形状不对服务端会忽略它，于是每按一次都会多开一条链接。
        for (String bad : new String[]{"", "带空格 的", "斜杠/不行", "a".repeat(97)}) {
            try { DeviceShareLink.request("dev_abc", "t", bad); fail("应当拒绝请求编号：" + bad); }
            catch (Exception expected) { assertEquals("SHARE_LINK_REQUEST_ID_INVALID", expected.getMessage()); }
        }
    }

    @Test public void remainingIsRoundedUpAndNeverNegative() {
        assertEquals("有效期约 1 小时", DeviceShareLink.remaining(NOW + 3600_000L, NOW));
        assertEquals("有效期约 30 分钟", DeviceShareLink.remaining(NOW + 1800_000L, NOW));
        // 已过期不显示负数。
        assertEquals("有效期约 0 分钟", DeviceShareLink.remaining(NOW - 1000, NOW));
        assertEquals("", DeviceShareLink.remaining(0, NOW));
    }
}
