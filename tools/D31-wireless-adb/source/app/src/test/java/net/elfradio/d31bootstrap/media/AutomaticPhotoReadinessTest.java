package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutomaticPhotoReadinessTest {
    private JSONObject readiness(boolean permission,boolean appOp,int cameras)throws Exception {
        return new JSONObject().put("camera_runtime_permission",permission).put("camera_app_op_allowed",appOp)
                .put("camera_permission",permission&&appOp).put("media_cameras",cameras);
    }
    private void failure(JSONObject input,String error)throws Exception {
        JSONObject result=PhotoAlarmService.automaticReadinessFailure(input);
        assertEquals("failed",result.getString("state"));
        assertTrue(result.getBoolean("permanent"));
        assertEquals(error,result.getString("error"));
        assertFalse(result.has("captured_at"));
    }
    @Test public void runtimePermissionDeniedIsNotClaimedAsMissingHardware()throws Exception {
        failure(readiness(false,false,1),"AUTO_PHOTO_CAMERA_PERMISSION_DENIED");
    }
    @Test public void appOpDeniedIsDistinctFromRuntimePermission()throws Exception {
        failure(readiness(true,false,1),"AUTO_PHOTO_CAMERA_APPOP_DENIED");
    }
    @Test public void zeroCameraWithPermissionsIsNotEnumerated()throws Exception {
        failure(readiness(true,true,0),"AUTO_PHOTO_CAMERA_NOT_ENUMERATED");
    }
    @Test public void oneOrMultipleCamerasPassTheSamePreparationGate()throws Exception {
        assertNull(PhotoAlarmService.automaticReadinessFailure(readiness(true,true,1)));
        assertNull(PhotoAlarmService.automaticReadinessFailure(readiness(true,true,2)));
    }
    @Test public void allPermissionCountCombinationsPreserveOriginalAdmission()throws Exception {
        for(boolean permission:new boolean[]{false,true})for(boolean appOp:new boolean[]{false,true})for(int count:new int[]{0,1,2}) {
            JSONObject input=readiness(permission,appOp,count);
            boolean oldDenied=!input.getBoolean("camera_permission")||count<1;
            assertEquals(oldDenied,PhotoAlarmService.automaticReadinessFailure(input)!=null);
        }
    }
    @Test public void MissingEvidenceStillFailsClosed()throws Exception {
        failure(new JSONObject(),"AUTO_PHOTO_CAMERA_PERMISSION_DENIED");
    }
}
