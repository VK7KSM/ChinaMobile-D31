package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteMediaReportTest {
    @Test public void unavailableModesRemainExplicitlyEmpty() throws Exception {
        JSONObject report = new JSONObject().put("managed_media", true);
        RemoteMediaReport.merge(report, null, null);
        assertFalse(report.getBoolean("managed_media"));
        assertEquals(0, report.getJSONArray("managed_media_modes").length());
        assertEquals(0, report.getInt("media_cameras"));
    }

    @Test public void onlyOwnedImplementedModesArePublishedWithoutPrivateState() throws Exception {
        JSONObject microphone = new JSONObject().put("managed_media_modes", new JSONArray("[\"microphone\",\"video\"]"))
                .put("session_id", "private").put("token", "private");
        JSONObject visual = new JSONObject().put("managed_media_modes", new JSONArray("[\"photo\",\"alarm\",\"microphone\"]"))
                .put("media_cameras", 1);
        JSONObject report = new JSONObject();
        RemoteMediaReport.merge(report, microphone, visual);
        assertEquals("[\"microphone\",\"photo\",\"alarm\",\"video\"]", report.getJSONArray("managed_media_modes").toString());
        assertEquals(1, report.getInt("media_cameras"));
        assertFalse(report.has("session_id"));
        assertFalse(report.has("token"));
        RemoteMediaReport.merge(report, null, new JSONObject().put("media_cameras", 1));
        assertFalse(report.getBoolean("managed_media"));
        assertEquals(1, report.getInt("media_cameras"));
    }
    @Test public void videoNeedsActualCameraCapabilityAndCannotComeFromVisualService()throws Exception{
        JSONObject report=new JSONObject();
        JSONObject rtc=new JSONObject().put("managed_media_modes",new JSONArray("[\"microphone\",\"video\",\"call\",\"ptt\"]"));
        RemoteMediaReport.merge(report,rtc,new JSONObject().put("media_cameras",0));
        assertEquals("[\"microphone\"]",report.getJSONArray("managed_media_modes").toString());
        RemoteMediaReport.merge(report,null,new JSONObject().put("media_cameras",1).put("managed_media_modes",new JSONArray("[\"video\"]")));
        assertEquals(0,report.getJSONArray("managed_media_modes").length());
    }
}
