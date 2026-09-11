package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;

/** 只建立明确的报告/实际采集关联，不拍摄、不上传、不为设备录音虚构浏览器媒体协议。 */
public final class MediaAssociation {
    public static final int MAX_PHOTO_BYTES = 256 * 1024;

    /** 只检查已确认报告的网络规则；24小时时限、JPEG及服务器许可仍由实际上传入口核验。 */
    public static String automaticPhotoEligibility(JSONObject frozenReport, JSONObject reply) throws Exception {
        String id = TelemetryJson.id(frozenReport.getString("report_id"));
        if (reply == null || !Boolean.TRUE.equals(reply.opt("ok")) || !id.equals(reply.optString("report_id"))) return "REPORT_NOT_ACKNOWLEDGED";
        if ("wifi".equals(frozenReport.optString("network"))) return "ELIGIBLE";
        JSONObject event = frozenReport.optJSONObject("report_event");
        if (event != null && "low_battery".equals(event.optString("type")) && event.opt("level") instanceof Number
                && (event.getDouble("level") == 0 || event.getDouble("level") == 1)) {
            JSONArray thresholds = event.optJSONArray("thresholds");
            if (thresholds != null) for (int i = 0; i < thresholds.length(); i++)
                if (thresholds.opt(i) instanceof Number && thresholds.getDouble(i) == 2) return "ELIGIBLE";
        }
        return "SERVER_NETWORK_POLICY_REJECTED";
    }
    /** 对应report-photo的现有查询/校验字段；设备身份与Bearer凭据由既有上传入口另行添加。 */
    public static JSONObject photo(String reportId, long capturedAtMs, long bytes, String sha256) throws Exception {
        TelemetryJson.id(reportId);
        if (capturedAtMs <= 0 || bytes < 4 || bytes > MAX_PHOTO_BYTES || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("INVALID_PHOTO_EVIDENCE");
        return new JSONObject().put("report_id", reportId).put("captured_at", capturedAtMs).put("bytes", bytes).put("sha256", sha256);
    }
    /** 本地音视频索引；当前Web录制接口不接收设备精确report_id，此索引不能直接当上传请求。 */
    public static JSONObject recording(String id, String type, String reportId, String taskId,
                                       long startedAtMs, long endedAtMs) throws Exception {
        TelemetryJson.id(id);
        if (reportId != null) TelemetryJson.id(reportId);
        if (taskId != null) TelemetryJson.id(taskId);
        if (!("audio".equals(type) || "video".equals(type)) || startedAtMs <= 0 || endedAtMs < startedAtMs)
            throw new IllegalArgumentException("INVALID_MEDIA_TIMES");
        return new JSONObject().put("schemaVersion", 1).put("id", id).put("type", type)
                .put("report_id", reportId == null ? JSONObject.NULL : reportId).put("task_id", taskId == null ? JSONObject.NULL : taskId)
                .put("captured_at", startedAtMs).put("ended_at", endedAtMs).put("duration_ms", endedAtMs - startedAtMs)
                .put("time_source", "device").put("association", reportId == null ? "UNLINKED" : "EXPLICIT_REPORT_ID")
                .put("transport_state", "NOT_INTEGRATED");
    }
    private MediaAssociation() { }
}
