package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import org.json.JSONObject;

/** APP进程唯一会话状态；准备和原生调用不持控制锁，stop/query不排在它们后面。 */
final class AppMediaController {
    interface Session { void start()throws Exception; void stop(); JSONObject snapshot()throws Exception; }
    interface Backend {
        JSONObject prepare(String hash,Cancellation cancel)throws Exception;
        Session create(String hash,RtcOffer offer,Cancellation cancel)throws Exception;
        URI origin()throws Exception;
        default LocalAudioCapture localCapture(String hash,String id,int duration,Cancellation cancel)throws Exception {
            throw new IOException("MEDIA_LOCAL_AUDIO_NOT_CONFIGURED");
        }
    }
    private volatile Backend backend;
    private final MediaCapture.Clock clock;
    private final LinkedHashSet<String> used=new LinkedHashSet<String>();
    private Session session;
    private Object owner;
    private String sessionId="",startRequest="";
    private String pendingId="",pendingRequest="";
    private Object pendingOwner;
    private Cancellation pendingCancellation;
    private long renewed;
    private LocalAudioCapture diagnostic;
    private String diagnosticRequest="";
    private Object diagnosticOwner;
    AppMediaController(Backend backend,MediaCapture.Clock clock){this.backend=backend;this.clock=clock;}
    void attachBackend(Backend backend){this.backend=backend;}
    JSONObject execute(String requestId,JSONObject command,Object callerOwner,Cancellation cancel)throws Exception {
        return execute(requestId,command,callerOwner,cancel,clock.elapsed()+AppMediaContract.DIAGNOSTIC_WAIT_MS);
    }
    JSONObject execute(String requestId,JSONObject command,Object callerOwner,Cancellation cancel,long replyDeadline)throws Exception {
        String op=command.getString("operation");cancel.check();
        if("query".equals(op))return query(command.getString("session_id"),callerOwner);
        if("stop".equals(op))return stop(command.getString("session_id"));
        synchronized(this){if(active())throw new IOException("MEDIA_APP_BUSY");}
        if("prepare".equals(op)){
            JSONObject result=backend.prepare(command.getString("apk_sha256"),cancel);cancel.check();return result;
        }
        if("local_audio_capture".equals(op)){
            final LocalAudioCapture current;
            synchronized(this){
                if(active())throw new IOException("MEDIA_APP_BUSY");
                String id=command.getString("diagnostic_id");
                if(used.contains(id))throw new IOException("MEDIA_SESSION_ALREADY_USED");
                if(used.size()>=128)throw new IOException("MEDIA_SESSION_HISTORY_FULL");
                // 此构造仅登记工厂；实际前检、AudioRecord及等待均在锁外run中执行。
                current=backend.localCapture(command.getString("apk_sha256"),id,command.getInt("duration_ms"),cancel);
                used.add(id);diagnostic=current;diagnosticOwner=callerOwner;diagnosticRequest=requestId;
            }
            return current.run(replyDeadline);
        }
        RtcOffer offer=RtcOffer.parse(command.getJSONObject("offer"),backend.origin(),clock.wall());
        synchronized(this){
            if(active())throw new IOException("MEDIA_APP_BUSY");
            if(used.contains(offer.id))throw new IOException("MEDIA_SESSION_ALREADY_USED");
            if(used.size()>=128)throw new IOException("MEDIA_SESSION_HISTORY_FULL");
            used.add(offer.id);
            pendingId=offer.id;pendingRequest=requestId;pendingOwner=callerOwner;pendingCancellation=cancel;
        }
        Session created=null;
        try{
            created=backend.create(command.getString("apk_sha256"),offer,cancel);
            cancel.check();
            synchronized(this){
                if(session!=null&&!"closed".equals(session.snapshot().optString("state")))throw new IOException("MEDIA_APP_BUSY");
                session=created;sessionId=offer.id;startRequest=requestId;owner=callerOwner;renewed=clock.elapsed();
                clearPending();
            }
            cancel.check();created.start();cancel.check();return query(offer.id,callerOwner);
        }catch(Exception failure){if(created!=null)created.stop();throw failure;}
        finally{synchronized(this){if(requestId.equals(pendingRequest))clearPending();}}
    }
    private void clearPending(){pendingId="";pendingRequest="";pendingOwner=null;pendingCancellation=null;}
    private boolean active()throws Exception {
        return (diagnostic!=null&&diagnostic.active())||pendingCancellation!=null||(session!=null&&!"closed".equals(session.snapshot().optString("state")));
    }
    synchronized JSONObject query(String id,Object callerOwner)throws Exception {
        if(diagnostic!=null){
            if(diagnostic.id().equals(id))return diagnostic.snapshot();
            if(id.isEmpty()){
                if(diagnostic.active())return new JSONObject().put("state","diagnosing").put("managed_media",false).put("last_diagnostic",diagnostic.snapshot());
                if(session==null||"closed".equals(session.snapshot().optString("state")))
                    return new JSONObject().put("state","idle").put("managed_media",false).put("last_diagnostic",diagnostic.snapshot());
            }
        }
        if(pendingCancellation!=null&&(id.isEmpty()||pendingId.equals(id)))return new JSONObject().put("state","preparing").put("session_id",pendingId).put("managed_media",false);
        if(id.isEmpty())return session==null?new JSONObject().put("state","idle").put("managed_media",false)
                :session.snapshot().put("managed_media",false).put("owner_lease_ms",AppMediaContract.LEASE_MS);
        if(session==null||!sessionId.equals(id))return new JSONObject().put("session_id",id).put("state","NOT_FOUND").put("managed_media",false);
        if(owner!=null&&owner.equals(callerOwner)&&active())renewed=clock.elapsed();
        return session.snapshot().put("managed_media",false).put("owner_lease_ms",AppMediaContract.LEASE_MS);
    }
    synchronized JSONObject stop(String id)throws Exception {
        if(diagnostic!=null&&diagnostic.id().equals(id)){if(diagnostic.active())diagnostic.cancel();return diagnostic.snapshot();}
        if(pendingCancellation!=null&&pendingId.equals(id)){pendingCancellation.cancel();return new JSONObject().put("state","closing").put("session_id",id).put("managed_media",false);}
        if(session==null||!sessionId.equals(id))return new JSONObject().put("session_id",id).put("state","NOT_FOUND").put("managed_media",false);
        session.stop();return session.snapshot().put("managed_media",false);
    }
    synchronized void cancelRequest(String id){if(id.equals(diagnosticRequest)&&diagnostic!=null&&diagnostic.active())diagnostic.cancel();if(id.equals(pendingRequest)&&pendingCancellation!=null)pendingCancellation.cancel();if(id.equals(startRequest)&&session!=null)session.stop();}
    synchronized void ownerDied(Object dead){if(diagnosticOwner!=null&&diagnosticOwner.equals(dead)&&diagnostic!=null&&diagnostic.active())diagnostic.cancel();if(pendingOwner!=null&&pendingOwner.equals(dead)&&pendingCancellation!=null)pendingCancellation.cancel();if(owner!=null&&owner.equals(dead)&&session!=null)session.stop();}
    synchronized void tick(){if(session!=null&&clock.elapsed()-renewed>=AppMediaContract.LEASE_MS)session.stop();}
    synchronized boolean hasActive(){try{return active();}catch(Exception failure){return true;}}
    synchronized boolean owns(Object value){return diagnosticOwner!=null&&diagnosticOwner.equals(value)&&diagnostic!=null&&diagnostic.active()||pendingOwner!=null&&pendingOwner.equals(value)||owner!=null&&owner.equals(value)&&hasActive();}
    synchronized void serviceDestroyed(){if(diagnostic!=null&&diagnostic.active())diagnostic.cancel();if(pendingCancellation!=null)pendingCancellation.cancel();if(session!=null)session.stop();}
}
