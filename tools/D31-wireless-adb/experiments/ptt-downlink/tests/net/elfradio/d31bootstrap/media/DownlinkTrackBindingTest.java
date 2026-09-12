package net.elfradio.d31bootstrap.media;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class DownlinkTrackBindingTest {
    static JSONObject offer()throws Exception{return new JSONObject("{tracks:[{location:'remote',trackName:'audio',mid:'1'}]}");}
    @Test public void actualReceivingTrackOnly()throws Exception{
        DownlinkTrackBinding b=new DownlinkTrackBinding();b.subscribeResult(offer());
        assertTrue(b.matches("1","RECV_ONLY","audio",true));assertTrue(b.matches("1","SEND_RECV","audio",true));
        assertFalse(b.matches("0","SEND_ONLY","audio",true));assertFalse(b.matches("1","SEND_ONLY","audio",true));
        assertFalse(b.matches("1","RECV_ONLY","audio",false));assertFalse(b.matches("1","RECV_ONLY","video",true));
        assertFalse(b.matches("1","null","audio",true));
    }
    @Test public void noBindingBeforeSubscribe()throws Exception{assertFalse(new DownlinkTrackBinding().matches("1","RECV_ONLY","audio",true));}
    @Test public void malformedTracksFailClosed()throws Exception{
        for(String field:new String[]{"location","trackName","mid"}){
            JSONObject o=offer();o.getJSONArray("tracks").getJSONObject(0).remove(field);
            try{new DownlinkTrackBinding().subscribeResult(o);fail();}catch(java.io.IOException expected){}
        }
        JSONObject duplicate=offer();duplicate.getJSONArray("tracks").put(duplicate.getJSONArray("tracks").getJSONObject(0));
        try{new DownlinkTrackBinding().subscribeResult(duplicate);fail();}catch(java.io.IOException expected){}
        JSONObject error=offer();error.getJSONArray("tracks").getJSONObject(0).put("errorCode","FAILED");
        try{new DownlinkTrackBinding().subscribeResult(error);fail();}catch(java.io.IOException expected){}
    }
    @Test public void duplicateSubscriptionCannotReplaceBinding()throws Exception{
        DownlinkTrackBinding b=new DownlinkTrackBinding();b.subscribeResult(offer());
        try{b.subscribeResult(offer());fail();}catch(java.io.IOException expected){}
        assertTrue(b.expected("1"));
    }
}
