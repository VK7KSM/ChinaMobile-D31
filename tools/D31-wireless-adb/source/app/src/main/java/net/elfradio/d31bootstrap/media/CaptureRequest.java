package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

public final class CaptureRequest {
    public final String id, reportId, kind, camera;
    public final int durationMs;
    public final long expiresAt;

    private CaptureRequest(String id, String reportId, String kind, String camera, int durationMs, long expiresAt) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,96}") || reportId == null || !reportId.matches("[A-Za-z0-9_-]{1,96}"))
            throw new IOException("MEDIA_INVALID_ID");
        if (!"front".equals(camera) && !"back".equals(camera)) throw new IOException("MEDIA_INVALID_CAMERA");
        if ("audio".equals(kind) && (durationMs < 1000 || durationMs > 60000)) throw new IOException("MEDIA_INVALID_DURATION");
        if (expiresAt <= 0) throw new IOException("MEDIA_INVALID_EXPIRY");
        this.id=id; this.reportId=reportId; this.kind=kind; this.camera=camera; this.durationMs=durationMs; this.expiresAt=expiresAt;
    }
    public static CaptureRequest photo(String id, String reportId, String camera, long expiresAt) throws IOException {
        return new CaptureRequest(id,reportId,"photo",camera,0,expiresAt);
    }
    /** 现有media_session的photo模式，不为其它WebRTC模式产生虚假的ready。 */
    public static CaptureRequest photoOffer(JSONObject offer) throws Exception {
        if (!"photo".equals(offer.optString("mode"))) throw new IOException("MEDIA_STREAM_NOT_IMPLEMENTED");
        String id=offer.getString("session_id");
        return photo(id,id,offer.optString("camera","front"),offer.getLong("expires_at"));
    }
    /** 本地有界录音供现有root_exec接线；这不是新增Web任务类型。 */
    public static CaptureRequest audio(String taskId, String reportId, int durationMs, long expiresAt) throws IOException {
        return new CaptureRequest(taskId,reportId,"audio","front",durationMs,expiresAt);
    }
    JSONObject identity() throws Exception {
        return new JSONObject().put("id",id).put("report_id",reportId).put("kind",kind).put("camera",camera)
                .put("duration_ms",durationMs).put("expires_at",expiresAt);
    }
}
