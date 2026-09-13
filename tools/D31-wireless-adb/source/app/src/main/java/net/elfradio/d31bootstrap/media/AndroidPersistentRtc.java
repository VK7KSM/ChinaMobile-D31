package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import static net.elfradio.d31bootstrap.media.AndroidPersistentMediaPeer.*;

/** JNI及Camera控制只在持久peer串行线程；跨枚举仅保存稳定ID。 */
final class AndroidPersistentRtc implements AndroidPersistentMediaPeer.Backend {
    private final Context context;private final File apk,cache;private final String hash;private final MediaCapture.Clock clock;
    private AndroidPersistentMediaPeer owner;private Cancellation cancel;
    private PeerConnectionFactory factory;private PeerConnection pc;private JavaAudioDeviceModule module;private EglBase egl;
    private AudioSource audioSource;private org.webrtc.AudioTrack audioTrack;private VideoSource videoSource;private VideoTrack videoTrack;
    private CameraVideoCapturer camera;private SurfaceTextureHelper texture;private PersistentSoftwareMedia software;
    private final DownlinkTrackBinding binding=new DownlinkTrackBinding();private volatile String expectedMid;
    private final Map<String,String> senders=new HashMap<>(),sourceIds=new HashMap<>();
    private final ConcurrentHashMap<String,String> remoteEvents=new ConcurrentHashMap<>();
    private Object input,output;private Field record,track,recordThread,playThread,micMute,speakerMute,useAudioRecord;
    private final Set<Thread> oldThreads=Collections.newSetFromMap(new ConcurrentHashMap<Thread,Boolean>());
    private final Set<Thread> cameraThreads=Collections.newSetFromMap(new ConcurrentHashMap<Thread,Boolean>());
    private final Object callbackLock=new Object();private volatile boolean closing;private int callbacks;
    private volatile boolean stopped,released;private volatile Cycle cycle=new Cycle(0,"prepare","front");
    private final Object muteLock=new Object();private long muteGeneration,seenMuteGeneration=-1,confirmedMuteGeneration=-1;
    private boolean focusOwned,remoteEnabled;
    private Object actualOutput;
    private volatile Thread outputCallbackThread;
    private volatile String outputIdentity="{}";
    void focusOwned(boolean owned){synchronized(muteLock){if(focusOwned!=owned){focusOwned=owned;revokeMute();}}}
    long outputGeneration(){synchronized(muteLock){return muteGeneration;}}
    void requireFocus()throws IOException {synchronized(muteLock){require(focusOwned,"MEDIA_PERSISTENT_FOCUS_NOT_OWNED");}}
    void requireReleased()throws IOException {require(released,"MEDIA_PERSISTENT_RELEASE_UNCONFIRMED");}
    private void revokeMute(){muteGeneration++;seenMuteGeneration=-1;confirmedMuteGeneration=-1;}
    void requireMutedOutput()throws Exception {synchronized(muteLock){
        require(!stopped&&!closing&&focusOwned,"MEDIA_PERSISTENT_FOCUS_NOT_OWNED");
        require(actualOutput!=null&&track.get(output)==actualOutput&&playing()&&speakerMute.getBoolean(output)
                &&confirmedMuteGeneration==muteGeneration,"MEDIA_PERSISTENT_MUTED_OUTPUT_UNCONFIRMED");
        Thread thread=outputCallbackThread;
        require(thread!=null&&thread.isAlive(),"MEDIA_PERSISTENT_OUTPUT_THREAD_UNCONFIRMED");
    }}
    void awaitMutedOutput(long timeoutMs)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for(;;){cancel.check();try{requireMutedOutput();return;}catch(IOException pending){
            if(System.nanoTime()>=deadline)throw pending;
            TimeUnit.MILLISECONDS.sleep(10);
        }}
    }
    static final class Cycle {
        final long operation;final String mode;volatile String facing;
        volatile CameraSelection selectedCamera;
        volatile boolean capture,playback,video,captureSeen,playbackSeen,videoSeen;
        volatile String inputIdentity="{}",outputIdentity="{}";
        final AppMediaPcmStats capturePcm=new AppMediaPcmStats(16000),playbackPcm=new AppMediaPcmStats(48000);
        Cycle(long operation,String mode,String facing){this.operation=operation;this.mode=mode;this.facing=facing;}
    }
    AndroidPersistentRtc(Context context,File apk,String hash,File cache,MediaCapture.Clock clock)throws Exception{
        require(context!=null&&apk!=null&&cache!=null&&clock!=null&&hash!=null&&hash.matches("[a-fA-F0-9]{64}"),"MEDIA_PERSISTENT_DEPENDENCY_MISSING");
        this.context=context;this.apk=apk;this.hash=hash;this.cache=cache;this.clock=clock;
    }
    public void open(AndroidPersistentMediaPeer owner,Cancellation cancel)throws Exception{
        this.owner=owner;this.cancel=cancel;cancel.check();MediaReadiness.requireApplicationIdentity(context,AppMediaContract.PACKAGE);
        ApkMediaLibrary library=new ApkMediaLibrary(apk,hash,cache);require(library.load("jingle_peerconnection_so"),"MEDIA_NATIVE_LOAD_FAILED");
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).setNativeLibraryLoader(library).createInitializationOptions());
        software=new PersistentSoftwareMedia(()->owner.fail("MEDIA_PERSISTENT_SOFTWARE_FORMAT_INVALID"));
        module=JavaAudioDeviceModule.builder(context).setAudioSource(1).setAudioFormat(2).setInputSampleRate(16000).setOutputSampleRate(48000)
            .setUseStereoInput(false).setUseStereoOutput(false).setEnableVolumeLogger(false)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioBufferCallback(this::buffer)
            .setSamplesReadyCallback(s->pcm(true,s.getData(),s.getAudioFormat(),s.getChannelCount(),s.getSampleRate()))
            .setPlaybackSamplesReadyCallback(s->pcm(false,s.getData(),s.getAudioFormat(),s.getChannelCount(),s.getSampleRate()))
            .setAudioRecordStateCallback(new JavaAudioDeviceModule.AudioRecordStateCallback(){
                public void onWebRtcAudioRecordStart(){oldThreads.add(Thread.currentThread());}
                public void onWebRtcAudioRecordStop(){cycle.captureSeen=false;}
            }).setAudioTrackStateCallback(new JavaAudioDeviceModule.AudioTrackStateCallback(){
                public void onWebRtcAudioTrackStart(){outputCallbackThread=Thread.currentThread();oldThreads.add(outputCallbackThread);}
                public void onWebRtcAudioTrackStop(){outputCallbackThread=null;cycle.playbackSeen=false;}
            }).setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback(){
                public void onWebRtcAudioRecordInitError(String e){audioFailure("MEDIA_PERSISTENT_RECORD_INIT_FAILED");}
                public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode c,String e){audioFailure("MEDIA_PERSISTENT_RECORD_START_FAILED");}
                public void onWebRtcAudioRecordError(String e){audioFailure("MEDIA_PERSISTENT_RECORD_FAILED");}
            }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback(){
                public void onWebRtcAudioTrackInitError(String e){audioFailure("MEDIA_PERSISTENT_PLAY_INIT_FAILED");}
                public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode c,String e){audioFailure("MEDIA_PERSISTENT_PLAY_START_FAILED");}
                public void onWebRtcAudioTrackError(String e){audioFailure("MEDIA_PERSISTENT_PLAY_FAILED");}
            }).createAudioDeviceModule();
        bind(module);micMute.setBoolean(input,true);speakerMute.setBoolean(output,true);module.setAudioRecordEnabled(false);
        egl=EglBase.create();cancel.check();
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(module).setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true))
            .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext())).createPeerConnectionFactory();
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()));
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;pc=factory.createPeerConnection(config,observer());require(pc!=null,"MEDIA_PEER_INIT_FAILED");
        pc.setAudioRecording(true);pc.setAudioPlayout(true);cancel.check();
        MediaConstraints constraints=new MediaConstraints();for(String key:new String[]{"googEchoCancellation","googNoiseSuppression","googHighpassFilter"})constraints.mandatory.add(new MediaConstraints.KeyValuePair(key,"true"));
        audioSource=factory.createAudioSource(constraints);audioTrack=factory.createAudioTrack("audio",audioSource);audioTrack.setEnabled(true);add(audioTrack,"audio");
        videoSource=factory.createVideoSource(false);videoTrack=factory.createVideoTrack("video",videoSource);videoTrack.setEnabled(true);add(videoTrack,"video");
        software.start(videoSource.getCapturerObserver());cancel.check();
    }
    private void add(MediaStreamTrack source,String kind)throws Exception{RtpTransceiver tx=pc.addTransceiver(source,new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
        require(tx!=null,"MEDIA_TRANSCEIVER_FAILED");String id=tx.getSender().id();senders.put(id,kind);sourceIds.put(id,source.id());
        if("video".equals(kind)){List<RtpCapabilities.CodecCapability> codecs=new ArrayList<>(factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs);
            Collections.sort(codecs,(a,b)->Boolean.compare(!"H264".equalsIgnoreCase(a.name),!"H264".equalsIgnoreCase(b.name)));tx.setCodecPreferences(codecs);}}
    void bind(JavaAudioDeviceModule audio)throws Exception{input=audio.audioInput;output=audio.audioOutput;
        record=field(input,"audioRecord");track=field(output,"audioTrack");recordThread=field(input,"audioThread");playThread=field(output,"audioThread");
        useAudioRecord=field(input,"useAudioRecord");
        micMute=field(input,"microphoneMute");speakerMute=field(output,"speakerMute");
        require(micMute.getType()==boolean.class&&speakerMute.getType()==boolean.class&&java.lang.reflect.Modifier.isVolatile(micMute.getModifiers())&&java.lang.reflect.Modifier.isVolatile(speakerMute.getModifiers()),"MEDIA_PERSISTENT_MUTE_CONTRACT_INVALID");}
    private static Field field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private boolean enter(){synchronized(callbackLock){if(closing)return false;callbacks++;return true;}}
    void audioFailure(String code){Cycle c=cycle;if(!stopped&&(code.startsWith("MEDIA_PERSISTENT_PLAY_")||c.operation==0||owner.operationAllowed(c.operation)))owner.fail(code);}
    private void leave(){synchronized(callbackLock){callbacks--;callbackLock.notifyAll();}}
    long buffer(ByteBuffer buffer,int format,int channels,int rate,int count,long at){
        if(count==0){Cycle current=cycle;current.captureSeen=false;PersistentSoftwareMedia sw=software;
            if(sw==null){PersistentSoftwareMedia.zero(buffer);return at;}return sw.audio(buffer,format,channels,rate,count,at,false);}
        if(!enter()){PersistentSoftwareMedia.zero(buffer);return at;}
        Cycle c=cycle;
        try{boolean permitted=!stopped&&owner.operationAllowed(c.operation)&&c.capture;long result=software.audio(buffer,format,channels,rate,count,at,permitted);
            if(!owner.operationAllowed(c.operation))PersistentSoftwareMedia.zero(buffer);
            if(c==cycle&&permitted&&owner.operationAllowed(c.operation)&&c.capture&&count==320&&recording()){
                if(!c.captureSeen){c.inputIdentity=identity(true).toString();c.captureSeen=true;owner.changed();}}
            return result;
        }catch(Exception|LinkageError e){PersistentSoftwareMedia.zero(buffer);if(owner.operationAllowed(c.operation))owner.fail("MEDIA_PERSISTENT_CAPTURE_OBSERVATION_FAILED");return at;}finally{leave();}
    }
    private void pcm(boolean capture,byte[] bytes,int format,int channels,int rate){if(!enter())return;Cycle c=cycle;
        try{if(stopped)return;
            if(!capture&&playing())observeMutedOutput(bytes,format,channels,rate);
            if(!owner.operationAllowed(c.operation)||!(capture?c.capture:c.playback))return;
            if(capture){if(c.captureSeen)c.capturePcm.accept(bytes,format,channels,rate,true);}
            else if(playing()){c.outputIdentity=outputIdentity;c.playbackPcm.accept(bytes,format,channels,rate,true);
                if(!c.playbackSeen){c.playbackSeen=true;owner.changed();}}
        }catch(Exception|LinkageError e){if(!capture||owner.operationAllowed(c.operation))owner.fail("MEDIA_PERSISTENT_PCM_OBSERVATION_FAILED");}finally{leave();}}
    private void observeMutedOutput(byte[] bytes,int format,int channels,int rate)throws Exception{
        synchronized(muteLock){
            Object actual=track.get(output);
            require(actual!=null,"MEDIA_PERSISTENT_IDENTITY_MISSING");
            if(actualOutput==null){outputIdentity=identity(false).toString();actualOutput=actual;revokeMute();}
            require(actualOutput==actual,"MEDIA_PERSISTENT_OUTPUT_IDENTITY_CHANGED");
            require(outputCallbackThread==Thread.currentThread(),"MEDIA_PERSISTENT_OUTPUT_THREAD_UNCONFIRMED");
            confirmMutedOutput(bytes,format,channels,rate);
        }
    }
    private void confirmMutedOutput(byte[] bytes,int format,int channels,int rate)throws Exception{
        synchronized(muteLock){
            boolean zero=bytes!=null&&bytes.length==960&&format==2&&channels==1&&rate==48000;
            if(zero)for(byte value:bytes)if(value!=0){zero=false;break;}
            // 回调在write返回后；跳过切门后的首帧，避免跨代在途帧确认新静音代次。
            if(zero&&speakerMute.getBoolean(output)){
                if(seenMuteGeneration==muteGeneration)confirmedMuteGeneration=muteGeneration;
                seenMuteGeneration=muteGeneration;
            }else {
                seenMuteGeneration=-1;
                if(speakerMute.getBoolean(output))revokeMute();
            }
        }
    }
    private void outputMute(boolean muted)throws Exception{
        synchronized(muteLock){
            if(output==null)return;
            boolean prior=speakerMute.getBoolean(output);
            if(prior==muted)return;
            if(!muted)revokeMute();
            speakerMute.setBoolean(output,muted);
            if(muted)revokeMute();
        }
    }
    private JSONObject identity(boolean capture)throws Exception{Object raw=(capture?record:track).get(capture?input:output);require(raw!=null,"MEDIA_PERSISTENT_IDENTITY_MISSING");
        int session=capture?((android.media.AudioRecord)raw).getAudioSessionId():((android.media.AudioTrack)raw).getAudioSessionId();
        int rate=capture?((android.media.AudioRecord)raw).getSampleRate():((android.media.AudioTrack)raw).getSampleRate();
        int channels=capture?((android.media.AudioRecord)raw).getChannelCount():((android.media.AudioTrack)raw).getChannelCount();
        int format=capture?((android.media.AudioRecord)raw).getAudioFormat():((android.media.AudioTrack)raw).getAudioFormat();
        int sourceOrStream=capture?((android.media.AudioRecord)raw).getAudioSource():((android.media.AudioTrack)raw).getStreamType();
        require(session>0&&rate==(capture?16000:48000)&&channels==1&&format==2&&sourceOrStream==(capture?1:3),"MEDIA_PERSISTENT_IDENTITY_INVALID");
        return new JSONObject().put("pid",android.os.Process.myPid()).put("session",session).put("sample_rate",rate).put("channels",channels).put("format",format).put(capture?"source":"stream",sourceOrStream);}
    boolean recording()throws Exception{android.media.AudioRecord raw=input==null?null:(android.media.AudioRecord)record.get(input);return raw!=null&&raw.getRecordingState()==android.media.AudioRecord.RECORDSTATE_RECORDING;}
    boolean playing()throws Exception{android.media.AudioTrack raw=output==null?null:(android.media.AudioTrack)track.get(output);return raw!=null&&raw.getPlayState()==android.media.AudioTrack.PLAYSTATE_PLAYING;}
    public boolean idle()throws Exception{
        for(Thread thread:cameraThreads)if(thread.isAlive())return false;
        return (input==null||!useAudioRecord.getBoolean(input))&&!recording()&&(!playing()||speakerMute.getBoolean(output))&&camera==null;
    }
    public void activate(long operation,String mode,String facing)throws Exception{
        owner.checkOperation(operation);
        if(capture(mode))permission(Manifest.permission.RECORD_AUDIO);
        if("video".equals(mode))permission(Manifest.permission.CAMERA);
        Cycle next=new Cycle(operation,mode,facing);next.capture=capture(mode);next.playback=playback(mode);next.video="video".equals(mode);cycle=next;
        owner.checkOperation(operation);micMute.setBoolean(input,!next.capture);
        owner.checkOperation(operation);outputMute(!next.playback);
        owner.checkOperation(operation);module.setAudioRecordEnabled(next.capture);
        owner.checkOperation(operation);
        if(next.video)startCamera(next);owner.checkOperation(operation);
    }
    private void permission(String permission)throws Exception{require(context.checkPermission(permission,android.os.Process.myPid(),android.os.Process.myUid())==PackageManager.PERMISSION_GRANTED,"MEDIA_PERMISSION_MISSING");}
    public boolean ready(long op,String mode){Cycle c=cycle;try{return !stopped&&owner.operationAllowed(op)&&c.operation==op&&(!capture(mode)||(c.captureSeen&&recording()))&&(!playback(mode)||(c.playbackSeen&&playing()))&&(!"video".equals(mode)||c.videoSeen);}
        catch(Exception invalid){return false;}}
    public void invalidateOperation(long operation){Cycle c=cycle;if(c.operation==operation){c.capture=false;c.playback=false;c.video=false;}}
    public void softStop(){softStop(cycle);}
    private void softStop(Cycle c){c.capture=false;c.playback=false;c.video=false;
        try{if(input!=null)micMute.setBoolean(input,true);outputMute(true);}catch(Exception e){stopped=true;}}
    public void stop(long deadline)throws Exception{
        softStop();if(module!=null)module.setAudioRecordEnabled(false);stopCamera();
        while(true){require(System.nanoTime()<deadline,"MEDIA_PERSISTENT_HARDWARE_STOP_TIMEOUT");
            synchronized(callbackLock){if(idle()&&callbacks==0)break;}
            TimeUnit.MILLISECONDS.sleep(10);}
        cycle.captureSeen=false;cycle.playbackSeen=false;cycle.videoSeen=false;
    }
    private void setRemote(boolean enabled)throws Exception{setRemote(enabled,0);}
    private void setRemote(boolean enabled,long operation)throws Exception{
        if(operation>0)owner.checkOperation(operation);
        MediaStreamTrack selected=null;Set<String> mids=new HashSet<>();
        // 本地sendonly也有receiver包装；只在这一轮枚举内使用订阅绑定的真实接收轨。
        for(RtpTransceiver tx:pc.getTransceivers()){
            if(operation>0)owner.checkOperation(operation);
            String mid=tx.getMid(),direction=String.valueOf(tx.getCurrentDirection());
            require(validMid(mid)&&mids.add(mid),"MEDIA_PERSISTENT_REMOTE_INVALID");
            if(!binding.expected(mid)){
                require(senders.containsKey(tx.getSender().id())&&"SEND_ONLY".equals(direction),"MEDIA_PERSISTENT_REMOTE_INVALID");continue;
            }
            RtpReceiver receiver=tx.getReceiver();MediaStreamTrack raw=receiver.track();
            require(selected==null&&remoteEvents.size()==1&&binding.matches(mid,direction,raw==null?null:raw.kind(),
                    Objects.equals(remoteEvents.get(receiver.id()),mid)),"MEDIA_PERSISTENT_REMOTE_INVALID");selected=raw;
        }
        require(selected!=null,"MEDIA_PERSISTENT_REMOTE_INVALID");
        if(operation>0)owner.checkOperation(operation);
        selected.setEnabled(enabled);require(selected.enabled()==enabled,"MEDIA_PERSISTENT_REMOTE_ENABLE_FAILED");
        if(operation>0)owner.checkOperation(operation);
    }
    static final class CameraSelection {
        final String name,facing;final int count;
        CameraSelection(String name,String facing,int count){this.name=name;this.facing=facing;this.count=count;}
    }
    static CameraSelection selectCamera(String[] names,int[] facings,String requested)throws Exception{
        require(names!=null&&facings!=null&&names.length==facings.length&&names.length<=16&&validFacing(requested),"MEDIA_CAMERA_FACING_UNAVAILABLE");
        int index=AndroidMediaDevice.selectCamera(facings,requested);
        require(index>=0&&names[index]!=null&&!names[index].isEmpty()&&(facings[index]==0||facings[index]==1),"MEDIA_CAMERA_FACING_UNAVAILABLE");
        return new CameraSelection(names[index],facings[index]==1?"front":"back",names.length);
    }
    static class CameraDiscovery {
        int count(){return android.hardware.Camera.getNumberOfCameras();}
        android.hardware.Camera.CameraInfo info(int index){
            android.hardware.Camera.CameraInfo info=new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(index,info);return info;
        }
    }
    static CameraSelection selectCamera(CameraDiscovery devices,String requested)throws Exception{
        require(validFacing(requested),"MEDIA_CAMERA_FACING_UNAVAILABLE");
        int count=devices.count();require(count>0&&count<=16,"MEDIA_CAMERA_FACING_UNAVAILABLE");
        String[] names=new String[count];int[] facings=new int[count];
        for(int i=0;i<count;i++){
            android.hardware.Camera.CameraInfo info=devices.info(i);
            require(info!=null&&(info.facing==0||info.facing==1),"MEDIA_CAMERA_FACING_UNAVAILABLE");
            facings[i]=info.facing;
            // 固定125.6422.07的getDeviceName格式；SDK仍在构造及打开时验证名称与索引。
            names[i]="Camera "+i+", Facing "+(info.facing==1?"front":"back")+", Orientation "+info.orientation;
        }
        return selectCamera(names,facings,requested);
    }
    private void startCamera(Cycle c)throws Exception{
        final long cameraStart=android.os.SystemClock.elapsedRealtime();
        owner.checkOperation(c.operation);Camera1Enumerator devices=new Camera1Enumerator(false);CameraSelection selected=selectCamera(new CameraDiscovery(),c.facing);
        android.util.Log.i("D31CameraTiming","selected_ms="+(android.os.SystemClock.elapsedRealtime()-cameraStart));
        owner.checkOperation(c.operation);camera=devices.createCapturer(selected.name,null);require(camera!=null,"MEDIA_CAMERA_UNAVAILABLE");
        android.util.Log.i("D31CameraTiming","capturer_ms="+(android.os.SystemClock.elapsedRealtime()-cameraStart));
        c.selectedCamera=selected;c.facing=selected.facing;
        owner.checkOperation(c.operation);texture=SurfaceTextureHelper.create("d31-persistent-camera",egl.getEglBaseContext());require(texture!=null,"MEDIA_CAMERA_THREAD_UNAVAILABLE");
        cameraThreads.add(texture.getHandler().getLooper().getThread());
        owner.checkOperation(c.operation);software.camera(true);owner.checkOperation(c.operation);
        camera.initialize(texture,context,new CapturerObserver(){
            public void onCapturerStarted(boolean success){if(!success&&cycle==c&&c.video&&owner.operationAllowed(c.operation))owner.fail("MEDIA_CAMERA_START_FAILED");}
            public void onCapturerStopped(){c.videoSeen=false;}
            public void onFrameCaptured(VideoFrame frame){if(!enter())return;try{if(stopped||cycle!=c||!c.video||!owner.operationAllowed(c.operation))return;software.cameraFrame(frame);if(!c.videoSeen){c.videoSeen=true;android.util.Log.i("D31CameraTiming","first_frame_ms="+(android.os.SystemClock.elapsedRealtime()-cameraStart));owner.changed();}}finally{leave();}}
        });android.net.ConnectivityManager connectivity=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo network=connectivity==null?null:connectivity.getActiveNetworkInfo();
        boolean local=network!=null&&network.isConnected()&&(network.getType()==android.net.ConnectivityManager.TYPE_WIFI||network.getType()==android.net.ConnectivityManager.TYPE_ETHERNET);
        owner.checkOperation(c.operation);android.util.Log.i("D31CameraTiming","start_capture_ms="+(android.os.SystemClock.elapsedRealtime()-cameraStart));camera.startCapture(local?640:320,local?480:240,local?15:10);owner.checkOperation(c.operation);
        for(RtpTransceiver tx:pc.getTransceivers())if("video".equals(senders.get(tx.getSender().id()))){owner.checkOperation(c.operation);RtpParameters params=tx.getSender().getParameters();for(RtpParameters.Encoding e:params.encodings){e.maxBitrateBps=local?600000:180000;e.maxFramerate=local?15:10;}owner.checkOperation(c.operation);require(tx.getSender().setParameters(params),"MEDIA_VIDEO_PARAMETERS_FAILED");}
    }
    private void stopCamera()throws Exception{if(camera!=null){camera.stopCapture();camera.dispose();camera=null;}if(texture!=null){texture.dispose();texture=null;}if(software!=null)software.camera(false);}
    public void switchCamera(long operation,String facing)throws Exception{owner.checkOperation(operation);switchCamera(operation,selectCamera(new CameraDiscovery(),facing));}
    void switchCamera(long operation,CameraSelection selected)throws Exception{owner.checkOperation(operation);Cycle c=cycle;require(camera!=null&&c.operation==operation&&c.video,"MEDIA_CAMERA_NOT_ACTIVE");
        if(c.selectedCamera!=null&&selected.name.equals(c.selectedCamera.name)){c.selectedCamera=selected;c.facing=selected.facing;return;}
        RtcAwait<Boolean> switched=new RtcAwait<>();
        owner.checkOperation(operation);camera.switchCamera(new CameraVideoCapturer.CameraSwitchHandler(){public void onCameraSwitchDone(boolean front){switched.succeed(front=="front".equals(selected.facing));}public void onCameraSwitchError(String e){switched.fail("MEDIA_CAMERA_SWITCH_FAILED");}},selected.name);
        require(switched.get(cancel,clock,1500),"MEDIA_CAMERA_FACING_UNCONFIRMED");owner.checkOperation(operation);c.selectedCamera=selected;c.facing=selected.facing;}
    private PeerConnection.Observer observer(){return new PeerConnection.Observer(){
        public void onSignalingChange(PeerConnection.SignalingState s){}
        public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(stopped)return;owner.ice(s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED);if(s==PeerConnection.IceConnectionState.FAILED)owner.fail("MEDIA_PERSISTENT_ICE_FAILED");}
        public void onIceConnectionReceivingChange(boolean b){}public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
        public void onIceCandidate(IceCandidate c){}public void onIceCandidatesRemoved(IceCandidate[] c){}
        public void onAddStream(MediaStream s){}public void onRemoveStream(MediaStream s){}
        public void onDataChannel(DataChannel c){owner.fail("MEDIA_PERSISTENT_DATA_CHANNEL_FORBIDDEN");}public void onRenegotiationNeeded(){}
        public void onAddTrack(RtpReceiver r,MediaStream[] s){if(!enter())return;try{if(r.track()!=null)r.track().setEnabled(false);}catch(Exception|LinkageError e){owner.fail("MEDIA_PERSISTENT_REMOTE_INVALID");}finally{leave();}}
        public void onTrack(RtpTransceiver tx){if(!enter())return;try{RtpReceiver receiver=tx.getReceiver();MediaStreamTrack raw=receiver.track();String mid=tx.getMid();
            require(raw!=null&&"audio".equals(raw.kind())&&Objects.equals(expectedMid,mid),"MEDIA_PERSISTENT_REMOTE_INVALID");raw.setEnabled(false);
            String prior=remoteEvents.putIfAbsent(receiver.id(),mid);require((prior==null||prior.equals(mid))&&remoteEvents.size()==1,"MEDIA_PERSISTENT_REMOTE_INVALID");
        }catch(Exception|LinkageError e){owner.fail("MEDIA_PERSISTENT_REMOTE_INVALID");}finally{leave();}}
    };}
    public JSONObject publish(JSONObject result)throws Exception{
        noError(result);RtcAwait<SessionDescription> offer=new RtcAwait<>();pc.createOffer(new Adapter(){public void onCreateSuccess(SessionDescription s){offer.succeed(s);}public void onCreateFailure(String e){offer.fail("MEDIA_RTC_OFFER_FAILED");}},new MediaConstraints());
        set(offer.get(cancel,clock,10000),true);JSONArray tracks=new JSONArray();Set<String> kinds=new HashSet<>();
        for(RtpTransceiver tx:pc.getTransceivers()){String id=tx.getSender().id(),kind=senders.get(id);MediaStreamTrack raw=tx.getSender().track();
            require(kind!=null&&raw!=null&&raw.id().equals(sourceIds.get(id))&&kinds.add(kind)&&validMid(tx.getMid()),"MEDIA_PERSISTENT_PUBLISH_INVALID");tracks.put(new JSONObject().put("mid",tx.getMid()).put("trackName",kind));}
        require(kinds.size()==2,"MEDIA_PERSISTENT_PUBLISH_INVALID");return new JSONObject().put("sessionDescription",description(pc.getLocalDescription())).put("tracks",tracks);
    }
    public void applyPublish(JSONObject result)throws Exception{noError(result);set(parse(result,"answer"),false);require(pc.signalingState()==PeerConnection.SignalingState.STABLE,"MEDIA_RTC_SIGNALING_INVALID");}
    public JSONObject subscribe(JSONObject result)throws Exception{
        binding.subscribeResult(result);expectedMid=result.getJSONArray("tracks").getJSONObject(0).getString("mid");
        if(result.has("sessionDescription")){String type=result.getJSONObject("sessionDescription").optString("type");
            if("offer".equals(type)){require(pc.signalingState()==PeerConnection.SignalingState.STABLE,"MEDIA_RTC_SIGNALING_INVALID");set(parse(result,"offer"),false);
                for(RtpTransceiver tx:pc.getTransceivers()){boolean local=senders.containsKey(tx.getSender().id()),remote=binding.expected(tx.getMid());require(local||remote,"MEDIA_PERSISTENT_TOPOLOGY_INVALID");
                    require(tx.setDirection(local?(remote?RtpTransceiver.RtpTransceiverDirection.SEND_RECV:RtpTransceiver.RtpTransceiverDirection.SEND_ONLY):RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),"MEDIA_RTC_DIRECTION_FAILED");}
                RtcAwait<SessionDescription> answer=new RtcAwait<>();pc.createAnswer(new Adapter(){public void onCreateSuccess(SessionDescription s){answer.succeed(s);}public void onCreateFailure(String e){answer.fail("MEDIA_RTC_ANSWER_FAILED");}},new MediaConstraints());
                set(answer.get(cancel,clock,10000),true);validate();return new JSONObject().put("sessionDescription",description(pc.getLocalDescription()));}
            require("answer".equals(type)&&pc.signalingState()==PeerConnection.SignalingState.HAVE_LOCAL_OFFER,"MEDIA_RTC_UNEXPECTED_ANSWER");set(parse(result,"answer"),false);}
        validate();return null;
    }
    public void validate()throws Exception{cancel.check();require(pc.signalingState()==PeerConnection.SignalingState.STABLE,"MEDIA_RTC_SIGNALING_INVALID");
        Set<String> mids=new HashSet<>(),ids=new HashSet<>();int locals=0,remotes=0;List<RtpTransceiver> txs=pc.getTransceivers();require(txs.size()>=2&&txs.size()<=3,"MEDIA_PERSISTENT_TOPOLOGY_INVALID");
        for(RtpTransceiver tx:txs){RtpSender s=tx.getSender();RtpReceiver r=tx.getReceiver();String mid=tx.getMid(),rid=r.id(),kind=senders.get(s.id());MediaStreamTrack local=s.track(),remote=r.track();boolean incoming=binding.expected(mid);
            require(validMid(mid)&&mids.add(mid)&&ids.add(s.id())&&Objects.equals(local==null?null:local.id(),sourceIds.get(s.id())),"MEDIA_PERSISTENT_TOPOLOGY_INVALID");
            if(kind!=null){locals++;require(local!=null&&kind.equals(local.kind()),"MEDIA_PERSISTENT_TOPOLOGY_INVALID");}
            if(incoming){remotes++;require(binding.matches(mid,String.valueOf(tx.getCurrentDirection()),remote==null?null:remote.kind(),Objects.equals(remoteEvents.get(rid),mid)),"MEDIA_PERSISTENT_TOPOLOGY_INVALID");}
            String direction=kind!=null?(incoming?"SEND_RECV":"SEND_ONLY"):"RECV_ONLY";require((kind!=null||incoming)&&direction.equals(String.valueOf(tx.getCurrentDirection())),"MEDIA_PERSISTENT_TOPOLOGY_INVALID");}
        require(locals==2&&remotes==1,"MEDIA_PERSISTENT_TOPOLOGY_INVALID");
        if(!remoteEnabled){setRemote(true);remoteEnabled=true;}}
    private void set(SessionDescription description,boolean local)throws Exception{cancel.check();RtcAwait<Boolean> done=new RtcAwait<>();SdpObserver callback=new Adapter(){public void onSetSuccess(){done.succeed(true);}public void onSetFailure(String e){done.fail("MEDIA_RTC_SDP_FAILED");}};
        if(local)pc.setLocalDescription(callback,description);else pc.setRemoteDescription(callback,description);done.get(cancel,clock,10000);}
    static boolean validMid(String mid){return mid!=null&&mid.matches("[A-Za-z0-9_-]{1,64}");}
    static void noError(JSONObject result)throws Exception{Object e=result.opt("errorCode");require(e==null||e==JSONObject.NULL||e instanceof String&&((String)e).isEmpty(),"MEDIA_RTC_REMOTE_ERROR");}
    static SessionDescription parse(JSONObject result,String type)throws Exception{JSONObject d=result.getJSONObject("sessionDescription");require(type.equals(d.optString("type"))&&d.opt("sdp") instanceof String&&!d.getString("sdp").isEmpty()&&d.getString("sdp").length()<=90000,"MEDIA_RTC_SDP_INVALID");return new SessionDescription(SessionDescription.Type.fromCanonicalForm(type),d.getString("sdp"));}
    private static JSONObject description(SessionDescription d)throws Exception{require(d!=null&&d.description!=null&&!d.description.isEmpty()&&d.description.length()<=90000,"MEDIA_RTC_SDP_INVALID");return new JSONObject().put("type",d.type.canonicalForm()).put("sdp",d.description);}
    public JSONObject snapshot()throws Exception{Cycle c=cycle;CameraSelection selected=c.selectedCamera;PersistentSoftwareMedia sw=software;JSONObject result=new JSONObject().put("operation",c.operation).put("capture_started",c.captureSeen).put("playback_started",c.playbackSeen).put("video_started",c.videoSeen)
        .put("capture_active",recording()).put("playback_active",playing()).put("capture_requested",c.capture).put("playback_requested",c.playback)
        .put("record_object_present",input!=null&&record.get(input)!=null).put("track_object_present",output!=null&&track.get(output)!=null)
        .put("input_identity",new JSONObject(c.inputIdentity)).put("output_identity",new JSONObject(outputIdentity)).put("capture_pcm",c.capturePcm.snapshot()).put("playback_pcm",c.playbackPcm.snapshot())
        .put("software_audio_buffers",sw==null?0:sw.audioBuffers()).put("software_video_frames",sw==null?0:sw.videoFrames());
        if(selected!=null)result.put("camera",selected.facing).put("cameras",selected.count);return result;}
    public void close()throws Exception{
        stopped=true;softStop();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1750);
        synchronized(callbackLock){closing=true;while(callbacks>0){long left=deadline-System.nanoTime();require(left>0,"MEDIA_PERSISTENT_CALLBACK_RELEASE_UNCONFIRMED");TimeUnit.NANOSECONDS.timedWait(callbackLock,left);}}
        AndroidCallPeer.AudioRelease audio=module==null?null:new AndroidCallPeer.AudioRelease(module);
        stopCamera();if(pc!=null){pc.setAudioRecording(false);pc.setAudioPlayout(false);pc.close();pc.dispose();pc=null;}
        if(software!=null){software.close();software=null;}
        if(audioTrack!=null){audioTrack.dispose();audioTrack=null;}if(videoTrack!=null){videoTrack.dispose();videoTrack=null;}
        if(audioSource!=null){audioSource.dispose();audioSource=null;}if(videoSource!=null){videoSource.dispose();videoSource=null;}
        if(factory!=null){factory.dispose();factory=null;}if(module!=null){module.release();audio.finish();module=null;}
        for(Thread thread:oldThreads)require(!thread.isAlive(),"MEDIA_PERSISTENT_AUDIO_THREAD_RELEASE_UNCONFIRMED");
        for(Thread thread:cameraThreads)require(!thread.isAlive(),"MEDIA_PERSISTENT_CAMERA_THREAD_RELEASE_UNCONFIRMED");
        if(egl!=null){egl.release();egl=null;}released=true;
    }
    private static class Adapter implements SdpObserver{public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String s){}public void onSetFailure(String s){}}
}
