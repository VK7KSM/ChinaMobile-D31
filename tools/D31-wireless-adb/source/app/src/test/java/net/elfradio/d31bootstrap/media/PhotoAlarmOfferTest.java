package net.elfradio.d31bootstrap.media;

import java.net.URI;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoAlarmOfferTest {
    static JSONObject offer(String mode)throws Exception{return new JSONObject().put("mode",mode).put("camera","front")
            .put("session_id","session-one").put("token","test-session-token-123456789")
            .put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=session-one").put("expires_at",140000);}
    static PhotoAlarmOffer parse(JSONObject value)throws Exception{return PhotoAlarmOffer.parse(value,new URI("https://v.elfradio.net"),100000);}
    @Test public void productionPhotoAndAlarmNeedNoRtc()throws Exception {
        assertEquals("photo",parse(offer("photo")).mode);assertEquals("alarm",parse(offer("alarm")).mode);
    }
    @Test public void rejectsExpiredWrongOriginAndOtherModes()throws Exception {
        for(JSONObject value:new JSONObject[]{offer("microphone"),offer("photo").put("expires_at",100000),offer("photo").put("expires_at",146000),
                offer("photo").put("url","wss://other.example/api/elfremote/media/device?session_id=session-one"),
                offer("photo").put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=other"),
                offer("photo").put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=session-one&token=a"),
                offer("photo").put("camera","side"),offer("photo").put("expires_at","140000")}) {
            try{parse(value);fail();}catch(java.io.IOException expected){}
        }
    }
    @Test public void photoParametersAndAckKeepOriginalSession()throws Exception {
        JSONObject capture=new JSONObject().put("state","completed").put("kind","photo").put("mime","image/jpeg")
                .put("report_id","session-one").put("captured_at",100001).put("bytes",24).put("sha256",new String(new char[64]).replace('\0','a'));
        JSONObject ack=new JSONObject().put("ok",true).put("bytes",24).put("sha256",capture.getString("sha256"));
        assertEquals("session-one",PhotoReport.acknowledged(capture,ack).getString("report_id"));
        for(JSONObject bad:new JSONObject[]{new JSONObject().put("ok",true),new JSONObject(ack.toString()).put("bytes",25),new JSONObject(ack.toString()).put("ok","true")}){
            try{PhotoReport.acknowledged(capture,bad);fail();}catch(java.io.IOException expected){}
        }
    }
}
