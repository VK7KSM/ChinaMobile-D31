package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteMediaReportTest {
    @Test public void cameraPermissionRefreshWithdrawsVideoAndRestoresItWithoutDroppingAudioPrepare()throws Exception{
        JSONObject report=new JSONObject();
        JSONObject rtc=new JSONObject().put("managed_media_modes",new JSONArray("['microphone','ptt','call','video']"))
                .put("managed_media_prepare_v1",true);
        JSONObject visual=new JSONObject().put("media_cameras",1).put("camera_permission",true)
                .put("managed_media_modes",new JSONArray("['photo','alarm']"));
        RemoteMediaReport.merge(report,rtc,visual);
        assertEquals("[\"microphone\",\"ptt\",\"call\",\"photo\",\"alarm\",\"video\"]",report.getJSONArray("managed_media_modes").toString());
        visual.put("camera_permission",false).put("managed_media_modes",new JSONArray("['alarm']"));
        RemoteMediaReport.merge(report,rtc,visual);
        assertEquals(1,report.getInt("media_cameras"));
        assertEquals("[\"microphone\",\"ptt\",\"call\",\"alarm\"]",report.getJSONArray("managed_media_modes").toString());
        assertTrue(report.getBoolean("managed_media_prepare_v1"));
        visual.put("camera_permission",true).put("managed_media_modes",new JSONArray("['photo','alarm']"));
        RemoteMediaReport.merge(report,rtc,visual);
        assertEquals("[\"microphone\",\"ptt\",\"call\",\"photo\",\"alarm\",\"video\"]",report.getJSONArray("managed_media_modes").toString());
    }
    @Test public void enumeratedCameraWithoutPhotoModeDoesNotAdvertiseVideo()throws Exception{
        JSONObject report=new JSONObject();
        JSONObject rtc=new JSONObject().put("managed_media_modes",new JSONArray("['video']"));
        for(String modes:new String[]{"[]","['alarm']","['video']"}){
            RemoteMediaReport.merge(report,rtc,new JSONObject().put("media_cameras",1)
                    .put("camera_permission",true).put("managed_media_modes",new JSONArray(modes)));
            assertFalse(report.getJSONArray("managed_media_modes").toString().contains("video"));
        }
        RemoteMediaReport.merge(report,rtc,new JSONObject().put("media_cameras",0)
                .put("managed_media_modes",new JSONArray("['photo']")));
        assertEquals("[\"photo\"]",report.getJSONArray("managed_media_modes").toString());
    }
    @Test public void preparedConnectionKeepsAudioWhenCameraIsNotEnumerated()throws Exception{
        JSONObject report=new JSONObject();
        JSONObject rtc=new JSONObject().put("managed_media_modes",new JSONArray("['microphone','ptt','call','video']"))
                .put("managed_media_prepare_v1",true);
        JSONObject visual=new JSONObject().put("managed_media_modes",new JSONArray("['photo','alarm']")).put("media_cameras",1);
        RemoteMediaReport.merge(report,rtc,visual);assertTrue(report.getBoolean("managed_media_prepare_v1"));
        visual.put("media_cameras",0).put("managed_media_modes",new JSONArray("['alarm']"));
        RemoteMediaReport.merge(report,rtc,visual);assertTrue(report.getBoolean("managed_media_prepare_v1"));
        assertEquals("[\"microphone\",\"ptt\",\"call\",\"alarm\"]",report.getJSONArray("managed_media_modes").toString());
        visual.put("media_cameras",1);rtc.put("managed_media_prepare_v1","true");
        RemoteMediaReport.merge(report,rtc,visual);assertFalse(report.getBoolean("managed_media_prepare_v1"));
    }
    @Test public void prepareStillRequiresBothAudioDirectionsAndExplicitSupport()throws Exception{
        for(String modes:new String[]{"[]","['ptt']","['microphone']","['microphone','call']"}){
            JSONObject report=new JSONObject(),rtc=new JSONObject().put("managed_media_modes",new JSONArray(modes))
                    .put("managed_media_prepare_v1",true);
            RemoteMediaReport.merge(report,rtc,new JSONObject().put("managed_media_modes",new JSONArray("['alarm']")));
            assertFalse(report.getBoolean("managed_media_prepare_v1"));
        }
        JSONObject report=new JSONObject(),rtc=new JSONObject().put("managed_media_modes",new JSONArray("['microphone','ptt','call']"));
        RemoteMediaReport.merge(report,rtc,null);assertFalse(report.getBoolean("managed_media_prepare_v1"));
    }
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
        assertEquals("[\"microphone\",\"ptt\",\"call\"]",report.getJSONArray("managed_media_modes").toString());
        RemoteMediaReport.merge(report,null,new JSONObject().put("media_cameras",1).put("managed_media_modes",new JSONArray("[\"video\"]")));
        assertEquals(0,report.getJSONArray("managed_media_modes").length());
    }
    @Test public void oldCallCapabilityOnlyComesFromAudioSessionsWithoutPrepareClaim()throws Exception{
        JSONObject report=new JSONObject();
        RemoteMediaReport.merge(report,new JSONObject().put("managed_media_modes",new JSONArray("['call','call','managed_media_prepare_v1']"))
                .put("managed_media_prepare_v1",true).put("token","private-value"),null);
        assertEquals("[\"call\"]",report.getJSONArray("managed_media_modes").toString());assertTrue(report.getBoolean("managed_media"));
        assertFalse(report.getBoolean("managed_media_prepare_v1"));assertFalse(report.toString().contains("private-value"));
        RemoteMediaReport.merge(report,null,new JSONObject().put("managed_media_modes",new JSONArray("['call']")));
        assertFalse(report.getBoolean("managed_media"));assertEquals(0,report.getJSONArray("managed_media_modes").length());
    }
}
