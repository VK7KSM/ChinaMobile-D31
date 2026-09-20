package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;

/**
 * 分享链接任务载荷的校验与归一。纯逻辑，不碰安卓接口，便于单元测试。
 *
 * 链接由服务端生成后推下来，设备不向服务端索取，因此本流程全程不读设备令牌。
 * 二维码只编码 qr_text（全大写）：二维码的字母数字模式不收小写，小写会静默退回字节模式让码变密。
 */
public final class ShareLinkPayload {
    static final String TYPE = "show_share_link";
    /**
     * 核心与 :visual 之间那条桥上的操作名。放在这里是因为 PhotoAlarmContract 是 media 包私有的，
     * 核心侧够不着；两边各写一份字符串迟早会漂，所以以本类为唯一出处。
     */
    public static final String OP_SHOW = "share_link_show", OP_QUERY = "share_link_query",
            OP_DISMISS = "share_link_dismiss";
    static final long DISPLAY_MIN_MS = 15000L, DISPLAY_MAX_MS = 300000L, DISPLAY_DEFAULT_MS = 120000L;
    static final int TEXT_MAX = 512;
    /** 二维码字母数字模式的字符集；不在其中就只能用字节模式。 */
    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
    /**
     * 载荷顶层出现任何一项都直接拒收。这是第二道闸，不是安全边界：
     * 只查顶层精确键名，嵌套对象里的同名字段查不到；而且分享链接本身就是持有即可用的凭据，
     * 所以「口令不进设备」这个说法要收着讲——真正该保证的是链接有效期短、且只显示给在场的人。
     */
    private static final String[] FORBIDDEN = {"password", "passcode", "pin", "secret", "token", "credential"};

    final String url, qrText;
    final long linkExpiresAt, displayMs;

    private ShareLinkPayload(String url, String qrText, long linkExpiresAt, long displayMs) {
        this.url = url; this.qrText = qrText; this.linkExpiresAt = linkExpiresAt; this.displayMs = displayMs;
    }

    /** 显示时长夹紧：座机平时是屏保，二维码长期占屏会挡住正常使用也有烧屏风险。 */
    static long clampDisplayMs(long requested) {
        if (requested <= 0) return DISPLAY_DEFAULT_MS;
        return Math.max(DISPLAY_MIN_MS, Math.min(DISPLAY_MAX_MS, requested));
    }

    /** 能否用字母数字模式编码；不能则退回字节模式，回执里注明，不静默。 */
    static boolean alphanumeric(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (ALPHANUMERIC.indexOf(text.charAt(i)) < 0) return false;
        return true;
    }

    static String qrMode(String text) { return alphanumeric(text) ? "alphanumeric" : "byte"; }

    private static String text(JSONObject params, String key) throws IOException {
        String value = params.optString(key, "");
        if (value.isEmpty() || value.length() > TEXT_MAX) throw new IOException("SHARE_LINK_" + key.toUpperCase(java.util.Locale.US) + "_INVALID");
        return value;
    }

    /**
     * 校验并归一载荷。服务端已过期或缺字段一律拒收，不显示半截信息。
     * 载荷里的 link_expires_at 是链接有效期，和任务信封的 expires_at（领取期限）是两回事，不能混用。
     */
    static ShareLinkPayload parse(JSONObject params, long now) throws Exception {
        if (params == null) throw new IOException("SHARE_LINK_PARAMS_MISSING");
        for (String forbidden : FORBIDDEN)
            if (params.has(forbidden)) throw new IOException("SHARE_LINK_CREDENTIAL_FORBIDDEN");
        String url = text(params, "url"), qrText = text(params, "qr_text");
        if (!url.startsWith("https://")) throw new IOException("SHARE_LINK_URL_INVALID");
        // 只认本管理服务器的主机，口径与远程桌面校验中继地址一致：
        // 邀约被改写就能把扫码的人引到别处去。
        if (!new java.net.URI(url).getHost().equals(new java.net.URI(RemoteProtocol.BASE).getHost()))
            throw new IOException("SHARE_LINK_URL_HOST_INVALID");
        // 屏幕上显示的是 url，二维码里编码的是 qr_text，两者必须是同一个地址。
        // 不钉死的话，「看到的」和「扫到的」可以不一致，那正是钓鱼的形状——扫码的人看的是码不是字。
        // 服务端保证 qr_text 就是 url 的大写形式（二维码字母数字模式不收小写），这里复核一遍。
        if (!qrText.equals(url.toUpperCase(java.util.Locale.US)))
            throw new IOException("SHARE_LINK_QR_TEXT_MISMATCH");
        long linkExpiresAt = params.optLong("link_expires_at", 0);
        if (linkExpiresAt <= now) throw new IOException("SHARE_LINK_ALREADY_EXPIRED");
        return new ShareLinkPayload(url, qrText, linkExpiresAt, clampDisplayMs(params.optLong("display_ms", 0)));
    }

    /**
     * 实际显示到何时：显示时长与链接有效期取先到者。
     * 到了链接有效期必须立刻撤下，显示一个已失效的二维码比不显示更糟，扫的人会得到错误页。
     */
    long deadline(long now) { return Math.min(now + displayMs, linkExpiresAt); }
}
