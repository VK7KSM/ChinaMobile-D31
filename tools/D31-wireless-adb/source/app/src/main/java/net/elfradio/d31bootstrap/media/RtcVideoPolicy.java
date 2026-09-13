package net.elfradio.d31bootstrap.media;

import java.io.IOException;

/** 固定有界视频档位；网络类型由主线已有观测传入，不重新轮询网络。 */
public final class RtcVideoPolicy {
    public final int width,height,fps,maxBitrate;
    private RtcVideoPolicy(int w,int h,int fps,int bitrate){width=w;height=h;this.fps=fps;maxBitrate=bitrate;}
    public static RtcVideoPolicy profile(boolean unmetered){return unmetered?new RtcVideoPolicy(640,480,15,600000):new RtcVideoPolicy(320,240,10,180000);}
    public static int camera(boolean[] front,String requested)throws IOException {
        if(front==null||!("front".equals(requested)||"back".equals(requested)))throw new IOException("MEDIA_VIDEO_CAMERA_REQUEST");
        for(int i=0;i<front.length;i++)if(front[i]=="front".equals(requested))return i;
        if(front.length==1)return 0;
        throw new IOException("MEDIA_VIDEO_CAMERA_UNAVAILABLE");
    }
    private RtcVideoPolicy(){this(0,0,0,0);}
}
