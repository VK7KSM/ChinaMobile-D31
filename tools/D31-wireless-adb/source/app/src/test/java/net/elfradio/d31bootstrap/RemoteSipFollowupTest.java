package net.elfradio.d31bootstrap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteSipFollowupTest {
    private JSONObject receipt()throws Exception{return new JSONObject().put("state","success").put("task_id","fixture")
        .put("result",new JSONObject().put("applied",true).put("target","nexui").put("account_id","line-1"));}
    @Test public void boundedAndNoIdlePolling()throws Exception{
        RemoteSipFollowup f=new RemoteSipFollowup();assertFalse(f.due(100000));f.completed(receipt(),1000);
        assertTrue(f.due(1000));assertFalse(f.due(1001));
        for(long t:new long[]{6000,16000,31000,61000,121000})assertTrue(f.due(t));
        assertFalse(f.due(999999));
    }
    @Test public void onlyMatchingRegisteredTaskEndsFollowup()throws Exception{
        RemoteSipFollowup f=new RemoteSipFollowup();f.completed(receipt(),1000);
        JSONObject r=new JSONObject().put("target","nexui").put("account_id","line-1").put("state","registered").put("config_task_id","old");
        f.observed(new JSONObject().put("sip_registrations",new JSONArray().put(r)));assertTrue(f.due(1000));
        r.put("config_task_id","fixture");f.observed(new JSONObject().put("sip_registrations",new JSONArray().put(r)));assertFalse(f.due(100000));
    }
}
