package net.elfradio.d31bootstrap.media;

import java.io.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import org.json.JSONArray;
import static org.junit.Assert.*;

public class PhotoAlarmCaptureTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void soleRearCameraAcceptsDefaultFrontButMultipleCamerasAreStrict() {
        assertEquals(0,AndroidMediaDevice.selectCamera(new int[]{0},"front"));
        assertEquals(0,AndroidMediaDevice.selectCamera(new int[]{1},"back"));
        assertEquals(1,AndroidMediaDevice.selectCamera(new int[]{0,1},"front"));
        assertEquals(-1,AndroidMediaDevice.selectCamera(new int[]{0,0},"front"));
        assertEquals(-1,AndroidMediaDevice.selectCamera(new int[0],"front"));
    }
    @Test public void unsupportedOrRejectedShutterMuteFailsBeforeCapture()throws Exception {
        AndroidMediaDevice.requireSilentShutter(true,true);
        for(boolean[] result:new boolean[][]{{false,false},{false,true},{true,false}}){
            try{AndroidMediaDevice.requireSilentShutter(result[0],result[1]);fail();}catch(IOException expected){assertEquals("MEDIA_SILENT_SHUTTER_UNAVAILABLE",expected.getMessage());}
        }
    }
    @Test public void photoHasNoAudioGuardDependencyButRetainsCameraLease()throws Exception {
        MediaCapture.Device camera=(request,file,cancel,deadline,guard)->{
            try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});}
            return new MediaCapture.Captured(100001,100001,"camera:0:front","image/jpeg");
        };
        MediaCapture capture=new MediaCapture(temp.newFolder(),camera,()->{},new PhotoAlarmSessionTest.Clock());
        JSONObject result=capture.photo(CaptureRequest.photo("session-one","session-one","front",120000),new Cancellation());
        assertEquals("completed",result.getString("state"));assertEquals("session-one",result.getString("report_id"));
        assertFalse(result.getBoolean("uploaded"));
    }
    @Test public void ownedAlarmExemptsOnlyLocalAlarmStream()throws Exception {
        JSONObject raw=new JSONObject().put("audio",new JSONObject().put("streams",new JSONArray()
                .put(new JSONObject().put("stream",3).put("active",true).put("remote_active",false))
                .put(new JSONObject().put("stream",4).put("active",true).put("remote_active",true)))
                .put("mode",2).put("focus_gain",1));
        JSONArray checked=PhotoAlarmBackend.alarmObservation(raw,true).getJSONObject("audio").getJSONArray("streams");
        assertTrue(checked.getJSONObject(0).getBoolean("active"));assertFalse(checked.getJSONObject(1).getBoolean("active"));
        assertTrue(checked.getJSONObject(1).getBoolean("remote_active"));
        assertEquals(raw.toString(),PhotoAlarmBackend.alarmObservation(raw,false).toString());
        assertTrue(raw.getJSONObject("audio").getJSONArray("streams").getJSONObject(1).getBoolean("active"));
    }
}
