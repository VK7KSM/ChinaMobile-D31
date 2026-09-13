package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoReportTest {
    JSONObject capture()throws Exception{return new JSONObject().put("state","completed").put("kind","photo").put("mime","image/jpeg")
            .put("report_id","session-a").put("captured_at",123456).put("bytes",12).put("sha256",String.format("%064d",0));}
    @Test public void acknowledgedImageUsesOriginalReportAndTime()throws Exception{
        JSONObject source=capture();JSONObject result=PhotoReport.acknowledged(source,new JSONObject().put("ok",true).put("sha256",source.getString("sha256")).put("bytes",12));
        assertEquals("result",result.getString("type"));assertEquals("session-a",result.getString("report_id"));assertEquals(123456,result.getLong("captured_at"));assertFalse(result.has("path"));
    }
    @Test public void noSuccessForUnknownReceipt()throws Exception{
        MediaCaptureTest.rejects("ACK_UNKNOWN",new MediaCaptureTest.Operation(){public void run()throws Exception{PhotoReport.acknowledged(capture(),null);}});
        MediaCaptureTest.rejects("ACK_UNKNOWN",new MediaCaptureTest.Operation(){public void run()throws Exception{PhotoReport.acknowledged(capture(),new JSONObject().put("ok",true));}});
    }
    @Test public void localAudioCannotClaimPhotoUpload()throws Exception{
        MediaCaptureTest.rejects("NOT_READY",new MediaCaptureTest.Operation(){public void run()throws Exception{PhotoReport.uploadParameters(capture().put("kind","audio"));}});
    }
}
