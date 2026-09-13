package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaRecorder;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;

/** JNI资源仅在d31-call-peer串行线程操作；事件只持稳定ID，不缓存transceiver/receiver包装。 */
final class AndroidCallPeer implements AndroidRtcCall.Backend {
    private final Context context;
    private final File apk,cache;
    private final String hash;
    private final MediaCapture.Clock clock;
    private final DownlinkTrackBinding binding=new DownlinkTrackBinding();
    private final ConcurrentHashMap<String,String> remoteEvents=new ConcurrentHashMap<>();
    private volatile String expectedMid;
    private String senderId;
    private AndroidRtcCall owner;
    private Cancellation cancel;
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule module;
    private PeerConnection pc;
    private AudioSource source;
    private AudioTrack local;
    private volatile boolean recording,playing;
    private final Object callbacksLock=new Object();
    private int callbacks;
    private boolean callbacksClosing;
    AndroidCallPeer(Context context,File apk,String hash,File cache,MediaCapture.Clock clock)throws IOException{
        if(context==null||apk==null||cache==null||clock==null||hash==null||!hash.matches("[a-f0-9]{64}"))
            throw new IOException("MEDIA_CALL_DEPENDENCY_MISSING");
        this.context=context;this.apk=apk;this.hash=hash;this.cache=cache;this.clock=clock;
    }
    public void open(AndroidRtcCall owner,Cancellation cancel)throws Exception{
        this.owner=owner;this.cancel=cancel;check();normalSpeaker();
        MediaReadiness.requireApplicationIdentity(context,AppMediaContract.PACKAGE);
        if(context.checkPermission(Manifest.permission.RECORD_AUDIO,android.os.Process.myPid(),android.os.Process.myUid())!=PackageManager.PERMISSION_GRANTED)
            throw new IOException("MEDIA_RECORD_PERMISSION_MISSING");
        ApkMediaLibrary library=new ApkMediaLibrary(apk,hash,cache);
        if(!library.load("jingle_peerconnection_so"))throw new IOException("MEDIA_NATIVE_LOAD_FAILED");check();
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setNativeLibraryLoader(library).createInitializationOptions());check();
        module=JavaAudioDeviceModule.builder(context).setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(2)
                .setInputSampleRate(16000).setOutputSampleRate(48000).setUseStereoInput(false).setUseStereoOutput(false)
                .setEnableVolumeLogger(false).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback(){
                    public void onWebRtcAudioRecordStart(){recording=true;identity(true);}
                    public void onWebRtcAudioRecordStop(){recording=false;owner.stopped(true);}
                }).setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback(){
                    public void onWebRtcAudioTrackStart(){playing=true;identity(false);}
                    public void onWebRtcAudioTrackStop(){playing=false;owner.stopped(false);}
                }).setSamplesReadyCallback(samples->owner.pcm(true,samples.getData(),samples.getAudioFormat(),samples.getChannelCount(),samples.getSampleRate()))
                .setPlaybackSamplesReadyCallback(samples->owner.pcm(false,samples.getData(),samples.getAudioFormat(),samples.getChannelCount(),samples.getSampleRate()))
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){
                    public void onWebRtcAudioRecordInitError(String detail){owner.fail("MEDIA_CALL_CAPTURE_INIT_FAILED");}
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code,String detail){owner.fail("MEDIA_CALL_CAPTURE_START_FAILED");}
                    public void onWebRtcAudioRecordError(String detail){owner.fail("MEDIA_CALL_CAPTURE_FAILED");}
                }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                    public void onWebRtcAudioTrackInitError(String detail){owner.fail("MEDIA_CALL_PLAYOUT_INIT_FAILED");}
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code,String detail){owner.fail("MEDIA_CALL_PLAYOUT_START_FAILED");}
                    public void onWebRtcAudioTrackError(String detail){owner.fail("MEDIA_CALL_PLAYOUT_FAILED");}
                }).createAudioDeviceModule();
        // 固定依赖的公开setter会调用Logging；预解析真实volatile字段，取消路径不进入日志/JNI。
        owner.attachMute(softwareMute(module));check();
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory();check();
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.singletonList(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc=factory.createPeerConnection(config,new PeerConnection.Observer(){
            public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){
                if(cancel.isCancelled())return;
                if(s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED)owner.ice(true);
                else if(s==PeerConnection.IceConnectionState.DISCONNECTED||s==PeerConnection.IceConnectionState.FAILED)owner.fail("MEDIA_CALL_ICE_LOST");
            }
            public void onTrack(RtpTransceiver transceiver){
                if(!enterCallback())return;
                try{
                    RtpReceiver receiver=transceiver.getReceiver();MediaStreamTrack track=receiver.track();
                    if(track!=null)track.setEnabled(false);
                    String mid=transceiver.getMid();
                    if(expectedMid==null||!expectedMid.equals(mid)||track==null||!"audio".equals(track.kind()))throw new IOException();
                    String previous=remoteEvents.putIfAbsent(receiver.id(),mid);
                    if(previous!=null&&!previous.equals(mid)||remoteEvents.size()>1)throw new IOException();
                }catch(Exception|LinkageError invalid){owner.fail("MEDIA_CALL_UNEXPECTED_TRACK");}
                finally{leaveCallback();}
            }
            public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){
                if(!enterCallback())return;
                try{MediaStreamTrack track=receiver.track();if(track!=null)track.setEnabled(false);}
                catch(Exception|LinkageError invalid){owner.fail("MEDIA_CALL_UNEXPECTED_TRACK");}
                finally{leaveCallback();}
            }
            public void onIceConnectionReceivingChange(boolean b){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            public void onIceCandidate(IceCandidate c){}
            public void onIceCandidatesRemoved(IceCandidate[] c){}
            public void onAddStream(MediaStream s){}
            public void onRemoveStream(MediaStream s){}
            public void onDataChannel(DataChannel channel){owner.fail("MEDIA_CALL_DATA_CHANNEL_FORBIDDEN");}
            public void onRenegotiationNeeded(){}
        });
        if(pc==null)throw new IOException("MEDIA_PEER_INIT_FAILED");check();
        pc.setAudioRecording(false);pc.setAudioPlayout(false);check();
        source=factory.createAudioSource(new MediaConstraints());check();local=factory.createAudioTrack("audio",source);check();
        RtpTransceiver created=pc.addTransceiver(local,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
        if(created==null)throw new IOException("MEDIA_TRANSCEIVER_FAILED");senderId=created.getSender().id();check();
    }
    private void identity(boolean input){
        if(!enterCallback())return;
        try{
            Object device=JavaAudioDeviceModule.class.getField(input?"audioInput":"audioOutput").get(module);
            java.lang.reflect.Field field=device.getClass().getDeclaredField(input?"audioRecord":"audioTrack");field.setAccessible(true);Object raw=field.get(device);
            if(input){
                if(!(raw instanceof android.media.AudioRecord))throw new IOException();android.media.AudioRecord record=(android.media.AudioRecord)raw;
                owner.identity(new CallDuplexGuard.Identity(true,android.os.Process.myPid(),record.getAudioSessionId(),record.getSampleRate(),record.getChannelCount(),record.getAudioFormat(),record.getAudioSource()));
            }else{
                if(!(raw instanceof android.media.AudioTrack))throw new IOException();android.media.AudioTrack track=(android.media.AudioTrack)raw;
                owner.identity(new CallDuplexGuard.Identity(false,android.os.Process.myPid(),track.getAudioSessionId(),track.getSampleRate(),track.getChannelCount(),track.getAudioFormat(),track.getStreamType()));
            }
        }catch(Exception|LinkageError invalid){owner.fail(input?"MEDIA_CALL_INPUT_IDENTITY_INVALID":"MEDIA_CALL_OUTPUT_IDENTITY_INVALID");}
        finally{leaveCallback();}
    }
    static AndroidRtcCall.SoftwareMute softwareMute(JavaAudioDeviceModule audio)throws Exception{
        Object input=JavaAudioDeviceModule.class.getField("audioInput").get(audio);
        Object output=JavaAudioDeviceModule.class.getField("audioOutput").get(audio);
        Field microphone=muteField(input,"microphoneMute"),speaker=muteField(output,"speakerMute");
        return muted->{
            try{try{microphone.setBoolean(input,muted);}finally{speaker.setBoolean(output,muted);}}
            catch(IllegalAccessException invalid){throw new IllegalStateException("MEDIA_CALL_SOFTWARE_MUTE_FAILED",invalid);}
        };
    }
    private static Field muteField(Object device,String name)throws Exception{
        if(device==null)throw new IOException("MEDIA_CALL_SOFTWARE_MUTE_CONTRACT_INVALID");
        Field field=device.getClass().getDeclaredField(name);int modifiers=field.getModifiers();
        if(field.getType()!=boolean.class||!Modifier.isVolatile(modifiers)||Modifier.isStatic(modifiers)||Modifier.isFinal(modifiers))
            throw new IOException("MEDIA_CALL_SOFTWARE_MUTE_CONTRACT_INVALID");
        field.setAccessible(true);return field;
    }
    private boolean enterCallback(){synchronized(callbacksLock){
        if(callbacksClosing||cancel==null||cancel.isCancelled())return false;
        callbacks++;return true;
    }}
    private void leaveCallback(){synchronized(callbacksLock){callbacks--;callbacksLock.notifyAll();}}
    private void drainCallbacks()throws Exception{
        // 位于外层3秒释放等待内部；超时保留资源，绝不与尚在使用句柄的回调并发dispose。
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1750);
        synchronized(callbacksLock){callbacksClosing=true;
            while(callbacks!=0){long left=deadline-System.nanoTime();
                if(left<=0)throw new IOException("MEDIA_CALL_CALLBACK_RELEASE_UNCONFIRMED");
                TimeUnit.NANOSECONDS.timedWait(callbacksLock,left);
            }
        }
    }
    public JSONObject createPublish(JSONObject result)throws Exception{
        noError(result);check();RtcAwait<SessionDescription> created=new RtcAwait<>();
        pc.createOffer(new Adapter(){public void onCreateSuccess(SessionDescription s){created.succeed(s);}public void onCreateFailure(String ignored){created.fail("MEDIA_CALL_OFFER_FAILED");}},new MediaConstraints());
        set(created.get(cancel,clock,10000),true);check();
        List<RtpTransceiver> transceivers=pc.getTransceivers();
        if(transceivers.size()!=1)throw new IOException("MEDIA_CALL_SENDER_INVALID");
        RtpTransceiver tx=transceivers.get(0);RtpSender sender=tx.getSender();MediaStreamTrack track=sender.track();
        if(!senderId.equals(sender.id())||track==null||!"audio".equals(track.kind())||!validMid(tx.getMid()))throw new IOException("MEDIA_CALL_SENDER_INVALID");
        return new JSONObject().put("sessionDescription",description(pc.getLocalDescription(),"offer"))
                .put("tracks",new JSONArray().put(new JSONObject().put("trackName","audio").put("mid",tx.getMid())));
    }
    public void applyPublish(JSONObject result)throws Exception{
        noError(result);set(parse(result,"answer"),false);check();
        if(pc.signalingState()!=PeerConnection.SignalingState.STABLE)throw new IOException("MEDIA_CALL_SIGNALING_INVALID");
    }
    public CallProtocol.SubscriptionResult subscribe(JSONObject result)throws Exception{
        check();binding.subscribeResult(result);expectedMid=result.getJSONArray("tracks").getJSONObject(0).getString("mid");
        if(result.has("sessionDescription")){
            JSONObject raw=result.getJSONObject("sessionDescription");String type=raw.optString("type");
            if("offer".equals(type)){
                if(pc.signalingState()!=PeerConnection.SignalingState.STABLE)throw new IOException("MEDIA_CALL_SIGNALING_INVALID");
                set(parse(result,"offer"),false);check();directions();
                RtcAwait<SessionDescription> created=new RtcAwait<>();
                pc.createAnswer(new Adapter(){public void onCreateSuccess(SessionDescription s){created.succeed(s);}public void onCreateFailure(String ignored){created.fail("MEDIA_CALL_ANSWER_FAILED");}},new MediaConstraints());
                set(created.get(cancel,clock,10000),true);check();validate(false);
                return CallProtocol.SubscriptionResult.answer(new JSONObject().put("sessionDescription",description(pc.getLocalDescription(),"answer")));
            }
            if(!"answer".equals(type)||pc.signalingState()!=PeerConnection.SignalingState.HAVE_LOCAL_OFFER)
                throw new IOException("MEDIA_CALL_UNEXPECTED_ANSWER");
            set(parse(result,"answer"),false);
        }
        validate(false);return CallProtocol.SubscriptionResult.completed();
    }
    private void directions()throws Exception{
        int senders=0,receivers=0;List<RtpTransceiver> current=pc.getTransceivers();
        if(current.isEmpty()||current.size()>2)throw new IOException("MEDIA_CALL_TOPOLOGY_INVALID");
        for(RtpTransceiver tx:current){check();RtpSender sender=tx.getSender();boolean sends=sender.track()!=null,receives=binding.expected(tx.getMid());
            if(sends){if(!senderId.equals(sender.id())||!"audio".equals(sender.track().kind()))throw new IOException("MEDIA_CALL_SENDER_INVALID");senders++;}
            if(receives)receivers++;if(!sends&&!receives)throw new IOException("MEDIA_CALL_TOPOLOGY_INVALID");
            RtpTransceiver.RtpTransceiverDirection direction=sends?(receives?RtpTransceiver.RtpTransceiverDirection.SEND_RECV:RtpTransceiver.RtpTransceiverDirection.SEND_ONLY):RtpTransceiver.RtpTransceiverDirection.RECV_ONLY;
            if(!tx.setDirection(direction))throw new IOException("MEDIA_CALL_DIRECTION_INVALID");
        }
        if(senders!=1||receivers!=1)throw new IOException("MEDIA_CALL_TOPOLOGY_INVALID");
    }
    private void validate(boolean enable)throws Exception{
        check();if(pc.signalingState()!=PeerConnection.SignalingState.STABLE)throw new IOException("MEDIA_CALL_SIGNALING_INVALID");
        List<RtpTransceiver> current=pc.getTransceivers();List<CallTrackTopology.Track> tracks=new ArrayList<>();
        for(RtpTransceiver tx:current){RtpSender sender=tx.getSender();RtpReceiver receiver=tx.getReceiver();
            MediaStreamTrack localTrack=sender.track(),remoteTrack=receiver.track();String receiverId=receiver.id(),mid=tx.getMid();
            tracks.add(new CallTrackTopology.Track(mid,sender.id(),localTrack==null?null:localTrack.kind(),receiverId,
                    remoteTrack==null?null:remoteTrack.kind(),String.valueOf(tx.getCurrentDirection()),Objects.equals(remoteEvents.get(receiverId),mid)&&remoteEvents.containsKey(receiverId)));
        }
        int remote=CallTrackTopology.validate(tracks,senderId,binding);
        if(enable){check();current.get(remote).getReceiver().track().setEnabled(true);}
    }
    public void negotiationComplete()throws Exception{validate(false);}
    public void prepareMuted()throws Exception{normalSpeaker();validate(true);check();pc.setAudioRecording(true);check();pc.setAudioPlayout(true);check();}
    private void normalSpeaker()throws IOException{
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(audio==null||audio.getMode()!=AudioManager.MODE_NORMAL||!audio.isSpeakerphoneOn())throw new IOException("MEDIA_CALL_ROUTE_UNVERIFIED");
    }
    private void set(SessionDescription value,boolean isLocal)throws Exception{
        check();RtcAwait<Boolean> applied=new RtcAwait<>();SdpObserver callback=new Adapter(){public void onSetSuccess(){applied.succeed(true);}public void onSetFailure(String ignored){applied.fail("MEDIA_CALL_SDP_FAILED");}};
        if(isLocal)pc.setLocalDescription(callback,value);else pc.setRemoteDescription(callback,value);applied.get(cancel,clock,10000);check();
    }
    static SessionDescription parse(JSONObject result,String type)throws Exception{
        JSONObject value=result.getJSONObject("sessionDescription");Object raw=value.opt("sdp");
        if(!type.equals(value.optString("type"))||!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>90000)throw new IOException("MEDIA_CALL_SDP_INVALID");
        return new SessionDescription(SessionDescription.Type.fromCanonicalForm(type),(String)raw);
    }
    private static JSONObject description(SessionDescription value,String type)throws Exception{
        if(value==null||!type.equals(value.type.canonicalForm())||value.description==null||value.description.isEmpty()||value.description.length()>90000)throw new IOException("MEDIA_CALL_SDP_INVALID");
        return new JSONObject().put("type",type).put("sdp",value.description);
    }
    static void noError(JSONObject result)throws IOException{
        Object error=result.opt("errorCode");if(error!=null&&error!=JSONObject.NULL&&(!(error instanceof String)||!((String)error).isEmpty()))throw new IOException("MEDIA_CALL_REMOTE_ERROR");
    }
    static boolean validMid(String mid){return mid!=null&&mid.matches("[A-Za-z0-9_-]{1,64}");}
    private void check()throws IOException{cancel.check();}
    public void close()throws Exception{
        // 按依赖逆序清理；任一步无法确认时保留后续依赖，外层不能释放租约。
        drainCallbacks();
        AudioRelease audio=module==null?null:new AudioRelease(module);
        if(pc!=null){pc.setAudioRecording(false);pc.setAudioPlayout(false);pc.close();pc.dispose();pc=null;}
        if(recording||playing)throw new IOException("MEDIA_CALL_AUDIO_THREAD_RELEASE_UNCONFIRMED");
        remoteEvents.clear();
        if(local!=null){local.dispose();local=null;}if(source!=null){source.dispose();source=null;}
        if(factory!=null){factory.dispose();factory=null;}
        if(module!=null){module.release();audio.finish();module=null;}
    }
    /** 仅在所有native使用者销毁后调用；固定库的stop路径不覆盖init-only对象。 */
    static final class AudioRelease {
        final DeviceRelease input,output;
        AudioRelease(JavaAudioDeviceModule module)throws Exception{
            input=new DeviceRelease(module.audioInput,true);
            output=new DeviceRelease(module.audioOutput,false);
        }
        void finish()throws Exception{
            input.capture();output.capture();
            input.requireStopped();output.requireStopped();
            Exception failure=null;
            try{input.finish();}catch(Exception e){failure=e;}
            try{output.finish();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure!=null)throw failure;
        }
    }
    static final class DeviceRelease {
        private final Object device;
        private final boolean input;
        private final Field hardware,thread;
        private final java.lang.reflect.Method release;
        private final Set<Object> objects=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        final Set<Thread> threads=Collections.newSetFromMap(new IdentityHashMap<Thread,Boolean>());
        DeviceRelease(Object device,boolean input)throws Exception{
            this.device=device;this.input=input;
            Class<?> expected=Class.forName(input?"org.webrtc.audio.WebRtcAudioRecord":"org.webrtc.audio.WebRtcAudioTrack");
            if(device==null||device.getClass()!=expected)throw invalid();
            hardware=expected.getDeclaredField(input?"audioRecord":"audioTrack");
            thread=expected.getDeclaredField("audioThread");
            release=expected.getDeclaredMethod("releaseAudioResources");
            if(hardware.getType()!=(input?android.media.AudioRecord.class:android.media.AudioTrack.class)
                    ||!Thread.class.isAssignableFrom(thread.getType())||release.getReturnType()!=void.class
                    ||Modifier.isStatic(hardware.getModifiers())||Modifier.isStatic(thread.getModifiers())
                    ||Modifier.isStatic(release.getModifiers())||!Modifier.isPrivate(release.getModifiers()))throw invalid();
            hardware.setAccessible(true);thread.setAccessible(true);release.setAccessible(true);capture();
        }
        void capture()throws Exception{
            Object raw=hardware.get(device);if(raw!=null)objects.add(raw);
            Thread running=(Thread)thread.get(device);if(running!=null)threads.add(running);
        }
        void requireStopped()throws IOException{
            for(Thread running:threads)if(running.isAlive())throw new IOException("MEDIA_CALL_AUDIO_THREAD_RELEASE_UNCONFIRMED");
        }
        void finish()throws Exception{
            capture();requireStopped();
            try{release.invoke(device);}
            catch(java.lang.reflect.InvocationTargetException e){throw new IOException("MEDIA_CALL_AUDIO_OBJECT_RELEASE_UNCONFIRMED",e.getCause());}
            capture();requireStopped();
            if(hardware.get(device)!=null)throw invalid();
            for(Object raw:objects){
                int state=input?((android.media.AudioRecord)raw).getState():((android.media.AudioTrack)raw).getState();
                if(state!=0)throw new IOException("MEDIA_CALL_AUDIO_OBJECT_RELEASE_UNCONFIRMED");
            }
        }
        private static IOException invalid(){return new IOException("MEDIA_CALL_AUDIO_RELEASE_CONTRACT_INVALID");}
    }
    private static class Adapter implements SdpObserver {
        public void onCreateSuccess(SessionDescription value){}public void onSetSuccess(){}
        public void onCreateFailure(String detail){}public void onSetFailure(String detail){}
    }
}
