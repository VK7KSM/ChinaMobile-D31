package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.camera2.*;
import android.os.Handler;
import java.io.IOException;

/** 复用平台占用回调；它是开拍前提示，最终仲裁仍由CameraService负责。 */
@android.annotation.TargetApi(23)
final class AutomaticPhotoCameraAvailability implements AutoCloseable {
    private final AutomaticPhotoCameraGate gate=new AutomaticPhotoCameraGate();
    private final CameraManager manager;
    private final CameraManager.AvailabilityCallback callback=new CameraManager.AvailabilityCallback(){
        public void onCameraAvailable(String id){gate.update(id,true);}
        public void onCameraUnavailable(String id){gate.update(id,false);}
    };
    AutomaticPhotoCameraAvailability(Context context,Handler main)throws Exception {
        manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        if(manager==null)throw new IOException("AUTO_PHOTO_CAMERA_UNKNOWN");
        manager.registerAvailabilityCallback(callback,main);
    }
    @SuppressWarnings("deprecation")
    void check()throws Exception {
        int[] facings=new int[Camera.getNumberOfCameras()];Camera.CameraInfo info=new Camera.CameraInfo();
        for(int i=0;i<facings.length;i++){Camera.getCameraInfo(i,info);facings[i]=info.facing;}
        int selected=AndroidMediaDevice.selectCamera(facings,"front");
        if(selected<0)throw new IOException("AUTO_PHOTO_CAMERA_UNAVAILABLE");
        String id=Integer.toString(selected);boolean matched=false;
        for(String listed:manager.getCameraIdList())if(id.equals(listed))matched=true;
        if(!matched)throw new IOException("AUTO_PHOTO_CAMERA_UNKNOWN");
        gate.requireAvailable(id,1000);
    }
    public void close(){gate.close();manager.unregisterAvailabilityCallback(callback);}
}
