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
        for(String field:new String[]{"trackName","mid"}){
            JSONObject o=offer();o.getJSONArray("tracks").getJSONObject(0).remove(field);
            try{new DownlinkTrackBinding().subscribeResult(o);fail();}catch(java.io.IOException expected){}
        }
        JSONObject duplicate=offer();duplicate.getJSONArray("tracks").put(duplicate.getJSONArray("tracks").getJSONObject(0));
        try{new DownlinkTrackBinding().subscribeResult(duplicate);fail();}catch(java.io.IOException expected){}
        JSONObject error=offer();error.getJSONArray("tracks").getJSONObject(0).put("errorCode","FAILED");
        try{new DownlinkTrackBinding().subscribeResult(error);fail();}catch(java.io.IOException expected){}
    }
    @Test public void optionalLocationAndEmptyErrorDoNotRejectSuccessfulSubscription()throws Exception{
        for(Object error:new Object[]{JSONObject.NULL,""}){
            JSONObject result=offer().put("errorCode",error),track=result.getJSONArray("tracks").getJSONObject(0);
            track.remove("location");track.put("errorCode",error);
            DownlinkTrackBinding binding=new DownlinkTrackBinding();binding.subscribeResult(result);
            assertTrue(binding.matches("1","RECV_ONLY","audio",true));
        }
        JSONObject result=offer();result.getJSONArray("tracks").getJSONObject(0).put("location",JSONObject.NULL);
        new DownlinkTrackBinding().subscribeResult(result);
    }
    @Test public void explicitWrongLocationAndNonStringMidAreRejected()throws Exception{
        for(String field:new String[]{"location","mid","errorCode"}){
            JSONObject result=offer();result.getJSONArray("tracks").getJSONObject(0).put(field,field.equals("location")?"local":1);
            try{new DownlinkTrackBinding().subscribeResult(result);fail();}catch(java.io.IOException expected){}
        }
    }
    @Test public void shapeNeverContainsSdpOrOpaqueSessionValues()throws Exception{
        JSONObject result=offer().put("sessionId","private-session").put("sessionDescription",new JSONObject().put("sdp","private-sdp"));
        result.getJSONArray("tracks").getJSONObject(0).put("sessionId","private-session");
        JSONObject shape=DownlinkTrackBinding.shape(result);
        assertEquals(1,shape.getInt("track_count"));assertTrue(shape.getBoolean("mid_valid"));
        assertFalse(shape.toString().contains("private"));
    }
    @Test public void duplicateSubscriptionCannotReplaceBinding()throws Exception{
        DownlinkTrackBinding b=new DownlinkTrackBinding();b.subscribeResult(offer());
        try{b.subscribeResult(offer());fail();}catch(java.io.IOException expected){}
        assertTrue(b.expected("1"));
    }
}
