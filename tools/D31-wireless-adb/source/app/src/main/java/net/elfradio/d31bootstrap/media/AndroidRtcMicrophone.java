package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import java.io.*;
import java.util.Collections;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** 真实WebRTC单向上行；视频按会话创建，不修改电话模式、音量或扬声器路由。 */
public final class AndroidRtcMicrophone implements MicrophoneSession.Peer {
    private final Context context;
    private final MediaCapture.Clock clock;
    private final ApkMediaLibrary library;
    private final String mode,camera;
    private RtcVideoEncoding videoEncoding;
    private volatile RtcVideoCapture video;
    private volatile String videoFailure="";
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audioModule;
    private AudioSource source;
    private AudioTrack track;
    private PeerConnection connection;
    private AppMediaRtcGuard inputGuard;
    private volatile boolean captureVerified;
    private volatile boolean closing;
    private final AppMediaMute mute=new AppMediaMute();
    private final AppMediaPcmStats pcm=new AppMediaPcmStats();
    private volatile JSONObject recordParameters=new JSONObject();
    void inputGuard(AppMediaRtcGuard guard){inputGuard=guard;}
    public boolean captureReady(){return !closing&&captureVerified&&(!"video".equals(mode)||(video!=null&&video.ready()));}
    public void requireHealthy()throws IOException{if(!videoFailure.isEmpty())throw new IOException(videoFailure);}
    public JSONObject diagnostics()throws Exception{
        return new JSONObject().put("guard",inputGuard==null?new JSONObject():inputGuard.snapshot())
                .put("record_parameters",new JSONObject(recordParameters.toString())).put("pcm",pcm.snapshot())
                .put("video",video==null?new JSONObject():video.snapshot());
    }
    public AndroidRtcMicrophone(Context mediaContext, File verifiedCoreApk, String coreSha256,
            File privateNativeCache, MediaCapture.Clock clock) throws Exception {
        this(mediaContext,verifiedCoreApk,coreSha256,privateNativeCache,clock,null);
    }
    public AndroidRtcMicrophone(Context mediaContext, File verifiedCoreApk, String coreSha256,
            File privateNativeCache, MediaCapture.Clock clock,RtcOffer offer) throws Exception {
        if(mediaContext==null||clock==null)throw new IOException("MEDIA_CONTEXT_REQUIRED");
        context=mediaContext;this.clock=clock;
        mode=offer==null?"microphone":offer.mode;camera=offer==null?"front":offer.camera;
        library=new ApkMediaLibrary(verifiedCoreApk,coreSha256,privateNativeCache);
    }
    /** 显式独立验收入口，只装载JNI；不构造PeerConnection或AudioRecord。 */
    public boolean loadNativeForValidation(){return library.load("jingle_peerconnection_so");}
    public void open(final MicrophoneSession.PeerEvents events, Cancellation cancel) throws Exception {
        cancel.check();
        // 已知root独立核心与已安装APP权限身份不同；实际录音只交给本包APP进程。
        MediaReadiness.requireApplicationIdentity(context,"net.elfradio.d31bootstrap");
        if(connection!=null||factory!=null)throw new IOException("MEDIA_PEER_NOT_REUSABLE");
        if(context.checkPermission(Manifest.permission.RECORD_AUDIO,android.os.Process.myPid(),android.os.Process.myUid())
                !=PackageManager.PERMISSION_GRANTED)throw new IOException("MEDIA_RECORD_PERMISSION_MISSING");
        if(!loadNativeForValidation())throw new IOException("MEDIA_NATIVE_LOAD_FAILED");
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setNativeLibraryLoader(library).createInitializationOptions());
        cancel.check();
        audioModule=JavaAudioDeviceModule.builder(context).setAudioSource(MediaRecorder.AudioSource.MIC)
                // 138原件证明默认48k与已验证16k归属合同不符；仅固定输入，不改变输出。
                .setInputSampleRate(16000)
                .setEnableVolumeLogger(false)
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback(){
                    public void onWebRtcAudioRecordStart(){
                        try{
                            // 固定依赖125.6422.07的实际AudioRecord；不从转储猜测自身session。
                            Object input=JavaAudioDeviceModule.class.getField("audioInput").get(audioModule);
                            java.lang.reflect.Field field=input.getClass().getDeclaredField("audioRecord");field.setAccessible(true);
                            Object raw=field.get(input);
                            if(!(raw instanceof android.media.AudioRecord)||inputGuard==null)throw new IOException("MEDIA_RTC_INPUT_IDENTITY");
                            android.media.AudioRecord record=(android.media.AudioRecord)raw;
                            recordParameters=new JSONObject().put("sample_rate",record.getSampleRate()).put("channels",record.getChannelCount())
                                    .put("audio_format",record.getAudioFormat()).put("audio_source",record.getAudioSource());
                            inputGuard.recording(record.getAudioSessionId());events.recording(true);
                        }catch(Exception|LinkageError failure){events.failed();}
                    }
                    public void onWebRtcAudioRecordStop(){captureVerified=false;events.recording(false);}
                })
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){
                    public void onWebRtcAudioRecordInitError(String detail){events.failed();}
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code,String detail){events.failed();}
                    public void onWebRtcAudioRecordError(String detail){events.failed();}
                }).setSamplesReadyCallback(samples->{
                    boolean allowed=!closing&&inputGuard!=null&&inputGuard.ready();captureVerified=allowed;
                    pcm.accept(samples.getData(),samples.getAudioFormat(),samples.getChannelCount(),samples.getSampleRate(),allowed);
                    JavaAudioDeviceModule module=audioModule;if(module!=null)mute.apply(!allowed,module::setMicrophoneMute);
                }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                    public void onWebRtcAudioTrackInitError(String detail){events.failed();}
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code,String detail){events.failed();}
                    public void onWebRtcAudioTrackError(String detail){events.failed();}
                }).createAudioDeviceModule();
        mute.apply(true,audioModule::setMicrophoneMute);
        PeerConnectionFactory.Builder builder=PeerConnectionFactory.builder().setAudioDeviceModule(audioModule);
        if("video".equals(mode)){
            videoEncoding=new RtcVideoEncoding(context);
            builder.setVideoEncoderFactory(videoEncoding.encoderFactory()).setVideoDecoderFactory(videoEncoding.decoderFactory());
        }
        factory=builder.createPeerConnectionFactory();
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        connection=factory.createPeerConnection(config,new PeerConnection.Observer(){
            public void onSignalingChange(PeerConnection.SignalingState state){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState state){
                if(state==PeerConnection.IceConnectionState.CONNECTED||state==PeerConnection.IceConnectionState.COMPLETED)events.connected();
                if(state==PeerConnection.IceConnectionState.FAILED||state==PeerConnection.IceConnectionState.DISCONNECTED)events.failed();
            }
            public void onIceConnectionReceivingChange(boolean receiving){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState state){}
            public void onIceCandidate(IceCandidate candidate){}
            public void onIceCandidatesRemoved(IceCandidate[] candidates){}
            public void onAddStream(MediaStream stream){}
            public void onRemoveStream(MediaStream stream){}
            public void onDataChannel(DataChannel channel){events.failed();}
            public void onRenegotiationNeeded(){}
            public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){
                if(receiver.track()!=null)receiver.track().setEnabled(false);events.failed();
            }
        });
        if(connection==null)throw new IOException("MEDIA_PEER_INIT_FAILED");
        connection.setAudioPlayout(false);cancel.check();
        source=factory.createAudioSource(new MediaConstraints());track=factory.createAudioTrack("audio",source);
        if(connection.addTransceiver(track,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))==null)
            throw new IOException("MEDIA_TRANSCEIVER_FAILED");
        if("video".equals(mode)){
            boolean unmetered=false;
            try{
                android.net.ConnectivityManager network=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                unmetered=network!=null&&network.getActiveNetworkInfo()!=null&&network.getActiveNetworkInfo().isConnected()
                        &&!network.isActiveNetworkMetered();
            }catch(Exception unavailable){}
            video=new RtcVideoCapture(context,factory,connection,videoEncoding,camera,unmetered,state->{
                String phase=state.optString("state");
                if(!closing&&("closing".equals(phase)||"closed".equals(phase)||"release_unconfirmed".equals(phase))){
                    String error=state.optString("error");
                    videoFailure=error.matches("MEDIA_[A-Z0-9_]{1,80}")?error:"MEDIA_VIDEO_CAPTURE_STOPPED";
                }
            });
            video.start();video.awaitReady(RtcVideoCapture.START_TIMEOUT_MS,cancel);
        }
    }
    public JSONObject publishOffer(Cancellation cancel)throws Exception {
        final RtcAwait<SessionDescription> created=new RtcAwait<SessionDescription>();
        connection.createOffer(new SdpAdapter(){
            public void onCreateSuccess(SessionDescription value){created.succeed(value);}
            public void onCreateFailure(String detail){created.fail("MEDIA_OFFER_FAILED");}
        },new MediaConstraints());
        set(created.get(cancel,clock,10000),true,cancel);
        // CF SFU先交换SDP再进行ICE连通检查，不等待全部本地候选收集完成。
        cancel.check();JSONArray tracks=new JSONArray();int audioTracks=0,videoTracks=0;
        for(RtpTransceiver transceiver:connection.getTransceivers()){
            MediaStreamTrack sender=transceiver.getSender().track();
            if(sender!=null){
                String kind=sender.kind();
                if("audio".equals(kind))audioTracks++;
                else if("video".equals(kind)&&"video".equals(mode))videoTracks++;
                else throw new IOException("MEDIA_TRACK_INVALID");
                if(transceiver.getMid()==null)throw new IOException("MEDIA_TRACK_INVALID");
                tracks.put(new JSONObject().put("mid",transceiver.getMid()).put("trackName",kind));
            }
        }
        if(audioTracks!=1||videoTracks!=("video".equals(mode)?1:0))throw new IOException("MEDIA_TRACK_INVALID");
        SessionDescription local=connection.getLocalDescription();
        if(local==null)throw new IOException("MEDIA_OFFER_MISSING");
        return new JSONObject().put("sessionDescription",new JSONObject().put("type",local.type.canonicalForm()).put("sdp",local.description))
                .put("tracks",tracks);
    }
    public void answer(JSONObject value,Cancellation cancel)throws Exception {
        if(!"answer".equals(value.optString("type"))||value.optString("sdp").isEmpty()||value.optString("sdp").length()>90000)
            throw new IOException("MEDIA_ANSWER_INVALID");
        set(new SessionDescription(SessionDescription.Type.ANSWER,value.getString("sdp")),false,cancel);
    }
    private void set(SessionDescription description,boolean local,Cancellation cancel)throws Exception {
        final RtcAwait<Boolean> applied=new RtcAwait<Boolean>();
        SdpObserver observer=new SdpAdapter(){public void onSetSuccess(){applied.succeed(true);}
            public void onSetFailure(String detail){applied.fail("MEDIA_SDP_FAILED");}};
        if(local)connection.setLocalDescription(observer,description);else connection.setRemoteDescription(observer,description);
        applied.get(cancel,clock,10000);
    }
    public void close()throws Exception {
        String failure="";
        closing=true;captureVerified=false;
        try{if(audioModule!=null)mute.close(audioModule::setMicrophoneMute);}catch(Exception|LinkageError e){failure="MEDIA_MUTE_RELEASE_UNCONFIRMED";}
        if(video!=null){
            try{if(connection!=null)connection.setAudioRecording(false);}
            catch(Exception|LinkageError e){failure="MEDIA_RECORD_STOP_UNCONFIRMED";}
        }
        try{
            if(video!=null){
                video.close();
                if(!video.awaitClosed(RtcVideoCapture.RELEASE_TIMEOUT_MS))throw new IOException("MEDIA_VIDEO_RELEASE_UNCONFIRMED");
            }
            // 依赖按序释放；任一步失败保留该对象及其依赖，不能继续销毁factory/EGL。
            if(connection!=null){connection.close();connection.dispose();connection=null;}
            if(track!=null){track.dispose();track=null;}
            if(source!=null){source.dispose();source=null;}
            if(factory!=null){factory.dispose();factory=null;}
            if(audioModule!=null){audioModule.release();audioModule=null;}
            if(videoEncoding!=null){videoEncoding.close();videoEncoding=null;}
        }catch(Exception|LinkageError e){
            String code=e.getMessage();failure=code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_NATIVE_RELEASE_UNCONFIRMED";
        }
        try{if(inputGuard!=null)inputGuard.close();}catch(Exception e){if(failure.isEmpty())failure="MEDIA_RTC_OBSERVER_RELEASE_UNCONFIRMED";}
        if(!failure.isEmpty())throw new IOException(failure);
    }
    private static class SdpAdapter implements SdpObserver {
        public void onCreateSuccess(SessionDescription value){} public void onSetSuccess(){}
        public void onCreateFailure(String detail){} public void onSetFailure(String detail){}
    }
}
