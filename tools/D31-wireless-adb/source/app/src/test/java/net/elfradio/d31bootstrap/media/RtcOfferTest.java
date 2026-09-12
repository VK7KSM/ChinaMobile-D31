package net.elfradio.d31bootstrap.media;

import java.net.URI;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RtcOfferTest {
    static JSONObject offer()throws Exception{return new JSONObject().put("mode","microphone").put("session_id","session-1")
            .put("token","abcdefghijklmnop").put("expires_at",140000)
            .put("url","wss://control.example/api/elfremote/media/device?session_id=session-1");}
    static RtcOffer parse(JSONObject value)throws Exception{return RtcOffer.parse(value,new URI("https://control.example"),100000);}
    @Test public void acceptsExistingMicrophoneOffer()throws Exception{assertEquals("session-1",parse(offer()).id);}
    @Test public void rejectsUnimplementedModes()throws Exception{
        for(final String mode:new String[]{"ptt","call","photo","alarm","record_audio"})
            MediaCaptureTest.rejects("MODE",new MediaCaptureTest.Operation(){public void run()throws Exception{parse(offer().put("mode",mode));}});
    }
    @Test public void videoKeepsModeAndRequestedCameraWithoutRelaxingEndpointChecks()throws Exception{
        RtcOffer value=parse(offer().put("mode","video").put("camera","back"));
        assertEquals("video",value.mode);assertEquals("back",value.camera);
        assertEquals("front",parse(offer().put("mode","video")).camera);
        MediaCaptureTest.rejects("CAMERA",()->parse(offer().put("mode","video").put("camera","unknown")));
    }
    @Test public void rejectsEndpointAndSessionConfusion()throws Exception{
        for(final String url:new String[]{"ws://control.example/api/elfremote/media/device?session_id=session-1",
                "wss://other.example/api/elfremote/media/device?session_id=session-1",
                "wss://control.example:444/api/elfremote/media/device?session_id=session-1",
                "wss://user@control.example/api/elfremote/media/device?session_id=session-1",
                "wss://control.example/api/elfremote/media/device?session_id=session-2",
                "wss://control.example/api/elfremote/media/device?session_id=session-1&extra=1",
                "wss://control.example/api/elfremote/media/device?session_id=session-1#fragment"})
            MediaCaptureTest.rejects("ENDPOINT",new MediaCaptureTest.Operation(){public void run()throws Exception{parse(offer().put("url",url));}});
    }
    @Test public void rejectsExpiryAndHeaderInjection()throws Exception{
        for(final long expiry:new long[]{0,100000,145001})MediaCaptureTest.rejects("OFFER",new MediaCaptureTest.Operation(){public void run()throws Exception{parse(offer().put("expires_at",expiry));}});
        MediaCaptureTest.rejects("OFFER",new MediaCaptureTest.Operation(){public void run()throws Exception{parse(offer().put("token","abcdefghijklmnop\r\nInjected: true"));}});
    }
    @Test public void tlsMissingSessionFailsBeforeWebSocketAuthentication()throws Exception{
        MediaCaptureTest.rejects("TLS_HOST_REJECTED",new MediaCaptureTest.Operation(){public void run()throws Exception{
            MediaWebSocket.verifyPeer("control.example",null,javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier());
        }});
    }
}
