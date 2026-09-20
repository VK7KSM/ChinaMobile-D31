package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 分享链接载荷校验。链接由服务端生成后推下来，设备不索取也不持有令牌；
 * 这里守住三件事：不显示已失效的二维码、不把口令画到可能摆在公共位置的屏幕上、显示时长有界。
 */
public class ShareLinkPayloadTest {
    private static final long NOW = 1_789_800_000_000L;

    private static JSONObject params() throws Exception {
        return new JSONObject().put("url", "https://v.elfradio.net/m/abc123def456")
                .put("qr_text", "HTTPS://V.ELFRADIO.NET/M/ABC123DEF456")
                .put("link_expires_at", NOW + 3600_000L);
    }

    @Test public void acceptsServerPayloadAndClampsDisplayWindow() throws Exception {
        ShareLinkPayload parsed = ShareLinkPayload.parse(params(), NOW);
        assertEquals("https://v.elfradio.net/m/abc123def456", parsed.url);
        assertEquals("HTTPS://V.ELFRADIO.NET/M/ABC123DEF456", parsed.qrText);
        assertEquals(NOW + 3600_000L, parsed.linkExpiresAt);
        assertEquals("未下发显示时长时用缺省值", ShareLinkPayload.DISPLAY_DEFAULT_MS, parsed.displayMs);

        assertEquals(ShareLinkPayload.DISPLAY_MIN_MS, ShareLinkPayload.parse(params().put("display_ms", 1), NOW).displayMs);
        assertEquals(ShareLinkPayload.DISPLAY_MAX_MS, ShareLinkPayload.parse(params().put("display_ms", 86_400_000L), NOW).displayMs);
        assertEquals(ShareLinkPayload.DISPLAY_DEFAULT_MS, ShareLinkPayload.parse(params().put("display_ms", -5), NOW).displayMs);
        assertEquals(60_000L, ShareLinkPayload.parse(params().put("display_ms", 60_000L), NOW).displayMs);
    }

    @Test public void neverOutlivesTheLinkItself() throws Exception {
        // 链接只剩30秒，显示时长却是2分钟：必须按链接有效期撤下，不能继续显示已失效的码。
        ShareLinkPayload shortLink = ShareLinkPayload.parse(params().put("link_expires_at", NOW + 30_000L), NOW);
        assertEquals(NOW + 30_000L, shortLink.deadline(NOW));
        // 链接还有一小时，显示时长2分钟：按显示时长撤下，不长期占屏。
        assertEquals(NOW + ShareLinkPayload.DISPLAY_DEFAULT_MS, ShareLinkPayload.parse(params(), NOW).deadline(NOW));
    }

    @Test public void rejectsExpiredMalformedAndCredentialBearingPayloads() throws Exception {
        String[] forbidden = {"password", "passcode", "pin", "secret", "token", "credential"};
        for (String key : forbidden) {
            try { ShareLinkPayload.parse(params().put(key, "x"), NOW); fail("含" + key + "应拒收"); }
            catch (Exception expected) { assertEquals("SHARE_LINK_CREDENTIAL_FORBIDDEN", expected.getMessage()); }
        }
        try { ShareLinkPayload.parse(params().put("link_expires_at", NOW), NOW); fail("已过期应拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_ALREADY_EXPIRED", expected.getMessage()); }
        try { ShareLinkPayload.parse(params().put("link_expires_at", NOW - 1), NOW); fail("已过期应拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_ALREADY_EXPIRED", expected.getMessage()); }
        try { ShareLinkPayload.parse(params().put("url", "http://v.elfradio.net/m/x"), NOW); fail("非https应拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_URL_INVALID", expected.getMessage()); }
        try { ShareLinkPayload.parse(params().put("qr_text", ""), NOW); fail("空二维码内容应拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_QR_TEXT_INVALID", expected.getMessage()); }
        StringBuilder overlong = new StringBuilder("HTTPS://");
        while (overlong.length() <= ShareLinkPayload.TEXT_MAX) overlong.append('A');
        try { ShareLinkPayload.parse(params().put("qr_text", overlong.toString()), NOW); fail("超长应拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_QR_TEXT_INVALID", expected.getMessage()); }
        try { ShareLinkPayload.parse(null, NOW); fail("缺载荷应拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_PARAMS_MISSING", expected.getMessage()); }
    }

    @Test public void reportsQrModeInsteadOfSilentlyFallingBack() {
        // 服务端给的是全大写，能进字母数字模式，码最稀疏最好扫。
        assertTrue(ShareLinkPayload.alphanumeric("HTTPS://V.ELFRADIO.NET/M/ABC123DEF456"));
        assertEquals("alphanumeric", ShareLinkPayload.qrMode("HTTPS://V.ELFRADIO.NET/M/ABC123DEF456"));
        // 万一混进小写，仍然能出码，但必须在回执里注明退回了字节模式。
        assertFalse(ShareLinkPayload.alphanumeric("https://v.elfradio.net/m/abc"));
        assertEquals("byte", ShareLinkPayload.qrMode("https://v.elfradio.net/m/abc"));
        assertEquals("byte", ShareLinkPayload.qrMode(""));
        assertEquals("byte", ShareLinkPayload.qrMode(null));
    }

    @Test public void qrTextMustEncodeTheSameAddressThatIsDisplayed() throws Exception {
        // 屏幕上显示 url，二维码里编码 qr_text。两者不一致就是钓鱼的形状——扫码的人看的是码不是字。
        JSONObject mismatched = params().put("qr_text", "HTTPS://V.ELFRADIO.NET/M/OTHER1");
        try { ShareLinkPayload.parse(mismatched, NOW); fail("二维码与显示的网址不一致必须拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_QR_TEXT_MISMATCH", expected.getMessage()); }

        // 只有大小写形式的差别是合同规定的（二维码字母数字模式不收小写）。
        assertEquals("HTTPS://V.ELFRADIO.NET/M/ABC123DEF456", ShareLinkPayload.parse(params(), NOW).qrText);
    }

    @Test public void urlMustPointAtThisManagementServer() throws Exception {
        JSONObject elsewhere = params().put("url", "https://evil.example/m/ABC123")
                .put("qr_text", "HTTPS://EVIL.EXAMPLE/M/ABC123");
        try { ShareLinkPayload.parse(elsewhere, NOW); fail("别的主机必须拒收"); }
        catch (Exception expected) { assertEquals("SHARE_LINK_URL_HOST_INVALID", expected.getMessage()); }
    }
}
