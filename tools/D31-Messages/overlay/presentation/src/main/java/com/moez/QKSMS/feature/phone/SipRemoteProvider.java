package dev.octoshrimpy.quik.feature.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** 由本机系统管理核心调用，配置在短信进程内生效，不触碰短信内容。 */
public final class SipRemoteProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != Process.SYSTEM_UID && uid != Process.myUid())
            throw new SecurityException("仅允许本机系统管理核心");
        long identity = Binder.clearCallingIdentity();
        try {
            if ("status".equals(method)) return status();
            if (!"configure".equals(method) || extras == null) throw new IllegalArgumentException();
            return configure(new JSONObject(extras.getString("request", "")));
        } catch (Exception error) {
            Bundle result = new Bundle(); result.putBoolean("ok", false);
            result.putString("error", "配置未完成，请核对字段或本地配置事务"); return result;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private Bundle configure(JSONObject request) throws Exception {
        String id = request.getString("task_id");
        if (!id.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException();
        JSONObject p = request.getJSONObject("params");
        if (!"quik".equals(p.getString("target")) || !"default".equals(p.getString("account_id")))
            throw new IllegalArgumentException();
        String server=p.getString("server"), user=p.getString("username"), password=p.getString("password");
        int port=p.getInt("port");
        if (!server.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || !user.matches("[A-Za-z0-9_.+-]{1,128}")
                || !user.equals(p.optString("auth_username",user)) || port<1 || port>65535
                || password.isEmpty() || password.length()>256 || password.matches("(?s).*[\\x00-\\x1f\\x7f].*"))
            throw new IllegalArgumentException();
        SipConfigStore.Transport transport=SipConfigStore.Transport.valueOf(p.getString("transport").toUpperCase(java.util.Locale.US));
        SharedPreferences prefs=getContext().getSharedPreferences("d31_sip_profile",Context.MODE_PRIVATE);
        String realm=p.optString("realm",prefs.getString("realm","*"));
        if (!realm.matches("[A-Za-z0-9*_.@:-]{1,253}")) throw new IllegalArgumentException();
        String hash=hex(MessageDigest.getInstance("SHA-256").digest(p.toString().getBytes(StandardCharsets.UTF_8)));
        String key="remote_receipt_"+id;
        if (prefs.contains(key)) {
            if (!hash.equals(prefs.getString(key,""))) throw new IllegalArgumentException();
            Bundle r=status();r.putBoolean("applied",true);return r;
        }
        int count=0;for(String k:prefs.getAll().keySet())if(k.startsWith("remote_receipt_"))count++;
        if(count>=1024)throw new IllegalStateException("配置记录需归档");
        SharedPreferences backup=getContext().getSharedPreferences("d31_sip_remote_backup",Context.MODE_PRIVATE);
        JSONObject before=new JSONObject(prefs.getAll());
        if(!backup.edit().putString("before_"+id,before.toString()).commit())throw new IllegalStateException();
        if(!prefs.edit().putBoolean("enabled",true).putString("server",server).putInt("port",port)
                .putString("username",user).putString("password",password).putString("realm",realm)
                .putString("transport",transport.name()).putString("remote_task_id",id).putString(key,hash).commit())
            throw new IllegalStateException();
        try {
            SipEngine.get().configurationChanging();
            SipService.restart(getContext());
        } catch(Exception failure) {
            SharedPreferences.Editor restore=prefs.edit().clear();
            java.util.Iterator<String> keys=before.keys();
            while(keys.hasNext()){String k=keys.next();Object v=before.get(k);
                if(v instanceof Boolean)restore.putBoolean(k,(Boolean)v);
                else if(v instanceof Integer)restore.putInt(k,(Integer)v);
                else if(v instanceof Long)restore.putLong(k,(Long)v);
                else restore.putString(k,String.valueOf(v));}
            if(!restore.commit())throw new IllegalStateException();
            SipService.restart(getContext());throw failure;
        }
        Bundle result=status();result.putBoolean("applied",true);return result;
    }

    private Bundle status() {
        SipConfigStore.Profile p=new SipConfigStore(getContext()).load();
        Bundle result=SipEngine.get().remoteStatus();
        result.putBoolean("ok",true);result.putInt("api",1);
        result.putString("server",p.server);result.putString("username",p.username);
        result.putInt("port",p.port);result.putString("transport",p.transport.name().toLowerCase(java.util.Locale.US));
        result.putString("realm",p.realm);result.putBoolean("enabled",p.enabled);
        result.putString("config_task_id",getContext().getSharedPreferences("d31_sip_profile",Context.MODE_PRIVATE).getString("remote_task_id",""));
        return result;
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
