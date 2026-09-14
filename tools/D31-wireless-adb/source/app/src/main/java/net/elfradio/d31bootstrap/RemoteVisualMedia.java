package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.media.PhotoAlarmBridge;
import net.elfradio.d31bootstrap.media.PhotoAlarmOffer;

/** 主线缓存门面：核心线程不等待APP、相机、Binder或网络。 */
public final class RemoteVisualMedia implements AutoCloseable {
    interface Bridge { JSONObject request(JSONObject command)throws Exception; }
    interface Clock { long wall();long elapsed(); }
    static final long READINESS_RETRY=30000,READINESS_INTERVAL=300000;
    private final Bridge bridge;
    private final File apk;
    private final Runnable changed;
    private final ExecutorService worker;
    private final Clock clock;
    private final Set<String> seen=new LinkedHashSet<>();
    private volatile String cached="{\"state\":\"preparing\",\"managed_media_modes\":[]}",sha="",session="";
    private volatile boolean closed,active;
    private boolean sessionInFlight,readinessInFlight;
    private long generation,nextReadiness;
    private int readinessFailures;
    private String readinessSignature="";
    private volatile long nextQuery;
    private volatile JSONArray modes=new JSONArray();
    private volatile int cameras;
    public RemoteVisualMedia(Context context,File privateRoot,String apkPath,Runnable changed){
        this(checkedBridge(context,privateRoot,apkPath),new File(apkPath),changed,new Clock(){
            public long wall(){return System.currentTimeMillis();}
            public long elapsed(){return System.nanoTime()/1000000L;}
        },Executors.newSingleThreadExecutor(job->{Thread t=new Thread(job,"d31-visual-root");t.setDaemon(true);return t;}));
    }
    private static Bridge checkedBridge(Context context,File root,String apk){
        if(context==null||root==null||apk==null)throw new IllegalArgumentException("VISUAL_ARGUMENTS");
        PhotoAlarmBridge bridge=new PhotoAlarmBridge(context);return bridge::request;
    }
    RemoteVisualMedia(Bridge bridge,File apk,Runnable changed,Clock clock,ExecutorService worker){
        if(bridge==null||apk==null||clock==null||worker==null)throw new IllegalArgumentException("VISUAL_ARGUMENTS");
        this.bridge=bridge;this.apk=apk;this.changed=changed;this.clock=clock;this.worker=worker;
        refreshReadiness();
    }
    private String apkHash()throws Exception {
        if(!sha.isEmpty())return sha;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(apk)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));
        synchronized(this){if(!closed)sha=hash.toString();}return hash.toString();
    }
    private boolean readinessCurrent(long owned){return !closed&&!active&&!sessionInFlight&&generation==owned;}
    private void readinessBackoff(){
        readinessFailures=Math.min(5,readinessFailures+1);
        nextReadiness=clock.elapsed()+Math.min(READINESS_INTERVAL,READINESS_RETRY*(1L<<(readinessFailures-1)));
    }
    private synchronized void refreshReadiness(){
        if(closed||active||sessionInFlight||readinessInFlight||clock.elapsed()<nextReadiness)return;
        readinessInFlight=true;final long owned=generation;
        try{worker.execute(()->{
            try{
                synchronized(this){if(!readinessCurrent(owned))return;}
                String hash=apkHash();
                synchronized(this){if(!readinessCurrent(owned))return;}
                JSONObject value=bridge.request(new JSONObject().put("operation","prepare").put("apk_sha256",hash));
                synchronized(this){
                    if(!readinessCurrent(owned))return;
                    JSONArray available=value.getJSONArray("managed_media_modes");
                    int count=value.getInt("media_cameras");boolean permission=value.getBoolean("camera_permission");
                    if(count<0)throw new IOException("VISUAL_PREPARE_INVALID");
                    // 能力签名只含稳定字段；时间或属性顺序变化不能制造额外报告唤醒。
                    SortedSet<String> names=new TreeSet<>();for(int i=0;i<available.length();i++)names.add(available.getString(i));
                    String signature=names.toString()+":"+count+":"+permission;
                    if(count>0&&permission&&names.contains("photo")){readinessFailures=0;nextReadiness=clock.elapsed()+READINESS_INTERVAL;}
                    else readinessBackoff();
                    if(!signature.equals(readinessSignature)){
                        readinessSignature=signature;modes=new JSONArray(available.toString());cameras=count;publish(value);
                    }
                }
            }catch(Exception failure){synchronized(this){if(readinessCurrent(owned))readinessFailed();}}
            finally{synchronized(this){readinessInFlight=false;}}
        });}catch(RejectedExecutionException rejected){readinessInFlight=false;readinessFailed();}
    }
    private void readinessFailed(){
        readinessBackoff();readinessSignature="";modes=new JSONArray();cameras=0;publishError("VISUAL_PREPARE_FAILED");
    }
    public synchronized void accept(JSONObject offer,JSONObject credentials){
        if(closed||active||sessionInFlight||offer==null||credentials==null)return;
        try{
            PhotoAlarmOffer checked=PhotoAlarmOffer.parse(offer,new URI("https://v.elfradio.net"),clock.wall());
            if(active||seen.contains(checked.id))return;
            // 固定大小的本进程去重；APP另有不可重拍的原件日记。
            if(seen.size()>=128)seen.remove(seen.iterator().next());seen.add(checked.id);
            final JSONObject frozen=new JSONObject(offer.toString()),identity=new JSONObject(credentials.toString());
            session=checked.id;active=true;sessionInFlight=true;nextQuery=0;final long owned=++generation;
            try{worker.execute(()->{try{
                if(closed)return;String hash=apkHash();if(closed)return;
                JSONObject result=bridge.request(new JSONObject().put("operation","start").put("apk_sha256",hash).put("offer",frozen).put("credentials",identity));
                synchronized(this){if(!closed&&generation==owned)publish(result);}
            }catch(Exception failure){synchronized(this){if(!closed&&generation==owned)publishError("VISUAL_START_UNCONFIRMED");}}
            finally{identity.remove("token");synchronized(this){sessionInFlight=false;}}});}
            catch(RejectedExecutionException rejected){identity.remove("token");sessionInFlight=false;active=false;session="";seen.remove(checked.id);publishError("VISUAL_START_UNCONFIRMED");}
        }catch(Exception invalid){publishError("VISUAL_OFFER_REJECTED");}
    }
    public synchronized void tick(){
        if(closed)return;
        if(!active){refreshReadiness();return;}
        if(sessionInFlight||clock.elapsed()<nextQuery)return;
        sessionInFlight=true;nextQuery=clock.elapsed()+2000;final String owned=session;final long current=generation;
        try{worker.execute(()->{try{
            if(closed)return;
            JSONObject result=bridge.request(new JSONObject().put("operation","query").put("session_id",owned));
            synchronized(this){if(!closed&&generation==current){
                if(result.optBoolean("cleanup_complete")&&(result.optBoolean("closed")||"idle".equals(result.optString("state"))))active=false;
                publish(result);
            }}
        }catch(Exception unknown){synchronized(this){if(!closed&&generation==current)publishError("VISUAL_QUERY_UNCONFIRMED");}}
        finally{synchronized(this){sessionInFlight=false;}}});}
        catch(RejectedExecutionException rejected){sessionInFlight=false;publishError("VISUAL_QUERY_UNCONFIRMED");}
    }
    public boolean active(){return active;}
    public JSONObject snapshot(){try{return new JSONObject(cached);}catch(Exception ignored){return new JSONObject();}}
    private synchronized void publish(JSONObject value){try{
        if(closed)return;
        value.put("managed_media_modes",new JSONArray(modes.toString())).put("media_cameras",cameras);
        String next=value.toString();if(next.equals(cached))return;cached=next;
        if(changed!=null)changed.run();
    }catch(Exception ignored){}}
    private void publishError(String error){try{publish(new JSONObject().put("state","unconfirmed").put("error",error).put("active",active));}catch(Exception ignored){}}
    public synchronized void close(){
        if(closed)return;closed=true;generation++;final String owned=session;
        try{worker.execute(()->{try{if(!owned.isEmpty())bridge.request(new JSONObject().put("operation","stop").put("session_id",owned));}
            catch(Exception ignored){}finally{worker.shutdown();}});}
        catch(RejectedExecutionException rejected){worker.shutdown();}
    }
}
