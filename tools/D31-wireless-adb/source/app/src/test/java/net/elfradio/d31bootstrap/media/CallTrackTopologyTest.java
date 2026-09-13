package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallTrackTopologyTest {
    static DownlinkTrackBinding binding()throws Exception{DownlinkTrackBinding b=new DownlinkTrackBinding();b.subscribeResult(new JSONObject("{tracks:[{trackName:'audio',mid:'1',errorCode:null}],errorCode:''}"));return b;}
    static CallTrackTopology.Track upstream(){return new CallTrackTopology.Track("0","sender","audio","receiver-0","audio","SEND_ONLY",false);}
    static CallTrackTopology.Track downstream(){return new CallTrackTopology.Track("1","unused",null,"receiver-1","audio","RECV_ONLY",true);}
    static void rejected(List<CallTrackTopology.Track> list)throws Exception{try{CallTrackTopology.validate(list,"sender",binding());fail();}catch(IOException expected){}}
    @Test public void splitSendingAndReceivingTransceiversShareOnePeer()throws Exception{assertEquals(1,CallTrackTopology.validate(Arrays.asList(upstream(),downstream()),"sender",binding()));}
    @Test public void oneSendRecvTransceiverCanCarryBothDirections()throws Exception{
        assertEquals(0,CallTrackTopology.validate(Collections.singletonList(new CallTrackTopology.Track("1","sender","audio","receiver-1","audio","SEND_RECV",true)),"sender",binding()));
    }
    @Test public void absentEventWrongMidKindOrDirectionCannotBind()throws Exception{
        for(CallTrackTopology.Track track:Arrays.asList(new CallTrackTopology.Track("1","unused",null,"receiver-1","audio","RECV_ONLY",false),
                new CallTrackTopology.Track("2","unused",null,"receiver-1","audio","RECV_ONLY",true),
                new CallTrackTopology.Track("1","unused",null,"receiver-1","video","RECV_ONLY",true),
                new CallTrackTopology.Track("1","unused",null,"receiver-1","audio","SEND_ONLY",true),
                new CallTrackTopology.Track("1","unused",null,null,"audio","RECV_ONLY",true)))rejected(Arrays.asList(upstream(),track));
    }
    @Test public void missingDuplicateForeignOrNonAudioSenderIsRejected()throws Exception{
        rejected(Collections.singletonList(downstream()));rejected(Arrays.asList(upstream(),upstream(),downstream()));
        for(String[] identity:new String[][]{{"foreign","audio"},{"sender","video"}})
            rejected(Arrays.asList(new CallTrackTopology.Track("0",identity[0],identity[1],"r0","audio","SEND_ONLY",false),downstream()));
    }
    @Test public void extraReceiverDuplicateMidAndUnexpectedReceiveDirectionAreRejected()throws Exception{
        rejected(Arrays.asList(new CallTrackTopology.Track("0","sender","audio","r0","audio","SEND_RECV",false),downstream()));
        rejected(Arrays.asList(new CallTrackTopology.Track("0","sender","audio","r0","audio","SEND_ONLY",true),downstream()));
        rejected(Arrays.asList(new CallTrackTopology.Track("1","sender","audio","r0","audio","SEND_ONLY",false),downstream()));
    }
    @Test public void newEnumerationUsesOnlyStableValuesAndNeverRetainsNativeWrappers()throws Exception{
        List<CallTrackTopology.Track> first=Arrays.asList(upstream(),downstream());assertEquals(1,CallTrackTopology.validate(first,"sender",binding()));
        List<CallTrackTopology.Track> replacement=Arrays.asList(upstream(),downstream());assertNotSame(first.get(0),replacement.get(0));
        assertEquals(1,CallTrackTopology.validate(replacement,"sender",binding()));
        for(java.lang.reflect.Field field:CallTrackTopology.Track.class.getDeclaredFields())assertFalse(field.getType().getName().startsWith("org.webrtc."));
    }
    @Test public void sdpValidationRejectsWrongTypeSizeAndNonString()throws Exception{
        for(Object sdp:new Object[]{"",123,new String(new char[90001]).replace('\0','x')}){
            JSONObject result=new JSONObject().put("sessionDescription",new JSONObject().put("type","offer").put("sdp",sdp));
            try{AndroidCallPeer.parse(result,"offer");fail();}catch(IOException expected){assertEquals("MEDIA_CALL_SDP_INVALID",expected.getMessage());}
        }
        try{AndroidCallPeer.parse(new JSONObject("{sessionDescription:{type:'answer',sdp:'synthetic'}}"),"offer");fail();}catch(IOException expected){}
        assertEquals("synthetic",AndroidCallPeer.parse(new JSONObject("{sessionDescription:{type:'offer',sdp:'synthetic'}}"),"offer").description);
    }
    @Test public void remoteErrorsAreRejectedWithoutTreatingNullOrEmptyAsError()throws Exception{
        for(Object error:new Object[]{JSONObject.NULL,""})AndroidCallPeer.noError(new JSONObject().put("errorCode",error));
        AndroidCallPeer.noError(new JSONObject());
        for(Object error:new Object[]{"FAILED",1,false,new JSONObject()}){
            try{AndroidCallPeer.noError(new JSONObject().put("errorCode",error));fail();}catch(IOException expected){assertEquals("MEDIA_CALL_REMOTE_ERROR",expected.getMessage());}
        }
    }
}
