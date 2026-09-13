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
        if(hasError(result)||tracks==null||tracks.length()!=1)throw new IOException("MEDIA_DOWNLINK_TRACKS_INVALID");
        JSONObject track=tracks.getJSONObject(0);
        if(hasError(track)||!"audio".equals(track.optString("trackName"))
                ||(!track.isNull("location")&&!"remote".equals(track.optString("location")))
                ||!(track.opt("mid") instanceof String)||!track.optString("mid").matches("[A-Za-z0-9_-]{1,64}"))
            throw new IOException("MEDIA_DOWNLINK_TRACKS_INVALID");
        mid=track.getString("mid");
    }
    private static boolean hasError(JSONObject value){
        Object error=value.opt("errorCode");
        return error!=null&&error!=JSONObject.NULL&&(!(error instanceof String)||!((String)error).isEmpty());
    }
    /** 仅记录协议形状，不保存SDP、会话标识或远端音轨名称原文。 */
    public static JSONObject shape(JSONObject result)throws Exception{
        JSONObject shape=new JSONObject().put("error_code_type",type(result,"errorCode"));
        JSONArray tracks=result.optJSONArray("tracks");
        shape.put("tracks_type",type(result,"tracks")).put("track_count",tracks==null?-1:tracks.length());
        if(tracks!=null&&tracks.length()==1&&tracks.optJSONObject(0)!=null){
            JSONObject track=tracks.getJSONObject(0);
            shape.put("track_error_type",type(track,"errorCode")).put("location_type",type(track,"location"))
                    .put("location_remote","remote".equals(track.optString("location")))
                    .put("track_name_audio","audio".equals(track.optString("trackName")))
                    .put("mid_valid",track.opt("mid") instanceof String&&track.optString("mid").matches("[A-Za-z0-9_-]{1,64}"));
        }
        return shape;
    }
    private static String type(JSONObject object,String key){
        if(!object.has(key))return "ABSENT";Object value=object.opt(key);
        if(value==JSONObject.NULL)return "NULL";
        if(value instanceof String)return ((String)value).isEmpty()?"EMPTY_STRING":"STRING";
        if(value instanceof JSONArray)return "ARRAY";
        if(value instanceof JSONObject)return "OBJECT";
        if(value instanceof Number)return "NUMBER";
        if(value instanceof Boolean)return "BOOLEAN";
        return "UNKNOWN";
    }
    public boolean matches(String actualMid,String direction,String kind,boolean actualTrackEvent){
        return mid!=null&&mid.equals(actualMid)&&actualTrackEvent&&"audio".equals(kind)
                &&("RECV_ONLY".equals(direction)||"SEND_RECV".equals(direction));
    }
    public boolean expected(String actualMid){return mid!=null&&mid.equals(actualMid);}
}
