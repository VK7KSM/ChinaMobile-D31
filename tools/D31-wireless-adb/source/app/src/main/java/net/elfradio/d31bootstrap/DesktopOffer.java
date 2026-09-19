package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 远程桌面会话邀约的校验与画质换算。纯逻辑，不碰安卓接口，便于单元测试。
 *
 * 中继地址校验整段照搬 D22（FreePBX_VPNnode_Web 提交 0987942）：只比对协议、主机名和路径是不够的，
 * wss://u@host、host:8443、带片段的地址都能绕过去，把设备引到别处，所以用户信息、片段、端口、
 * 查询串都要卡死，且查询串必须恰好绑定本次会话号。
 *
 * 令牌只做健壮性检查，不校验形状：它是服务端签发、服务端核验的持有者凭据，
 * 形状属于服务端实现细节（当前是两个 UUID 拼接、72字符带短横，不是 ADB 那种64位十六进制）。
 * 设备端钉死格式只会在服务端换格式时把自己弄哑，而且哑得很难查。这里只限长度与字符集，挡住头注入。
 */
public final class DesktopOffer {
    public static final String CAPABILITY = "managed_desktop_v1";
    public static final String PATH = "/api/elfremote/desktop/device";
    /** 服务端只认这两个档位，传别的会被中继拒掉。 */
    public static final String WIFI = "wifi", CELLULAR = "cellular";
    public static final int TOKEN_MAX = 256;

    public final String sessionId, token, quality;
    public final int generation;
    public final long expiresAt;
    public final URI url;
    public final JSONArray iceServers;

    private DesktopOffer(String sessionId, String token, String quality, int generation,
                         long expiresAt, URI url, JSONArray iceServers) {
        this.sessionId = sessionId; this.token = token; this.quality = quality;
        this.generation = generation; this.expiresAt = expiresAt; this.url = url; this.iceServers = iceServers;
    }

    /** 档位到 scrcpy 参数。取值与网关一致，不另定一套；D31 是 1280x800，蜂窝档要压。 */
    public static int maxFps(String quality) { return CELLULAR.equals(quality) ? 15 : 30; }
    public static int bitRate(String quality) { return CELLULAR.equals(quality) ? 500000 : 1500000; }
    public static int maxSize(String quality) { return CELLULAR.equals(quality) ? 960 : 1280; }

    public static boolean validToken(String token) {
        return token != null && !token.isEmpty() && token.length() <= TOKEN_MAX
                && token.matches("[A-Za-z0-9._~+/=-]+");
    }

    public static JSONObject scrcpyParams(String scid, String quality) throws Exception {
        return new JSONObject().put("scid", scid).put("max_fps", maxFps(quality))
                .put("bit_rate", bitRate(quality)).put("max_size", maxSize(quality));
    }

    public static DesktopOffer parse(JSONObject offer, long now) throws Exception {
        if (offer == null) throw new IOException("DESKTOP_OFFER_MISSING");
        String sessionId = offer.optString("session_id");
        if (!sessionId.matches("[a-f0-9-]{36}")) throw new IOException("DESKTOP_SESSION_ID_INVALID");
        long expiresAt = offer.optLong("expires_at");
        if (expiresAt <= now) throw new IOException("DESKTOP_OFFER_EXPIRED");
        String token = offer.optString("token");
        if (!validToken(token)) throw new IOException("DESKTOP_TOKEN_INVALID");
        String quality = offer.optString("quality", WIFI);
        if (!WIFI.equals(quality) && !CELLULAR.equals(quality)) throw new IOException("DESKTOP_QUALITY_INVALID");

        URI url = new URI(offer.optString("url")), control = new URI(RemoteProtocol.BASE);
        if (!"wss".equals(url.getScheme()) || !control.getHost().equals(url.getHost())
                || url.getUserInfo() != null || url.getFragment() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !PATH.equals(url.getPath())
                || !("session_id=" + sessionId).equals(url.getRawQuery()))
            throw new IOException("DESKTOP_RELAY_URL_INVALID");

        int generation = offer.optInt("generation", 1);
        if (generation < 1 || generation > 8) throw new IOException("DESKTOP_GENERATION_INVALID");
        JSONArray iceServers = offer.optJSONArray("ice_servers");
        return new DesktopOffer(sessionId, token, quality, generation, expiresAt, url,
                iceServers == null ? new JSONArray() : iceServers);
    }

    /** 传给应用进程的邀约；原样带过去，应用侧不再自己拼地址。 */
    public JSONObject forApp() throws Exception {
        return new JSONObject().put("session_id", sessionId).put("token", token)
                .put("quality", quality).put("generation", generation)
                .put("expires_at", expiresAt).put("url", url.toString())
                .put("ice_servers", iceServers);
    }
}
