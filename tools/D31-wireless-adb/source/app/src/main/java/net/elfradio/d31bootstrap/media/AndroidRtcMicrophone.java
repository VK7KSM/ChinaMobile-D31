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

/** 真实WebRTC单向上行，不创建相机/EGL，不修改电话模式、音量或扬声器路由。 */
public final class AndroidRtcMicrophone implements MicrophoneSession.Peer {
    private final Context context;
    private final MediaCapture.Clock clock;
    private final ApkMediaLibrary library;
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audioModule;
    private AudioSource source;
    private AudioTrack track;
    private PeerConnection connection;
    public AndroidRtcMicrophone(Context mediaContext, File verifiedCoreApk, String coreSha256,
            File privateNativeCache, MediaCapture.Clock clock) throws Exception {
        if(mediaContext==null||clock==null)throw new IOException("MEDIA_CONTEXT_REQUIRED");
        context=mediaContext;this.clock=clock;
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
                .setEnableVolumeLogger(false)
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback(){
                    public void onWebRtcAudioRecordStart(){events.recording(true);}
                    public void onWebRtcAudioRecordStop(){events.recording(false);}
                })
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){
                    public void onWebRtcAudioRecordInitError(String detail){events.failed();}
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code,String detail){events.failed();}
                    public void onWebRtcAudioRecordError(String detail){events.failed();}
                }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                    public void onWebRtcAudioTrackInitError(String detail){events.failed();}
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code,String detail){events.failed();}
                    public void onWebRtcAudioTrackError(String detail){events.failed();}
                }).createAudioDeviceModule();
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(audioModule).createPeerConnectionFactory();
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
    }
    public JSONObject publishOffer(Cancellation cancel)throws Exception {
        final RtcAwait<SessionDescription> created=new RtcAwait<SessionDescription>();
        connection.createOffer(new SdpAdapter(){
            public void onCreateSuccess(SessionDescription value){created.succeed(value);}
            public void onCreateFailure(String detail){created.fail("MEDIA_OFFER_FAILED");}
        },new MediaConstraints());
        set(created.get(cancel,clock,10000),true,cancel);
        long end=clock.elapsed()+7000;
        while(connection.iceGatheringState()!=PeerConnection.IceGatheringState.COMPLETE){
            cancel.check();if(clock.elapsed()>=end)throw new IOException("MEDIA_ICE_GATHERING_TIMED_OUT");Thread.sleep(50);
        }
        cancel.check();JSONArray tracks=new JSONArray();
        for(RtpTransceiver transceiver:connection.getTransceivers()){
            MediaStreamTrack sender=transceiver.getSender().track();
            if(sender!=null){
                if(!"audio".equals(sender.kind())||transceiver.getMid()==null)throw new IOException("MEDIA_TRACK_INVALID");
                tracks.put(new JSONObject().put("mid",transceiver.getMid()).put("trackName","audio"));
            }
        }
        if(tracks.length()!=1)throw new IOException("MEDIA_TRACK_INVALID");
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
        boolean failed=false;
        try{if(connection!=null){try{connection.close();}finally{connection.dispose();}}}catch(Exception|LinkageError e){failed=true;}finally{connection=null;}
        try{if(track!=null)track.dispose();}catch(Exception|LinkageError e){failed=true;}finally{track=null;}
        try{if(source!=null)source.dispose();}catch(Exception|LinkageError e){failed=true;}finally{source=null;}
        try{if(factory!=null)factory.dispose();}catch(Exception|LinkageError e){failed=true;}finally{factory=null;}
        try{if(audioModule!=null)audioModule.release();}catch(Exception|LinkageError e){failed=true;}finally{audioModule=null;}
        if(failed)throw new IOException("MEDIA_NATIVE_RELEASE_UNCONFIRMED");
    }
    private static class SdpAdapter implements SdpObserver {
        public void onCreateSuccess(SessionDescription value){} public void onSetSuccess(){}
        public void onCreateFailure(String detail){} public void onSetFailure(String detail){}
    }
}
