package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

/** 私有APP Binder接线；网络信令仍保持生产合同。 */
final class PhotoAlarmContract {
    static final String PACKAGE="net.elfradio.d31bootstrap";
    static final String SERVICE=PACKAGE+".media.PhotoAlarmService";
    static final String ACTION=PACKAGE+".media.VISUAL_CONTROL";
    static final String DESCRIPTOR=PACKAGE+".media.IPhotoAlarmControl";
    static final int EXECUTE=1,HELLO=1,RESULT=2;
    static final long WAIT_MS=6000;
    static JSONObject command(String raw)throws Exception {
        if(raw==null||raw.length()>16384)throw new IOException("VISUAL_COMMAND_SIZE");
        JSONObject x=new JSONObject(raw);String op=x.optString("operation");
        if(!"prepare".equals(op)&&!"start".equals(op)&&!"query".equals(op)&&!"stop".equals(op))throw new IOException("VISUAL_COMMAND_INVALID");
        if(("prepare".equals(op)||"start".equals(op))&&!x.optString("apk_sha256").matches("[a-f0-9]{64}"))throw new IOException("VISUAL_APK_INVALID");
        if(("query".equals(op)||"stop".equals(op))&&!x.optString("session_id").matches("[A-Za-z0-9_-]{0,96}"))throw new IOException("VISUAL_SESSION_INVALID");
        return x;
    }
    static JSONObject error(String code){try{return new JSONObject().put("command_error",true).put("error",code);}catch(Exception ignored){return new JSONObject();}}
    static JSONObject reply(JSONObject value)throws Exception {
        if(value==null)throw new IOException("VISUAL_REPLY_UNKNOWN");
        if(Boolean.TRUE.equals(value.opt("command_error")))throw new IOException(value.optString("error","VISUAL_COMMAND_FAILED"));
        // 兼容旧的纯命令错误封套；业务快照即使失败也必须完整传回，不能丢失状态和清理信息。
        if(!value.has("state")&&!value.optString("error").isEmpty())throw new IOException(value.getString("error"));
        return value;
    }
}
