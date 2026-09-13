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
        for(final String mode:new String[]{"managed_media_prepare_v1","photo","alarm","record_audio"})
            MediaCaptureTest.rejects("MODE",new MediaCaptureTest.Operation(){public void run()throws Exception{parse(offer().put("mode",mode));}});
    }
    @Test public void pttUsesSameEndpointExpiryAndTokenContract()throws Exception{
        assertEquals("ptt",parse(offer().put("mode","ptt")).mode);
        MediaCaptureTest.rejects("ENDPOINT",()->parse(offer().put("mode","ptt")
                .put("url","wss://other.example/api/elfremote/media/device?session_id=session-1")));
        MediaCaptureTest.rejects("OFFER",()->parse(offer().put("mode","ptt").put("expires_at",100000)));
        MediaCaptureTest.rejects("OFFER",()->parse(offer().put("mode","ptt").put("token","")));
    }
    @Test public void preparedConnectionKeepsEndpointAndCameraChecks()throws Exception{
        JSONObject identity=new JSONObject().put("device_id","test-device").put("token","test-upload-token");
        JSONObject raw=offer().put("mode","prepare").put("_credentials",identity);
        RtcOffer value=parse(raw);identity.put("token","changed");
        assertEquals("prepare",value.mode);assertEquals("test-upload-token",value.uploadIdentity().getString("token"));
        value.uploadIdentity().remove("token");assertTrue(value.uploadIdentity().has("token"));
        MediaCaptureTest.rejects("CAMERA",()->parse(raw.put("camera","invalid")));
        MediaCaptureTest.rejects("ENDPOINT",()->parse(offer().put("mode","prepare").put("url","wss://other.example/api/elfremote/media/device?session_id=session-1")));
    }
    @Test public void callUsesUnchangedOfferAuthenticationAndExpiryContract()throws Exception{
        assertEquals("call",parse(offer().put("mode","call")).mode);
        for(String endpoint:new String[]{"ws://control.example/api/elfremote/media/device?session_id=session-1",
                "wss://foreign.example/api/elfremote/media/device?session_id=session-1",
                "wss://control.example/api/elfremote/media/device?session_id=other"})
            MediaCaptureTest.rejects("ENDPOINT",()->parse(offer().put("mode","call").put("url",endpoint)));
        for(long expiry:new long[]{100000,145001})MediaCaptureTest.rejects("OFFER",()->parse(offer().put("mode","call").put("expires_at",expiry)));
        MediaCaptureTest.rejects("OFFER",()->parse(offer().put("mode","call").put("token","private\r\nvalue")));
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
