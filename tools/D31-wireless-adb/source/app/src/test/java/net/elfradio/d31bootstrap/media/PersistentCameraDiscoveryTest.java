package net.elfradio.d31bootstrap.media;

import android.hardware.Camera.CameraInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** 仅替代相机元数据读取，不创建相机，不定义Android或生产同名桩。 */
public class PersistentCameraDiscoveryTest {
    static final class Discovery extends AndroidPersistentRtc.CameraDiscovery {
        final CameraInfo[] cameras;
        final List<Integer> reads=new ArrayList<>();
        int countCalls;
        int reportedCount;
        RuntimeException failure;
        Discovery(int... facingOrientation)throws Exception {
            cameras=new CameraInfo[facingOrientation.length/2];reportedCount=cameras.length;
            for(int i=0;i<cameras.length;i++){
                cameras[i]=(CameraInfo)CallInitOnlyReleaseTest.empty(CameraInfo.class);
                cameras[i].facing=facingOrientation[2*i];cameras[i].orientation=facingOrientation[2*i+1];
            }
        }
        @Override int count(){countCalls++;return reportedCount;}
        @Override CameraInfo info(int index){reads.add(index);if(failure!=null)throw failure;return cameras[index];}
    }

    @Test public void d31SoleBackUsesOneCountOneInfoAndReportsActualFacing()throws Exception {
        Discovery d=new Discovery(0,0);
        AndroidPersistentRtc.CameraSelection selected=AndroidPersistentRtc.selectCamera(d,"front");
        assertEquals("Camera 0, Facing back, Orientation 0",selected.name);
        assertEquals("back",selected.facing);assertEquals(1,selected.count);
        assertEquals(1,d.countCalls);assertEquals(Arrays.asList(0),d.reads);
    }

    @Test public void frontSelectionKeepsPhysicalIndexAndSdkOrientationName()throws Exception {
        Discovery d=new Discovery(0,270,1,90);
        AndroidPersistentRtc.CameraSelection selected=AndroidPersistentRtc.selectCamera(d,"front");
        assertEquals("Camera 1, Facing front, Orientation 90",selected.name);
        assertEquals("front",selected.facing);assertEquals(2,selected.count);
        assertEquals(1,d.countCalls);assertEquals(Arrays.asList(0,1),d.reads);
    }

    @Test public void backSelectionDoesNotAssumeBackIsIndexZero()throws Exception {
        Discovery d=new Discovery(1,180,0,270);
        AndroidPersistentRtc.CameraSelection selected=AndroidPersistentRtc.selectCamera(d,"back");
        assertEquals("Camera 1, Facing back, Orientation 270",selected.name);
        assertEquals("back",selected.facing);assertEquals(2,selected.count);
        assertEquals(1,d.countCalls);assertEquals(Arrays.asList(0,1),d.reads);
    }

    @Test public void soleFrontFallbackStillReportsFront()throws Exception {
        AndroidPersistentRtc.CameraSelection selected=AndroidPersistentRtc.selectCamera(new Discovery(1,90),"back");
        assertEquals("front",selected.facing);assertEquals("Camera 0, Facing front, Orientation 90",selected.name);
    }

    @Test public void multipleBackCamerasDoNotPretendToSatisfyFront()throws Exception {
        rejected(new Discovery(0,0,0,90),"front");
    }

    @Test public void invalidRequestDoesNotQueryHardware()throws Exception {
        Discovery d=new Discovery(0,0);rejected(d,"external");
        assertEquals(0,d.countCalls);assertTrue(d.reads.isEmpty());
    }

    @Test public void noCameraAndInvalidCountsDoNotReadMetadata()throws Exception {
        for(int count:new int[]{0,-1,17}){
            Discovery d=new Discovery();d.reportedCount=count;rejected(d,"front");
            assertEquals(1,d.countCalls);assertTrue(d.reads.isEmpty());
        }
    }

    @Test public void unknownFacingIsNotConvertedToBack()throws Exception {
        rejected(new Discovery(2,0),"back");
    }

    @Test public void missingMetadataCannotBecomeSingleCameraFallback()throws Exception {
        Discovery d=new Discovery(0,0,1,90);d.cameras[1]=null;rejected(d,"front");
    }

    @Test public void metadataFailureDoesNotRetryOrSelectAnotherCamera()throws Exception {
        Discovery d=new Discovery(0,0);d.failure=new SecurityException("camera denied");
        try{AndroidPersistentRtc.selectCamera(d,"front");fail();}
        catch(SecurityException expected){assertSame(d.failure,expected);}
        assertEquals(1,d.countCalls);assertEquals(Arrays.asList(0),d.reads);
    }

    private static void rejected(Discovery d,String facing)throws Exception {
        try{AndroidPersistentRtc.selectCamera(d,facing);fail();}
        catch(IOException expected){assertEquals("MEDIA_CAMERA_FACING_UNAVAILABLE",expected.getMessage());}
    }
}
