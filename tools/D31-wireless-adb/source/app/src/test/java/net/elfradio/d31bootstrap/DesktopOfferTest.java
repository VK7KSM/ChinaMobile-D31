package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 远程桌面邀约校验。
 *
 * 最要紧的是中继地址：只比对协议、主机名、路径挡不住 wss://u@host、host:8443、带片段这几种写法，
 * 邀约被改写就能把设备的屏幕引到别人的中继上去。这组用例逐条钉住 D22 提交 0987942 补上的那几项。
 */
public class DesktopOfferTest {
    private static final long NOW = 1700000000000L;
    private static final String SESSION = "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
    /** 服务端签发的是两个 UUID 拼接，72字符带短横，不是 ADB 那种64位十六进制。 */
    private static final String TOKEN = "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d" + "1b2c3d4e-5f6a-4b7c-8d9e-1f2a3b4c5d6e";

    private JSONObject offer() throws Exception {
        return new JSONObject().put("session_id", SESSION).put("token", TOKEN)
                .put("quality", "wifi").put("generation", 1)
                .put("expires_at", NOW + 30000)
                .put("url", "wss://v.elfradio.net/api/elfremote/desktop/device?session_id=" + SESSION)
                .put("ice_servers", new JSONArray().put(new JSONObject().put("urls", "stun:stun.cloudflare.com:3478")));
    }

    private void rejects(String why, JSONObject offer) {
        try { DesktopOffer.parse(offer, NOW); fail("应当拒绝：" + why); }
        catch (Exception expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void acceptsAWellFormedOffer() throws Exception {
        DesktopOffer parsed = DesktopOffer.parse(offer(), NOW);
        assertEquals(SESSION, parsed.sessionId);
        assertEquals(TOKEN, parsed.token);
        assertEquals("wifi", parsed.quality);
        assertEquals(1, parsed.generation);
        assertEquals(1, parsed.iceServers.length());
        // 原样带给应用进程，应用侧不再自己拼地址。
        assertEquals(parsed.url.toString(), parsed.forApp().getString("url"));
        assertEquals(TOKEN, parsed.forApp().getString("token"));
    }

    @Test public void relayAddressMustBeBoundToThisServerAndThisSession() throws Exception {
        // 协议、主机名、路径这三项是显而易见的。
        rejects("明文 ws", offer().put("url", "ws://v.elfradio.net" + DesktopOffer.PATH + "?session_id=" + SESSION));
        rejects("别的主机", offer().put("url", "wss://evil.example/api/elfremote/desktop/device?session_id=" + SESSION));
        rejects("别的路径", offer().put("url", "wss://v.elfradio.net/api/elfremote/adb/device?session_id=" + SESSION));

        // 下面四项是只校验前三项时会漏掉的。
        rejects("带用户信息", offer().put("url", "wss://u@v.elfradio.net" + DesktopOffer.PATH + "?session_id=" + SESSION));
        rejects("非443端口", offer().put("url", "wss://v.elfradio.net:8443" + DesktopOffer.PATH + "?session_id=" + SESSION));
        rejects("带片段", offer().put("url", "wss://v.elfradio.net" + DesktopOffer.PATH + "?session_id=" + SESSION + "#x"));
        rejects("会话号不是本次的", offer().put("url", "wss://v.elfradio.net" + DesktopOffer.PATH
                + "?session_id=ffffffff-4e5f-4a6b-8c9d-0e1f2a3b4c5d"));
        // 查询串必须恰好是会话号，多挂一个参数也不行。
        rejects("查询串被追加", offer().put("url", "wss://v.elfradio.net" + DesktopOffer.PATH
                + "?session_id=" + SESSION + "&relay=evil.example"));

        // 显式写出 443 是接受的：它和省略端口指向同一个端点，D22 那段就是这么放行的，此处不另立规矩。
        assertEquals(SESSION, DesktopOffer.parse(offer().put("url",
                "wss://v.elfradio.net:443" + DesktopOffer.PATH + "?session_id=" + SESSION), NOW).sessionId);
    }

    @Test public void tokenIsCheckedForSafetyNotForShape() throws Exception {
        // 形状是服务端实现细节，钉死格式只会在服务端换格式时把自己弄哑。
        assertTrue("两个UUID拼接必须能过", DesktopOffer.validToken(TOKEN));
        assertTrue("换成64位十六进制也必须能过", DesktopOffer.validToken("a".repeat(64)));
        assertTrue(DesktopOffer.validToken("Ab0._~+/=-"));
        // 但要挡住头注入与空值。
        assertFalse(DesktopOffer.validToken(""));
        assertFalse(DesktopOffer.validToken(null));
        assertFalse("换行会被注进 Authorization 头", DesktopOffer.validToken("abc\r\nX-Evil: 1"));
        assertFalse(DesktopOffer.validToken("abc def"));
        assertFalse(DesktopOffer.validToken("a".repeat(DesktopOffer.TOKEN_MAX + 1)));
        rejects("空令牌", offer().put("token", ""));
    }

    @Test public void expiredOrMalformedEnvelopeIsRefused() throws Exception {
        rejects("已过期", offer().put("expires_at", NOW));
        rejects("没有期限", offer().put("expires_at", 0));
        rejects("会话号形状不对", offer().put("session_id", "not-a-uuid"));
        rejects("档位服务端不认", offer().put("quality", "ultra"));
        rejects("代次为0", offer().put("generation", 0));
        rejects("邀约为空", null);
    }

    @Test public void qualityTiersMatchTheGatewayValues() {
        // 这组数值是设备侧合同的一部分，和网关对齐，不各定一套。
        assertEquals(30, DesktopOffer.maxFps("wifi"));
        assertEquals(1500000, DesktopOffer.bitRate("wifi"));
        assertEquals(1280, DesktopOffer.maxSize("wifi"));
        assertEquals(15, DesktopOffer.maxFps("cellular"));
        assertEquals(500000, DesktopOffer.bitRate("cellular"));
        assertEquals(960, DesktopOffer.maxSize("cellular"));

        // 换算出来的参数必须落在拉起器的夹紧范围内，否则会被静默改掉，两边对不上账。
        for (String quality : new String[]{"wifi", "cellular"}) {
            assertEquals(DesktopOffer.maxFps(quality), DesktopLauncher.clampFps(DesktopOffer.maxFps(quality)));
            assertEquals(DesktopOffer.bitRate(quality), DesktopLauncher.clampBitRate(DesktopOffer.bitRate(quality)));
            assertEquals(DesktopOffer.maxSize(quality), DesktopLauncher.clampSize(DesktopOffer.maxSize(quality)));
        }
    }

    @Test public void scrcpyParamsCarryTheTierThrough() throws Exception {
        JSONObject cellular = DesktopOffer.scrcpyParams("0a1b2c3d", "cellular");
        assertEquals("0a1b2c3d", cellular.getString("scid"));
        assertEquals(15, cellular.getInt("max_fps"));
        assertEquals(500000, cellular.getInt("bit_rate"));
        assertEquals(960, cellular.getInt("max_size"));
    }
}
