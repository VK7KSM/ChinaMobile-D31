package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class RtcVideoPolicyTest {
    @Test public void soleRearD31CameraAcceptsFrontDefault()throws Exception {assertEquals(0,RtcVideoPolicy.camera(new boolean[]{false},"front"));}
    @Test public void multipleCamerasFollowRequestedFacing()throws Exception {
        assertEquals(1,RtcVideoPolicy.camera(new boolean[]{false,true},"front"));
        assertEquals(0,RtcVideoPolicy.camera(new boolean[]{false,true},"back"));
        for(boolean[] values:new boolean[][]{{},{false,false}})try{RtcVideoPolicy.camera(values,"front");fail();}catch(IOException expected){}
    }
    @Test public void invalidFacingIsNotSilentlyBack()throws Exception {try{RtcVideoPolicy.camera(new boolean[]{false},"side");fail();}catch(IOException expected){}}
    @Test public void meteredAndUnknownNetworkUseBoundedLowProfile(){
        RtcVideoPolicy p=RtcVideoPolicy.profile(false);assertEquals(320,p.width);assertEquals(240,p.height);assertEquals(10,p.fps);assertEquals(180000,p.maxBitrate);
        RtcVideoPolicy high=RtcVideoPolicy.profile(true);assertEquals(640,high.width);assertEquals(480,high.height);assertEquals(15,high.fps);assertEquals(600000,high.maxBitrate);
    }
}
