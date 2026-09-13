package net.elfradio.d31bootstrap.media;

import android.content.Context;
import org.webrtc.*;

/** 在主线加载既有WebRTC JNI后、媒体工作线程上创建；Peer/factory退出后最后释放。 */
public final class RtcVideoEncoding implements AutoCloseable {
    private EglBase egl;
    private final VideoEncoderFactory encoder;
    private final VideoDecoderFactory decoder;
    public RtcVideoEncoding(Context app)throws Exception {
        MediaReadiness.requireApplicationIdentity(app,"net.elfradio.d31bootstrap");
        egl=EglBase.create();
        try{
            encoder=new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true);
            decoder=new DefaultVideoDecoderFactory(egl.getEglBaseContext());
        }catch(RuntimeException|LinkageError failure){egl.release();egl=null;throw failure;}
    }
    public VideoEncoderFactory encoderFactory(){return encoder;}
    public VideoDecoderFactory decoderFactory(){return decoder;}
    public synchronized EglBase.Context context(){if(egl==null)throw new IllegalStateException("MEDIA_VIDEO_EGL_CLOSED");return egl.getEglBaseContext();}
    public synchronized void close(){if(egl!=null){egl.release();egl=null;}}
}
