package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.URI;
import org.json.JSONObject;

/** 既有media-relay的非RTC会话合同；不把会话凭据写入诊断。 */
public final class PhotoAlarmOffer {
    public final String id, mode, camera;
    public final URI uri;
    public final long expiresAt;
    final String token;
    private PhotoAlarmOffer(String id,String mode,String camera,URI uri,String token,long expiry) {
        this.id=id;this.mode=mode;this.camera=camera;this.uri=uri;this.token=token;this.expiresAt=expiry;
    }
    public static PhotoAlarmOffer parse(JSONObject value,URI origin,long now)throws Exception {
        if(value==null||!("photo".equals(value.optString("mode"))||"alarm".equals(value.optString("mode"))))
            throw new IOException("VISUAL_MODE_INVALID");
        if(origin==null||!"https".equals(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null
                ||origin.getQuery()!=null||origin.getFragment()!=null||!(origin.getPath().isEmpty()||"/".equals(origin.getPath())))
            throw new IOException("VISUAL_ORIGIN_INVALID");
        String id=value.optString("session_id"),token=value.optString("token"),camera=value.optString("camera","front");
        Object expiry=value.opt("expires_at");long end=value.optLong("expires_at");
        if(!id.matches("[A-Za-z0-9_-]{1,96}")||!token.matches("[A-Za-z0-9_-]{16,256}")
                ||!(expiry instanceof Number)||end<=now||end-now>45000
                ||!("front".equals(camera)||"back".equals(camera)))throw new IOException("VISUAL_OFFER_INVALID");
        URI uri=new URI(value.getString("url"));
        if(!"wss".equals(uri.getScheme())||!origin.getHost().equalsIgnoreCase(uri.getHost())
                ||port(uri)!=port(origin)||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
                ||!"/api/elfremote/media/device".equals(uri.getRawPath())||!("session_id="+id).equals(uri.getRawQuery()))
            throw new IOException("VISUAL_ENDPOINT_REJECTED");
        return new PhotoAlarmOffer(id,value.getString("mode"),camera,uri,token,end);
    }
    private static int port(URI uri){return uri.getPort()<0?443:uri.getPort();}
}
