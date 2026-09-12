package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.URI;
import org.json.JSONObject;

/** 接受已实现的单向媒体会话；鉴权字段不进入快照或日志。 */
public final class RtcOffer {
    public final String id;
    public final URI uri;
    public final long expiresAt;
    public final String mode, camera;
    final String token;
    private RtcOffer(String id, URI uri, long expiry, String token, String mode, String camera) {
        this.id=id; this.uri=uri; this.expiresAt=expiry; this.token=token;this.mode=mode;this.camera=camera;
    }
    public static RtcOffer parse(JSONObject value, URI controlOrigin, long now) throws Exception {
        if(value==null || !("microphone".equals(value.optString("mode"))||"video".equals(value.optString("mode"))))
            throw new IOException("MEDIA_MODE_NOT_IMPLEMENTED");
        String camera=value.optString("camera","front");
        if("video".equals(value.optString("mode"))&&!"front".equals(camera)&&!"back".equals(camera))
            throw new IOException("MEDIA_CAMERA_INVALID");
        if(controlOrigin==null || !"https".equals(controlOrigin.getScheme()) || controlOrigin.getHost()==null
                || controlOrigin.getUserInfo()!=null || controlOrigin.getQuery()!=null || controlOrigin.getFragment()!=null
                || !("".equals(controlOrigin.getPath()) || "/".equals(controlOrigin.getPath())))
            throw new IOException("MEDIA_CONTROL_ORIGIN_INVALID");
        String id=value.optString("session_id"), token=value.optString("token");
        long expiry=value.optLong("expires_at");
        if(!id.matches("[A-Za-z0-9_-]{1,96}") || !token.matches("[A-Za-z0-9_-]{16,256}")
                || expiry<=now || expiry-now>45000) throw new IOException("MEDIA_OFFER_INVALID");
        URI uri=new URI(value.getString("url"));
        if(!"wss".equals(uri.getScheme()) || !controlOrigin.getHost().equalsIgnoreCase(uri.getHost())
                || port(uri)!=port(controlOrigin) || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null
                || !"/api/elfremote/media/device".equals(uri.getRawPath())
                || !("session_id="+id).equals(uri.getRawQuery())) throw new IOException("MEDIA_ENDPOINT_REJECTED");
        return new RtcOffer(id,uri,expiry,token,value.getString("mode"),camera);
    }
    private static int port(URI uri){return uri.getPort()<0?443:uri.getPort();}
}
