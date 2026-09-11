package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.URI;
import org.json.JSONObject;

/** 实际APP身份、JNI准备和会话构造；不接root私有APK路径。 */
final class AppMediaBackend implements AppMediaController.Backend,AutoCloseable {
    private final Context app;
    private AudioGuard guard;
    private boolean closed;
    AppMediaBackend(Context context){app=context;}
    private synchronized AudioGuard guard()throws Exception {
        if(closed)throw new IOException("MEDIA_APP_SERVICE_CLOSED");
        if(guard==null){AppMediaService.GuardFactory factory=AppMediaService.configuration().factory;
            if(factory==null)throw new IOException("MEDIA_AUDIO_STATE_UNKNOWN");guard=factory.create(app);}
        if(guard==null)throw new IOException("MEDIA_AUDIO_STATE_UNKNOWN");return guard;
    }
    public URI origin()throws Exception {
        URI value=AppMediaService.configuration().origin;
        if(value==null)throw new IOException("MEDIA_CONTROL_ORIGIN_NOT_CONFIGURED");return value;
    }
    private File verifiedApk(String hash)throws Exception {
        if(Build.VERSION.SDK_INT!=23)throw new IOException("MEDIA_REQUIRES_API23_APP32");
        if(android.os.Process.is64Bit())throw new IOException("MEDIA_REQUIRES_API23_APP32");
        MediaReadiness.requireApplicationIdentity(app,AppMediaContract.PACKAGE);
        File apk=MediaFiles.plain(new File(app.getApplicationInfo().sourceDir));
        ApkMediaLibrary.inspect(apk,hash,"armeabi-v7a");return apk;
    }
    static File privateDirectory(File frameworkDataDir,File frameworkDirectory)throws IOException {
        if(frameworkDataDir==null||frameworkDirectory==null||!frameworkDataDir.isAbsolute()||!frameworkDirectory.isAbsolute())
            throw new IOException("MEDIA_APP_DIRECTORY_INVALID");
        // 只在框架提供的APP私有根处解析Android父级别名，不放宽媒体子目录的链接门。
        File root=frameworkDataDir.getCanonicalFile(),directory=frameworkDirectory.getCanonicalFile();
        if(!root.isDirectory())throw new IOException("MEDIA_APP_DIRECTORY_INVALID");
        boolean contained=false;
        for(File parent=directory.getParentFile();parent!=null;parent=parent.getParentFile())
            if(root.equals(parent)){contained=true;break;}
        if(!contained)throw new IOException("MEDIA_APP_DIRECTORY_OUTSIDE_DATA");
        if(directory.exists()&&!directory.isDirectory())throw new IOException("MEDIA_APP_DIRECTORY_INVALID");
        return directory;
    }
    private File appDirectory(File frameworkDirectory)throws IOException {
        String dataDir=app.getApplicationInfo().dataDir;
        return privateDirectory(dataDir==null?null:new File(dataDir),frameworkDirectory);
    }
    public JSONObject prepare(String hash,Cancellation cancel)throws Exception {
        cancel.check();File apk=verifiedApk(hash);cancel.check();
        AudioGuard current=null;try{current=guard();}catch(Exception unknown){}
        // 仅prepare工作线程有界等待首轮结果；完成或失败均不等同空闲。
        if(current instanceof AndroidAudioOccupancy)((AndroidAudioOccupancy)current).awaitFirstSample(1500);
        cancel.check();
        JSONObject result=MediaReadiness.snapshot(app,AppMediaContract.PACKAGE,current);
        appendGuardSnapshot(result,current);cancel.check();
        boolean loaded=new ApkMediaLibrary(apk,hash,new File(appDirectory(app.getCodeCacheDir()),"media-native")).load("jingle_peerconnection_so");cancel.check();
        boolean ready=loaded&&"GRANTED".equals(result.optString("record_permission"))&&result.optBoolean("process_package_match")
                &&result.optBoolean("framework_package_match")&&"ALLOWED".equals(result.optString("record_appop"))
                &&"IDLE_OBSERVED".equals(result.optString("audio_occupancy"))&&AppMediaService.configuration().origin!=null;
        return result.put("operation","prepare").put("state",ready?"PREPARED":"PREPARED_WITH_GAPS").put("preconditions_satisfied",ready)
                .put("process_64bit",false).put("app_identity","MATCH").put("apk_hash_match",true).put("jni_loaded",loaded)
                .put("audio_record_created",false).put("network_started",false);
    }
    static void appendGuardSnapshot(JSONObject result,AudioGuard current)throws Exception {
        // 只消费脱敏缓存，不触发采样或等待；与readiness可能相邻跨越一次后台刷新。
        JSONObject snapshot;
        if(current instanceof AndroidAudioOccupancy){
            try{snapshot=((AndroidAudioOccupancy)current).snapshot();}
            catch(Exception unavailable){snapshot=new JSONObject().put("state","UNKNOWN").put("reason","SNAPSHOT_UNAVAILABLE").put("read_only",true);}
        }else snapshot=new JSONObject().put("state","UNKNOWN")
                .put("reason",current==null?"GUARD_UNAVAILABLE":"SNAPSHOT_NOT_SUPPORTED").put("read_only",true);
        result.put("audio_occupancy_snapshot",snapshot);
    }
    public LocalAudioCapture localCapture(final String hash,String id,int duration,Cancellation cancel)throws Exception {
        return new LocalAudioCapture(id,duration,android.os.Process.myPid(),android.os.Process.myUid(),new LocalAudioCapture.Factory(){
            private volatile JSONObject evidence=new JSONObject();
            private AndroidAudioOccupancy occupancyGuard;
            public JSONObject preflight(){return evidence;}
            public void requireCallsIdle()throws Exception {
                if(occupancyGuard==null)throw new IOException("MEDIA_LOCAL_AUDIO_CALL_STATE_UNKNOWN");
                requireDiagnosticCallsIdle(occupancyGuard.snapshot());
            }
            public LocalAudioCapture.Recorder open(Cancellation cancellation)throws Exception {
                cancellation.check();verifiedApk(hash);cancellation.check();AudioGuard current=guard();
                if(current instanceof AndroidAudioOccupancy){occupancyGuard=(AndroidAudioOccupancy)current;occupancyGuard.awaitFirstSample(1500);}
                cancellation.check();JSONObject value=MediaReadiness.snapshot(app,AppMediaContract.PACKAGE,current);
                appendGuardSnapshot(value,current);evidence=value;
                requireDiagnosticPreconditions(value);
                current.requireIdle();requireCallsIdle();cancellation.check();return new LocalAudioCaptureAndroid();
            }
        },cancel,AndroidMediaDevice.CLOCK);
    }
    static void requireDiagnosticPreconditions(JSONObject value)throws IOException {
        JSONObject occupancy=value.optJSONObject("audio_occupancy_snapshot");
        if(!"GRANTED".equals(value.optString("record_permission"))||!value.optBoolean("process_package_match")
                ||!value.optBoolean("framework_package_match")||!"ALLOWED".equals(value.optString("record_appop"))||occupancy==null
                ||!"IDLE".equals(occupancy.optString("cellular"))||!"IDLE".equals(occupancy.optString("sip"))
                ||!"IDLE".equals(occupancy.optString("other")))throw new IOException("MEDIA_LOCAL_AUDIO_PRECONDITIONS");
    }
    static void requireDiagnosticCallsIdle(JSONObject value)throws IOException {
        if(value==null||!"IDLE".equals(value.optString("cellular"))||!"IDLE".equals(value.optString("sip")))
            throw new IOException("MEDIA_LOCAL_AUDIO_CALL_STATE_CHANGED");
    }
    public AppMediaController.Session create(String hash,RtcOffer offer,Cancellation cancel)throws Exception {
        cancel.check();File apk=verifiedApk(hash);final AudioGuard current=guard();current.requireIdle();cancel.check();
        File files=appDirectory(app.getFilesDir()),codeCache=appDirectory(app.getCodeCacheDir());
        final MicrophoneSession session=new MicrophoneSession(new File(files,"media"),offer,new MediaWebSocket(),
                new AndroidRtcMicrophone(app,apk,hash,new File(codeCache,"media-native"),AndroidMediaDevice.CLOCK),
                current,AndroidMediaDevice.CLOCK,null);
        return new AppMediaController.Session(){public void start()throws Exception{session.start();}
            public void stop(){session.close();}public JSONObject snapshot()throws Exception{return session.snapshot();}};
    }
    public void close()throws Exception {
        AudioGuard old;synchronized(this){if(closed)return;closed=true;old=guard;guard=null;}
        if(old instanceof AutoCloseable)((AutoCloseable)old).close();
    }
}
