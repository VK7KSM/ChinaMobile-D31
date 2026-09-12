package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅绑定SFU订阅返回的远端音轨，不接受sendonly的空接收器。 */
public final class DownlinkTrackBinding {
    private String mid;
    public void subscribeResult(JSONObject result)throws Exception {
        if(mid!=null)throw new IOException("MEDIA_SUBSCRIBE_REPLAY");
        JSONArray tracks=result.optJSONArray("tracks");
        if(result.has("errorCode")||tracks==null||tracks.length()!=1)throw new IOException("MEDIA_DOWNLINK_TRACKS_INVALID");
        JSONObject track=tracks.getJSONObject(0);
        if(track.has("errorCode")||!"audio".equals(track.optString("trackName"))
                ||!"remote".equals(track.optString("location"))||!track.optString("mid").matches("[A-Za-z0-9_-]{1,64}"))
            throw new IOException("MEDIA_DOWNLINK_TRACKS_INVALID");
        mid=track.getString("mid");
    }
    public boolean matches(String actualMid,String direction,String kind,boolean actualTrackEvent){
        return mid!=null&&mid.equals(actualMid)&&actualTrackEvent&&"audio".equals(kind)
                &&("RECV_ONLY".equals(direction)||"SEND_RECV".equals(direction));
    }
    public boolean expected(String actualMid){return mid!=null&&mid.equals(actualMid);}
}
