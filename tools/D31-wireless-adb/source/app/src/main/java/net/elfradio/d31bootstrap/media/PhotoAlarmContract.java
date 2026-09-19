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
    static final String SHARE_LINK_SHOW=net.elfradio.d31bootstrap.ShareLinkPayload.OP_SHOW,
            SHARE_LINK_QUERY=net.elfradio.d31bootstrap.ShareLinkPayload.OP_QUERY,
            SHARE_LINK_DISMISS=net.elfradio.d31bootstrap.ShareLinkPayload.OP_DISMISS;
    /** 二维码只显示链接本身；口令一类的东西不进设备，核心侧已拦一道，这里再拦一道。 */
    private static final String[] SHARE_LINK_FORBIDDEN={"password","passcode","pin","secret","token","credential"};
    static final int SHARE_LINK_TEXT_MAX=512;

    /**
     * 二维码显示命令的校验。窗口显示时长以分钟计，远超本桥6秒的同步时限，
     * 所以 show 只负责拉起并立即返回，结果靠 query 按轮次取，不在桥上等。
     */
    private static JSONObject shareLink(JSONObject x,String op)throws Exception {
        if(!x.optString("session").matches("[A-Za-z0-9_-]{1,96}"))throw new IOException("SHARE_LINK_SESSION_INVALID");
        if(!SHARE_LINK_SHOW.equals(op))return x;
        for(String forbidden:SHARE_LINK_FORBIDDEN)if(x.has(forbidden))throw new IOException("SHARE_LINK_CREDENTIAL_FORBIDDEN");
        String url=x.optString("url"),qrText=x.optString("qr_text");
        if(url.isEmpty()||url.length()>SHARE_LINK_TEXT_MAX||!url.startsWith("https://"))throw new IOException("SHARE_LINK_URL_INVALID");
        if(qrText.isEmpty()||qrText.length()>SHARE_LINK_TEXT_MAX)throw new IOException("SHARE_LINK_QR_TEXT_INVALID");
        if(x.optLong("display_ms")<=0)throw new IOException("SHARE_LINK_DISPLAY_MS_INVALID");
        return x;
    }
    static JSONObject command(String raw)throws Exception {
        if(raw==null||raw.length()>16384)throw new IOException("VISUAL_COMMAND_SIZE");
        JSONObject x=new JSONObject(raw);String op=x.optString("operation");
        boolean automatic="auto_photo".equals(op)||"auto_finish".equals(op)||"auto_cancel".equals(op);
        boolean shareLink=SHARE_LINK_SHOW.equals(op)||SHARE_LINK_QUERY.equals(op)||SHARE_LINK_DISMISS.equals(op);
        if(!automatic&&!shareLink&&!"prepare".equals(op)&&!"start".equals(op)&&!"query".equals(op)&&!"stop".equals(op))throw new IOException("VISUAL_COMMAND_INVALID");
        if(shareLink)return shareLink(x,op);
        if((automatic||"prepare".equals(op)||"start".equals(op))&&!x.optString("apk_sha256").matches("[a-f0-9]{64}"))throw new IOException("VISUAL_APK_INVALID");
        if(automatic){
            if(x.has("offer"))throw new IOException("AUTO_PHOTO_OFFER_FORBIDDEN");
            JSONObject job=x.getJSONObject("job");
            if(!job.optString("device_id").matches("[A-Za-z0-9_-]{1,96}")||!job.optString("report_id").matches("[A-Za-z0-9_-]{1,96}")
                    ||job.optInt("attempt",-1)<0||job.optInt("attempt",-1)>3||!(job.opt("critical") instanceof Boolean)
                    ||job.optLong("sampled_at")<=0||job.optLong("expires_at")<=job.optLong("sampled_at"))throw new IOException("AUTO_PHOTO_JOB_INVALID");
        }
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
