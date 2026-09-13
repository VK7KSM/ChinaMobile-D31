package net.elfradio.d31bootstrap;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdbSessionsTest {
    private JSONObject request()throws Exception {
        String id="12345678-1234-1234-1234-123456789abc";
        return new JSONObject().put("session_id",id).put("token",String.join("",java.util.Collections.nCopies(64,"a")))
                .put("expires_at",60000).put("url","wss://v.elfradio.net/api/elfremote/adb/device?session_id="+id);
    }
    @Test public void onlyCurrentControlServerAndFreshSessionAreAccepted()throws Exception {
        JSONObject good=request();assertEquals("v.elfradio.net",AdbSessions.validate(good,1000).getHost());
        for(String url:new String[]{good.getString("url").replace("wss:","ws:"),good.getString("url").replace("v.elfradio.net","example.test"),good.getString("url")+"&token=extra",good.getString("url")+"#fragment"}){
            try{AdbSessions.validate(request().put("url",url),1000);fail();}catch(java.io.IOException expected){}
        }
        try{AdbSessions.validate(good,60000);fail();}catch(java.io.IOException expected){}
        try{AdbSessions.validate(request().put("token","invalid"),1000);fail();}catch(java.io.IOException expected){}
    }
}
