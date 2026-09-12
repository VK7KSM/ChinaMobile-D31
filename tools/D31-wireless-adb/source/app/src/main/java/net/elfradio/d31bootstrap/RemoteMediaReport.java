package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 只公开已接入模式和相机数量，私有会话状态不进入设备能力报告。 */
final class RemoteMediaReport {
    static void merge(JSONObject report, JSONObject microphone, JSONObject visual) throws JSONException {
        JSONArray modes = new JSONArray();
        if (contains(microphone, "microphone")) modes.put("microphone");
        if (contains(visual, "photo")) modes.put("photo");
        if (contains(visual, "alarm")) modes.put("alarm");
        if (contains(microphone, "video") && visual != null && visual.optInt("media_cameras", 0) > 0) modes.put("video");
        report.put("managed_media", modes.length() > 0).put("managed_media_modes", modes)
                .put("media_cameras", visual == null ? 0 : Math.max(0, visual.optInt("media_cameras", 0)));
    }

    private static boolean contains(JSONObject state, String mode) {
        JSONArray values = state == null ? null : state.optJSONArray("managed_media_modes");
        if (values != null) for (int i = 0; i < values.length(); i++)
            if (mode.equals(values.optString(i))) return true;
        return false;
    }
}
