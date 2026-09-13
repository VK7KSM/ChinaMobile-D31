package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteSipConfigTest {
    private JSONObject params(String target,String slot)throws Exception{return new JSONObject().put("target",target).put("account_id",slot)
        .put("server","sip.example.invalid").put("username","101").put("password","fixture-secret").put("transport","tls").put("port",5061);}
    @Test public void fourLinesAndSms()throws Exception{
        for(int n=1;n<=4;n++)assertEquals("line-"+n,RemoteSipConfig.validate(params("nexui","line-"+n)).getString("account_id"));
        assertEquals("*",RemoteSipConfig.validate(params("quik","default").put("realm","*")).getString("realm"));
    }
    @Test public void rejectsUnsupportedFieldsAndInjection()throws Exception{
        for(JSONObject p:new JSONObject[]{params("nexui","line-5"),params("nexui","line-1").put("realm","*"),params("quik","default").put("auth_username","other"),params("quik","default").put("server","x;id"),params("quik","default").put("password","line\nbreak")}){
            try{RemoteSipConfig.validate(p);fail();}catch(java.io.IOException expected){}
        }
    }
    @Test public void realRegistrationStates(){assertEquals("registered",RemoteSipConfig.registration("SUCCESSFULLY"));assertEquals("unregistered",RemoteSipConfig.registration("DISABLED"));assertEquals("unknown",RemoteSipConfig.registration("unexpected"));}
    @Test public void sqliteTextNumbersAndKeyOrderDoNotBreakReadback()throws Exception{
        JSONObject requested=new JSONObject().put("port",5061).put("active",1).put("optional",JSONObject.NULL);
        JSONObject stored=new JSONObject().put("optional",JSONObject.NULL).put("active","1").put("port","5061");
        assertTrue(RemoteSip.same(new JSONObject().put("1",requested),new JSONObject().put("1",stored)));
        assertEquals(RemoteSip.configHash(requested),RemoteSip.configHash(stored));
        stored.put("port","5060");assertFalse(RemoteSip.same(requested,stored));
        assertNotEquals(RemoteSip.configHash(requested),RemoteSip.configHash(stored));
    }
}
