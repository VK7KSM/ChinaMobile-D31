package net.elfradio.d31bootstrap;

import org.json.JSONObject;

/** 旧报告仍按原编号重放，但只能用当前核心生成的报告证明本次上线。 */
final class RemoteReportReceipt {
    static JSONObject wire(JSONObject saved) throws Exception {
        JSONObject result = new JSONObject(saved.toString());
        result.remove("_notice_version");
        result.remove("_core_instance");
        return result;
    }

    static boolean current(JSONObject body, JSONObject reply, String version, String instance) {
        String id = body.optString("report_id");
        return !id.isEmpty() && id.equals(reply.optString("report_id"))
                && !instance.isEmpty() && instance.equals(body.optString("_core_instance"))
                && version.equals(body.optString("app_version"));
    }
}
