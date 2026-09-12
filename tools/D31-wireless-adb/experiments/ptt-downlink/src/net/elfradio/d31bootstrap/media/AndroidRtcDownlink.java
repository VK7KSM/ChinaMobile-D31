package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioAttributes;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** 独立PTT候选Peer：仅下行；协商/释放由主线专用串行媒体线程调用，不得放Daemon循环。 */
public final class AndroidRtcDownlink implements AutoCloseable {
    public interface Events {
        void changed();
        void failed(String code);
    }
    private final Context context;
    private final ApkMediaLibrary library;
    private final MediaCapture.Clock clock;
    private final Events events;
    private final DownlinkTrackBinding binding=new DownlinkTrackBinding();
    private final ConcurrentHashMap<String,RtpTransceiver> actualTracks=new ConcurrentHashMap<>();
    private final Cancellation cancellation=new Cancellation();
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule module;
    private PeerConnection pc;
    private AudioTrack remote;
    private volatile boolean closed,ice,negotiated,acknowledged,playoutRequested,playbackStarted,playbackFrames,unmuted;
    private volatile JSONObject playbackIdentity=new JSONObject();
    public AndroidRtcDownlink(Context context,File apk,String sha256,File nativeCache,MediaCapture.Clock clock,Events events)throws Exception{
        this.context=context;this.clock=clock;this.events=events;library=new ApkMediaLibrary(apk,sha256,nativeCache);
    }
    public void open()throws Exception {
        cancellation.check();if(factory!=null)throw new IOException("MEDIA_PEER_NOT_REUSABLE");
        MediaReadiness.requireApplicationIdentity(context,AppMediaContract.PACKAGE);
        if(!library.load("jingle_peerconnection_so"))throw new IOException("MEDIA_NATIVE_LOAD_FAILED");
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setNativeLibraryLoader(library).createInitializationOptions());
        module=JavaAudioDeviceModule.builder(context).setEnableVolumeLogger(false)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback(){
                    public void onWebRtcAudioRecordStart(){fail("MEDIA_PTT_CAPTURE_FORBIDDEN");}
                    public void onWebRtcAudioRecordStop(){}
                })
                .setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback(){
                    public void onWebRtcAudioTrackStart(){if(!closed){
                        try{
                            Object output=JavaAudioDeviceModule.class.getField("audioOutput").get(module);
                            java.lang.reflect.Field field=output.getClass().getDeclaredField("audioTrack");field.setAccessible(true);
                            Object raw=field.get(output);
                            if(!(raw instanceof android.media.AudioTrack))throw new IOException("MEDIA_OUTPUT_IDENTITY_MISSING");
                            android.media.AudioTrack track=(android.media.AudioTrack)raw;
                            playbackIdentity=new JSONObject().put("pid",android.os.Process.myPid()).put("audio_session",track.getAudioSessionId())
                                    .put("sample_rate",track.getSampleRate()).put("channels",track.getChannelCount())
                                    .put("audio_format",track.getAudioFormat()).put("stream_type",track.getStreamType());
                            playbackStarted=true;events.changed();
                        }catch(Exception|LinkageError failure){fail("MEDIA_OUTPUT_IDENTITY_MISSING");}
                    }}
                    public void onWebRtcAudioTrackStop(){playbackStarted=false;if(!closed&&playoutRequested)fail("MEDIA_PTT_PLAYOUT_STOPPED");}
                })
                .setPlaybackSamplesReadyCallback(samples->{if(!closed&&!playbackFrames&&playoutRequested&&samples.getData().length>0){
                    playbackFrames=true;events.changed();
                }})
                .setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                    public void onWebRtcAudioTrackInitError(String detail){fail("MEDIA_PTT_PLAYOUT_INIT_FAILED");}
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code,String detail){fail("MEDIA_PTT_PLAYOUT_START_FAILED");}
                    public void onWebRtcAudioTrackError(String detail){fail("MEDIA_PTT_PLAYOUT_FAILED");}
                }).createAudioDeviceModule();
        module.setMicrophoneMute(true);module.setSpeakerMute(true);
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory();
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc=factory.createPeerConnection(config,new PeerConnection.Observer(){
            public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){
                if(closed)return;
                boolean connected=s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED;
                if(ice!=connected){ice=connected;events.changed();}
                if(s==PeerConnection.IceConnectionState.FAILED||s==PeerConnection.IceConnectionState.DISCONNECTED)fail("MEDIA_PTT_ICE_LOST");
            }
            public void onTrack(RtpTransceiver transceiver){
                MediaStreamTrack track=transceiver.getReceiver().track();
                if(track!=null)track.setEnabled(false);
                if(closed)return;
                String mid=transceiver.getMid();
                if(track==null||!"audio".equals(track.kind())||!binding.expected(mid)){
                    fail("MEDIA_DOWNLINK_UNEXPECTED_TRACK");return;
                }
                actualTracks.put(transceiver.getReceiver().id(),transceiver);
            }
            public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){
                // onTrack提供实际transceiver/mid；不启用其它或占位receiver。
                MediaStreamTrack track=receiver.track();if(track!=null)track.setEnabled(false);
            }
            public void onIceConnectionReceivingChange(boolean b){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            public void onIceCandidate(IceCandidate c){}
            public void onIceCandidatesRemoved(IceCandidate[] c){}
            public void onAddStream(MediaStream s){}
            public void onRemoveStream(MediaStream s){}
            public void onDataChannel(DataChannel channel){fail("MEDIA_PTT_DATA_CHANNEL_FORBIDDEN");}
            public void onRenegotiationNeeded(){}
        });
        if(pc==null)throw new IOException("MEDIA_PEER_INIT_FAILED");
        pc.setAudioRecording(false);pc.setAudioPlayout(false);
        // 不创建AudioSource、本地AudioTrack、相机或EGL，也不请求录音权限。
    }
    /** 主线rpc(subscribe)的原结果；返回rpc(answer)所需body，首版严格接受含远端offer的首次订阅。 */
    public JSONObject subscribe(JSONObject result)throws Exception{
        cancellation.check();if(pc==null||negotiated)throw new IOException("MEDIA_PTT_SUBSCRIBE_STATE");
        JSONObject description=result.getJSONObject("sessionDescription");
        String sdp=description.getString("sdp");
        if(!"offer".equals(description.optString("type"))||sdp.isEmpty()||sdp.length()>90000)
            throw new IOException("MEDIA_PTT_SUBSCRIBE_OFFER_REQUIRED");
        binding.subscribeResult(result);
        set(new SessionDescription(SessionDescription.Type.OFFER,sdp),false);
        if(pc.getTransceivers().size()!=1)throw new IOException("MEDIA_PTT_TRANSCEIVER_COUNT");
        RtpTransceiver tx=pc.getTransceivers().get(0);
        if(!binding.expected(tx.getMid())||tx.getSender().track()!=null||!tx.setDirection(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            throw new IOException("MEDIA_PTT_DIRECTION_INVALID");
        RtcAwait<SessionDescription> created=new RtcAwait<>();
        pc.createAnswer(new Adapter(){public void onCreateSuccess(SessionDescription value){created.succeed(value);}
            public void onCreateFailure(String detail){created.fail("MEDIA_PTT_ANSWER_FAILED");}},new MediaConstraints());
        set(created.get(cancellation,clock,10000),true);
        RtpTransceiver actual=actualTracks.get(tx.getReceiver().id());
        if(actual==null||!binding.matches(tx.getMid(),String.valueOf(tx.getCurrentDirection()),
                tx.getReceiver().track().kind(),true))throw new IOException("MEDIA_DOWNLINK_ACTUAL_TRACK_MISSING");
        remote=(AudioTrack)tx.getReceiver().track();remote.setEnabled(false);negotiated=true;
        SessionDescription local=pc.getLocalDescription();
        return new JSONObject().put("sessionDescription",new JSONObject().put("type",local.type.canonicalForm()).put("sdp",local.description));
    }
    /** 只有主线rpc(answer)成功回执后调用，不把ICE或本地setDescription当成服务端确认。 */
    public void answerAcknowledged()throws Exception{
        cancellation.check();if(!negotiated)throw new IOException("MEDIA_PTT_ANSWER_NOT_CREATED");acknowledged=true;
    }
    /** 先以软件静音启动实际AudioTrack供归属取证，不能据此发ready或解除静音。 */
    public void prepareMutedPlayout(AudioGuard guard)throws Exception{
        cancellation.check();if(!acknowledged||!ice||remote==null)throw new IOException("MEDIA_PTT_NOT_CONNECTED");
        guard.requireIdle();cancellation.check();
        module.setSpeakerMute(true);playoutRequested=true;remote.setEnabled(true);pc.setAudioPlayout(true);
    }
    /** 此守卫必须证明本次实际输出身份；准备阶段的空闲守卫不能代替。 */
    public void enablePlayout(AudioGuard ownedOutput)throws Exception{
        cancellation.check();if(!playoutRequested||!playbackStarted||!acknowledged||!ice)throw new IOException("MEDIA_PTT_OUTPUT_NOT_STARTED");
        ownedOutput.requireIdle();cancellation.check();module.setSpeakerMute(false);unmuted=true;
    }
    public JSONObject privatePlaybackIdentity()throws Exception{return new JSONObject(playbackIdentity.toString());}
    public boolean ready(){return !closed&&acknowledged&&ice&&unmuted&&playoutRequested&&playbackStarted&&playbackFrames;}
    public JSONObject snapshot()throws Exception{return new JSONObject().put("mode","ptt").put("subscribed",acknowledged)
            .put("ice_connected",ice).put("actual_remote_track",remote!=null).put("playback_started",playbackStarted)
            .put("playback_frames_seen",playbackFrames).put("ready",ready()).put("audio_content","NOT_VERIFIED");}
    /** 可跨线程取消，仅撤销资格；JNI清理由原工作线程close执行。 */
    public void cancel(){closed=true;unmuted=false;cancellation.cancel();}
    private void fail(String code){if(!closed){cancel();events.failed(code);}}
    private void set(SessionDescription value,boolean local)throws Exception{
        RtcAwait<Boolean> applied=new RtcAwait<>();SdpObserver observer=new Adapter(){
            public void onSetSuccess(){applied.succeed(true);}public void onSetFailure(String detail){applied.fail("MEDIA_PTT_SDP_FAILED");}};
        if(local)pc.setLocalDescription(observer,value);else pc.setRemoteDescription(observer,value);
        applied.get(cancellation,clock,10000);
    }
    public void close()throws Exception{
        cancel();boolean failed=false;
        try{if(module!=null)module.setSpeakerMute(true);}catch(Exception|LinkageError e){failed=true;}
        try{if(pc!=null){try{pc.close();}finally{pc.dispose();}}}catch(Exception|LinkageError e){failed=true;}finally{pc=null;}
        remote=null;actualTracks.clear();
        try{if(factory!=null)factory.dispose();}catch(Exception|LinkageError e){failed=true;}finally{factory=null;}
        try{if(module!=null)module.release();}catch(Exception|LinkageError e){failed=true;}finally{module=null;}
        if(failed)throw new IOException("MEDIA_PTT_RELEASE_UNCONFIRMED");
    }
    private static class Adapter implements SdpObserver{
        public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}
        public void onCreateFailure(String s){}public void onSetFailure(String s){}
    }
}
