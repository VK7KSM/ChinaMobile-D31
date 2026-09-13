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
    private final PhotoAlarmBridge bridge;
    private final File apk;
    private final Runnable changed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(job->{Thread t=new Thread(job,"d31-visual-root");t.setDaemon(true);return t;});
    private final Set<String> seen=new LinkedHashSet<>();
    private volatile String cached="{\"state\":\"preparing\",\"managed_media_modes\":[]}",sha="",session="";
    private volatile boolean closed,active,querying;
    private volatile long nextQuery;
    private volatile JSONArray modes=new JSONArray();
    private volatile int cameras;
    public RemoteVisualMedia(Context context,File privateRoot,String apkPath,Runnable changed){
        if(context==null||privateRoot==null||apkPath==null)throw new IllegalArgumentException("VISUAL_ARGUMENTS");
        bridge=new PhotoAlarmBridge(context);apk=new File(apkPath);this.changed=changed;
        worker.execute(()->{try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(apk)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));sha=hash.toString();
            if(closed)return;
            JSONObject value=bridge.request(new JSONObject().put("operation","prepare").put("apk_sha256",sha));
            modes=value.getJSONArray("managed_media_modes");cameras=value.optInt("media_cameras",0);publish(value);
        }catch(Exception failure){publishError("VISUAL_PREPARE_FAILED");}});
    }
    public synchronized void accept(JSONObject offer,JSONObject credentials){
        if(closed||offer==null||credentials==null)return;
        try{
            PhotoAlarmOffer checked=PhotoAlarmOffer.parse(offer,new URI("https://v.elfradio.net"),System.currentTimeMillis());
            if(active||seen.contains(checked.id))return;
            // 固定大小的本进程去重；APP另有不可重拍的原件日记。
            if(seen.size()>=128)seen.remove(seen.iterator().next());seen.add(checked.id);
            final JSONObject frozen=new JSONObject(offer.toString()),identity=new JSONObject(credentials.toString());
            session=checked.id;active=true;nextQuery=0;
            worker.execute(()->{try{
                if(closed)return;
                publish(bridge.request(new JSONObject().put("operation","start").put("apk_sha256",sha).put("offer",frozen).put("credentials",identity)));
            }catch(Exception failure){publishError("VISUAL_START_UNCONFIRMED");}finally{identity.remove("token");}});
        }catch(Exception invalid){publishError("VISUAL_OFFER_REJECTED");}
    }
    public synchronized void tick(){
        if(closed||!active||querying||System.nanoTime()/1000000L<nextQuery)return;
        querying=true;nextQuery=System.nanoTime()/1000000L+2000;final String owned=session;
        worker.execute(()->{try{
            JSONObject result=bridge.request(new JSONObject().put("operation","query").put("session_id",owned));
            if(result.optBoolean("cleanup_complete")&&(result.optBoolean("closed")||"idle".equals(result.optString("state"))))active=false;
            publish(result);
        }catch(Exception unknown){publishError("VISUAL_QUERY_UNCONFIRMED");}finally{querying=false;}});
    }
    public boolean active(){return active;}
    public JSONObject snapshot(){try{return new JSONObject(cached);}catch(Exception ignored){return new JSONObject();}}
    private synchronized void publish(JSONObject value){try{
        value.put("managed_media_modes",new JSONArray(modes.toString())).put("media_cameras",cameras);
        String next=value.toString();if(next.equals(cached))return;cached=next;
        if(changed!=null)changed.run();
    }catch(Exception ignored){}}
    private void publishError(String error){try{publish(new JSONObject().put("state","unconfirmed").put("error",error).put("active",active));}catch(Exception ignored){}}
    public synchronized void close(){
        if(closed)return;closed=true;final String owned=session;
        worker.execute(()->{try{if(!owned.isEmpty())bridge.request(new JSONObject().put("operation","stop").put("session_id",owned));}
            catch(Exception ignored){}finally{worker.shutdown();}});
    }
}
