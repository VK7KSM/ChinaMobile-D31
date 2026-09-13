package net.elfradio.d31bootstrap.media;

import java.io.*;
import org.json.JSONObject;

/** APP私有原件：至多四个会话目录，每会话三个样本，每样本2MiB，不自动覆盖历史。 */
final class AppMediaRtcDiagnostics {
    static final int MAX_SESSIONS=4,MAX_SAMPLES=3,MAX_BYTES=2*1024*1024;
    private final File root;
    private final String id,hash;
    private File directory;
    private volatile int saved;
    private volatile String error="";
    AppMediaRtcDiagnostics(File root,String id,String hash){this.root=root;this.id=id;this.hash=hash;}
    synchronized void save(AudioCaptureObservation.Sample sample,String failure,int pid,int session,long elapsed,JSONObject decision){
        save(sample,failure,pid,session,elapsed,decision,null);
    }
    synchronized void save(AudioCaptureObservation.Sample sample,String failure,int pid,int session,long elapsed,JSONObject decision,JSONObject partial){
        if(saved>=MAX_SAMPLES)return;
        try{
            if(directory==null){
                if(!id.matches("[A-Za-z0-9_-]{1,96}")||!hash.matches("[a-f0-9]{64}"))throw new IOException("MEDIA_DIAGNOSTIC_IDENTITY");
                MediaFiles.directory(root);File[] prior=root.listFiles();
                if(prior==null||prior.length>=MAX_SESSIONS)throw new IOException("MEDIA_DIAGNOSTIC_STORAGE_LIMIT");
                File chosen=MediaFiles.plain(new File(root,id));
                if(!chosen.mkdir())throw new IOException("MEDIA_DIAGNOSTIC_NO_OVERWRITE");
                directory=chosen;
            }
            JSONObject value=new JSONObject().put("schema",1).put("session_id",id).put("apk_sha256",hash)
                    .put("pid",pid).put("audio_session",session).put("elapsed_ms",elapsed).put("read_error",failure)
                    .put("sample_returned",sample!=null).put("decision",decision).put("contains_audio",false);
            if(partial!=null)value.put("read_trace",partial);
            if(sample!=null){
                value.put("flinger",dump(sample.flinger)).put("policy",dump(sample.policy))
                        .put("external_raw",sample.externalJson).put("external_started_elapsed_ms",sample.began)
                        .put("external_finished_elapsed_ms",sample.finished);
            }
            byte[] bytes=value.toString().getBytes("UTF-8");
            if(bytes.length>MAX_BYTES)throw new IOException("MEDIA_DIAGNOSTIC_SIZE_LIMIT");
            File target=MediaFiles.plain(new File(directory,"sample-"+(saved+1)+".json"));
            if(!target.createNewFile())throw new IOException("MEDIA_DIAGNOSTIC_NO_OVERWRITE");
            try(FileOutputStream out=new FileOutputStream(target)){out.write(bytes);out.getFD().sync();}
            saved++;error="";
        }catch(Exception failureToSave){
            String code=failureToSave.getMessage();error=code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_DIAGNOSTIC_WRITE_FAILED";
        }
    }
    private static JSONObject dump(AudioInputOwnership.Dump value)throws Exception {
        return new JSONObject().put("text",value.text).put("complete",value.complete)
                .put("started_elapsed_ms",value.startedElapsedMs).put("finished_elapsed_ms",value.finishedElapsedMs);
    }
    JSONObject snapshot()throws Exception{return new JSONObject().put("saved_samples",saved).put("storage_error",error);}
}
