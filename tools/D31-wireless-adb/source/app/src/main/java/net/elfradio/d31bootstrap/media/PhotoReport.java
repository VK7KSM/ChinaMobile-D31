package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

/** 对接既有report-photo回执；只有服务端确认原件摘要和长度才生成WebSocket结果。 */
public final class PhotoReport {
    private PhotoReport(){}
    public static JSONObject uploadParameters(JSONObject capture)throws Exception {
        if(!"completed".equals(capture.optString("state"))||!"photo".equals(capture.optString("kind"))
                ||!"image/jpeg".equals(capture.optString("mime"))||!capture.optString("report_id").matches("[A-Za-z0-9_-]{1,96}")
                ||capture.optLong("captured_at")<=0)throw new IOException("PHOTO_NOT_READY");
        return new JSONObject().put("report_id",capture.getString("report_id")).put("captured_at",capture.getLong("captured_at"));
    }
    public static JSONObject acknowledged(JSONObject capture,JSONObject reply)throws Exception {
        JSONObject params=uploadParameters(capture);
        if(reply==null||!Boolean.TRUE.equals(reply.opt("ok"))||capture.optLong("bytes")<=0
                ||!capture.optString("sha256").matches("[a-f0-9]{64}")||!capture.getString("sha256").equals(reply.optString("sha256"))
                ||capture.getLong("bytes")!=reply.optLong("bytes",-1))throw new IOException("PHOTO_ACK_UNKNOWN_OR_MISMATCH");
        return params.put("type","result").put("message","照片已保存");
    }
}
