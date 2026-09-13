package dev.octoshrimpy.quik.feature.phone;

import android.app.Application;
import android.os.Bundle;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class SipRemoteProviderTest {
    private JSONObject params()throws Exception{return new JSONObject().put("target","quik").put("account_id","default")
        .put("server","sip.example.invalid").put("username","102").put("password","fixture-secret").put("transport","tls").put("port",5061);}
    private Bundle request(String id,JSONObject params)throws Exception{Bundle b=new Bundle();b.putString("request",new JSONObject().put("task_id",id).put("params",params).toString());return b;}
    @Test public void configurationIsPrivateAndRetryIsIdempotent()throws Exception{
        SipRemoteProvider provider=Robolectric.buildContentProvider(SipRemoteProvider.class).create().get();
        Bundle r=provider.call("configure",null,request("fixture-1",params()));assertTrue(r.getBoolean("applied"));assertFalse(r.containsKey("password"));
        assertEquals("102",r.getString("username"));assertEquals("*",r.getString("realm"));
        assertTrue(provider.call("configure",null,request("fixture-1",params())).getBoolean("applied"));
        assertFalse(provider.call("configure",null,request("fixture-1",params().put("username","103"))).getBoolean("ok"));
        assertEquals("102",provider.call("status",null,null).getString("username"));
    }
    @Test public void unsupportedTargetAuthAndLineDoNotChangeConfiguration()throws Exception{
        SipRemoteProvider provider=Robolectric.buildContentProvider(SipRemoteProvider.class).create().get();
        for(JSONObject p:new JSONObject[]{params().put("target","nexui"),params().put("account_id","line-1"),params().put("auth_username","different")})
            assertFalse(provider.call("configure",null,request("bad",p)).getBoolean("ok"));
        assertFalse(provider.call("status",null,null).getBoolean("enabled"));
    }
    @Test public void localEditDoesNotClaimAnOldRemoteConfiguration()throws Exception{
        SipRemoteProvider provider=Robolectric.buildContentProvider(SipRemoteProvider.class).create().get();
        assertTrue(provider.call("configure",null,request("fixture-local",params())).getBoolean("applied"));
        assertEquals("fixture-local",provider.call("status",null,null).getString("config_task_id"));
        new SipConfigStore(provider.getContext()).save(true,"other.invalid",5061,"103","other-secret","*",SipConfigStore.Transport.TLS);
        assertEquals("",provider.call("status",null,null).getString("config_task_id"));
    }
}
