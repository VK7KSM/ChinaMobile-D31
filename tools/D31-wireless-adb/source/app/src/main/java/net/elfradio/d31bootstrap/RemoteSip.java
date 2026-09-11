package net.elfradio.d31bootstrap;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** 独立配置进程；密码留在私有任务文件，原厂数据库只按指定线路事务更新。 */
public final class RemoteSip {
    static final File ROOT=new File("/data/local/d31-remote/runtime/sip");
    private static final String DB="/data/data/com.starnet.nexui/databases/sipaccount.db";
    private static final Uri QUIK=Uri.parse("content://net.elfradio.d31phone.debug.sipremote");
    private static final Uri STATUS=Uri.parse("content://com.starnet.videobox.provider.VsipProvider/accstatus/");

    static String command(String apk,String id,String taskId,JSONObject input)throws Exception{
        if(apk==null||(!apk.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk")
                &&!apk.equals("/system/priv-app/D31ElfRemote/D31ElfRemote.apk")))throw new IOException("载荷路径无效");
        if(!id.matches("[a-f0-9]{64}")||!taskId.matches("[A-Za-z0-9-]{1,64}"))throw new IOException("任务号无效");
        ensure();File f=new File(ROOT,id+".request.json");
        JSONObject request=new JSONObject().put("task_id",taskId).put("params",RemoteSipConfig.validate(input));
        if(f.exists()&&!RescueFiles.read(f,16000).equals(request.toString()))throw new IOException("任务内容冲突");
        if(!f.exists())write(f,request);
        return "CLASSPATH="+RescueFiles.quote(apk)+" /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteSip "+id;
    }
    private static void ensure()throws Exception{
        if(!ROOT.isDirectory()&&!ROOT.mkdirs())throw new IOException("配置目录不可用");Os.chmod(ROOT.getPath(),0700);
    }
    private static void write(File f,JSONObject value)throws Exception{RescueFiles.write(f,value.toString());Os.chmod(f.getPath(),0600);}
    private static Context context()throws Exception{
        if(android.os.Looper.getMainLooper()==null)android.os.Looper.prepareMainLooper();
        Class<?> c=Class.forName("android.app.ActivityThread");Object thread=c.getMethod("systemMain").invoke(null);
        return (Context)c.getMethod("getSystemContext").invoke(thread);
    }
    public static void main(String[] args){
        try{
            if(Os.getuid()!=0||args.length!=1)throw new IOException();ensure();Context c=context();
            if("snapshot".equals(args[0])){System.out.println(snapshot(c));System.exit(0);return;}
            if(!args[0].matches("[a-f0-9]{64}"))throw new IOException();
            try(RemoteMaintenance.Lease maintenance=RemoteMaintenance.acquire();
                RandomAccessFile lock=new RandomAccessFile(new File(ROOT,"configure.lock"),"rw");FileLock held=RemoteFileLocks.tryExclusive(lock.getChannel())){
                if(maintenance==null)throw new IOException("设备维护进行中");
                RemoteMaintenance.requireUnreserved();
                if(held==null)throw new IOException("另一账号事务进行中");
                File f=new File(ROOT,args[0]+".request.json");JSONObject request=new JSONObject(RescueFiles.read(f,16000));
                JSONObject p=RemoteSipConfig.validate(request.getJSONObject("params"));
                File resultFile=new File(ROOT,args[0]+".result.json");
                if(resultFile.exists()){System.out.println(RescueFiles.read(resultFile,16000));System.exit(0);return;}
                if("quik".equals(p.getString("target"))){
                    Bundle b=new Bundle();b.putString("request",request.toString());
                    Bundle r;try(RemoteContent provider=new RemoteContent(QUIK)){r=provider.call("configure",b);}
                    if(r==null||!r.getBoolean("ok")||!r.getBoolean("applied"))throw new IOException("短信配置未完成");
                }else applyNexui(c,args[0],request.getString("task_id"),p);
                JSONObject result=new JSONObject().put("target",p.getString("target")).put("account_id",p.getString("account_id"))
                        .put("applied",true).put("action","completed").put("exit_code",0).put("text","配置已写入");
                write(resultFile,result);System.out.println(result);
            }
            System.exit(0);
        }catch(Exception e){
            System.err.println("账号配置未完成，原配置备份保留");
            for(Throwable cause=e;cause!=null;cause=cause.getCause()){
                System.err.println(cause.getClass().getName());
                for(StackTraceElement frame:cause.getStackTrace())if(frame.getClassName().startsWith("net.elfradio.d31bootstrap."))System.err.println(frame);
            }
            System.exit(1);
        }
    }

    private static JSONObject rows(SQLiteDatabase db)throws Exception{
        JSONObject rows=new JSONObject();
        try(Cursor cursor=db.query("sipaccount",null,null,null,null,null,"_id")){
            while(cursor.moveToNext()){
                ContentValues row=new ContentValues();DatabaseUtils.cursorRowToContentValues(cursor,row);
                JSONObject value=new JSONObject();for(String k:row.keySet())value.put(k,row.get(k)==null?JSONObject.NULL:row.get(k));
                rows.put(String.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow("_id"))),value);
            }
        }
        return rows;
    }
    private static JSONObject mapping(JSONObject rows)throws Exception{
        File f=new File(ROOT,"slots.json");JSONObject slots=f.exists()?new JSONObject(RescueFiles.read(f,16000)):new JSONObject();
        if(rows.length()>4)throw new IOException("原厂账号数量超出四条线路");
        java.util.ArrayList<String> ids=new java.util.ArrayList<>();java.util.Iterator<String> it=rows.keys();while(it.hasNext())ids.add(it.next());
        java.util.Collections.sort(ids,(a,b)->Long.compare(Long.parseLong(a),Long.parseLong(b)));
        for(int n=1;n<=4;n++){String slot="line-"+n;if(slots.has(slot)&&!rows.has(slots.getString(slot)))slots.remove(slot);}
        for(String id:ids){boolean found=false;for(int n=1;n<=4;n++)if(id.equals(slots.optString("line-"+n)))found=true;
            if(!found)for(int n=1;n<=4;n++)if(!slots.has("line-"+n)){slots.put("line-"+n,id);break;}}
        write(f,slots);return slots;
    }
    private static ContentValues values(JSONObject o)throws Exception{
        ContentValues v=new ContentValues();java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){String k=keys.next();Object x=o.get(k);
            if(x==JSONObject.NULL)v.putNull(k);else if(x instanceof Number)v.put(k,((Number)x).longValue());else v.put(k,String.valueOf(x));}return v;
    }
    static boolean same(JSONObject a,JSONObject b)throws Exception{
        if(a.length()!=b.length())return false;
        java.util.Iterator<String> it=a.keys();while(it.hasNext()){
            String k=it.next();if(!b.has(k))return false;Object x=a.get(k),y=b.get(k);
            if(x instanceof JSONObject&&y instanceof JSONObject){if(!same((JSONObject)x,(JSONObject)y))return false;}
            else if(!String.valueOf(x).equals(String.valueOf(y)))return false;
        }return true;
    }
    static String configHash(JSONObject row)throws Exception{
        java.util.ArrayList<String> keys=new java.util.ArrayList<>();java.util.Iterator<String> i=row.keys();while(i.hasNext())keys.add(i.next());
        java.util.Collections.sort(keys);JSONArray fields=new JSONArray();for(String k:keys){Object value=row.get(k);fields.put(new JSONArray().put(k).put(value==JSONObject.NULL?JSONObject.NULL:String.valueOf(value)));}
        return RemoteProtocol.hash(fields.toString());
    }
    private static void launch(Context c)throws Exception{
        Intent intent=c.getPackageManager().getLaunchIntentForPackage("com.starnet.nexui");
        if(intent==null||intent.getComponent()==null)throw new IOException("原桌面入口不可用");
        Process process=new ProcessBuilder("/system/bin/am","start","--user","0","-n",intent.getComponent().flattenToString())
                .redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
        while((count=process.getInputStream().read(buffer))!=-1)if(output.size()<16000)output.write(buffer,0,count);
        String text=output.toString("UTF-8");
        if(process.waitFor()!=0||text.contains("Error:")||text.contains("Exception"))throw new IOException("原桌面重载未完成");
    }
    private static void stop()throws Exception{
        Process process=new ProcessBuilder("/system/bin/am","force-stop","--user","0","com.starnet.nexui").redirectErrorStream(true).start();
        while(process.getInputStream().read()!=-1){}if(process.waitFor()!=0)throw new IOException("原桌面未停止");
    }
    private static void restoreOwner(android.system.StructStat owner)throws Exception{
        for(String suffix:new String[]{"","-journal","-wal","-shm"}){
            File file=new File(DB+suffix);
            if(file.exists()){Os.chown(file.getPath(),owner.st_uid,owner.st_gid);Os.chmod(file.getPath(),suffix.isEmpty()?owner.st_mode&0777:0600);}
        }
    }
    static JSONObject configuredRow(SQLiteDatabase db,JSONObject before,JSONObject old,JSONObject p)throws Exception{
        JSONObject next=new JSONObject(old.toString());
        if(old.length()==0){
            try(Cursor columns=db.rawQuery("PRAGMA table_info(sipaccount)",null)){
                while(columns.moveToNext())next.put(columns.getString(1),JSONObject.NULL);
            }
            long max=0;java.util.Iterator<String> keys=before.keys();while(keys.hasNext())max=Math.max(max,Long.parseLong(keys.next()));
            next.put("_id",max+1).put("username",p.getString("username")).put("displayname",p.getString("username"))
                    .put("bakproxy","").put("bakproxyport",0).put("regexpires",300).put("kainterval",30).put("edit",1).put("checksip",1).put("accmode",0);
        }
        return next.put("account",p.getString("username")).put("password",p.getString("password")).put("regserver",p.getString("server"))
                .put("regserverport",p.getInt("port")).put("proxy",p.getString("server")).put("proxyport",p.getInt("port"))
                .put("transport","tls".equals(p.getString("transport"))?2:"tcp".equals(p.getString("transport"))?1:0).put("active",1);
    }
    private static void applyNexui(Context c,String id,String taskId,JSONObject p)throws Exception{
        File transaction=new File(ROOT,id+".transaction.json");String slot=p.getString("account_id");
        if(transaction.exists())throw new IOException("未完成原厂事务需先核查，不重复写入");
        JSONObject before,slots;try(SQLiteDatabase db=SQLiteDatabase.openDatabase(DB,null,SQLiteDatabase.OPEN_READONLY)){before=rows(db);slots=mapping(before);}
        String rowId=slots.optString(slot);JSONObject old=rowId.isEmpty()?new JSONObject():before.getJSONObject(rowId);
        if(!old.isNull("checksip")&&old.has("checksip")&&old.optInt("checksip",1)==0)throw new IOException("此线路是H323，未覆盖");
        JSONObject next;try(SQLiteDatabase db=SQLiteDatabase.openDatabase(DB,null,SQLiteDatabase.OPEN_READONLY)){next=configuredRow(db,before,old,p);}
        rowId=next.getString("_id");
        File applied=new File(ROOT,slot+".applied.json");
        if(same(old,next)){write(applied,new JSONObject().put("task_id",taskId).put("row_id",rowId).put("config_hash",configHash(next)));return;}
        JSONObject tx=new JSONObject().put("before",before).put("row_id",rowId).put("after",next).put("phase","prepared");write(transaction,tx);
        android.system.StructStat owner=Os.stat(DB);boolean changed=false,launched=false;
        try{
            stop();
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(DB,null,SQLiteDatabase.OPEN_READWRITE)){
                db.beginTransaction();try{
                    if(!same(before,rows(db)))throw new IOException("账号已在本地改变，取消覆盖");
                    if(old.length()==0)db.insertOrThrow("sipaccount",null,values(next));
                    else if(db.update("sipaccount",values(next),"_id=?",new String[]{rowId})!=1)throw new IOException("线路不存在");
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                changed=true;
                JSONObject actual=rows(db).getJSONObject(rowId);
                if(!same(next,actual)){
                    JSONArray fields=new JSONArray();java.util.Iterator<String> keys=next.keys();
                    while(keys.hasNext()){String k=keys.next();if(!String.valueOf(next.get(k)).equals(String.valueOf(actual.opt(k))))fields.put(k);}
                    write(transaction,tx.put("mismatched_fields",fields));throw new IOException("配置读回不一致");
                }
            }
            write(transaction,tx.put("phase","written"));
            restoreOwner(owner);
            launch(c);launched=true;slots.put(slot,rowId);write(new File(ROOT,"slots.json"),slots);
            write(applied,new JSONObject().put("task_id",taskId).put("row_id",rowId).put("config_hash",configHash(next)));write(transaction,tx.put("phase","success"));
        }catch(Exception failed){
            if(changed){try(SQLiteDatabase db=SQLiteDatabase.openDatabase(DB,null,SQLiteDatabase.OPEN_READWRITE)){
                db.beginTransaction();try{
                    JSONObject live=rows(db).optJSONObject(rowId);
                    if(live==null||!same(live,next))throw new IOException("现场已变化，保留备份等待核查");
                    if(old.length()==0)db.delete("sipaccount","_id=?",new String[]{rowId});else db.update("sipaccount",values(old),"_id=?",new String[]{rowId});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }}
            write(transaction,tx.put("phase","rolled_back"));throw failed;
        }finally{
            restoreOwner(owner);
            if(!launched)launch(c);
        }
    }

    static JSONObject snapshot(Context c)throws Exception{
        ensure();JSONArray targets=new JSONArray(),registrations=new JSONArray();
        try(RandomAccessFile lock=new RandomAccessFile(new File(ROOT,"configure.lock"),"rw");FileLock held=RemoteFileLocks.tryExclusive(lock.getChannel())){
            if(held==null)throw new IOException("配置进行中");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(DB,null,SQLiteDatabase.OPEN_READONLY)){
                JSONObject rows=rows(db),slots=mapping(rows),statuses=new JSONObject();
                try(RemoteContent provider=new RemoteContent(STATUS);Cursor cursor=provider.query(STATUS,new String[]{"_id","status","code"})){
                    if(cursor==null)throw new IOException();while(cursor.moveToNext())statuses.put(cursor.getString(0),cursor.getString(1));
                }
                JSONArray accounts=new JSONArray();long sampled=System.currentTimeMillis();
                for(int n=1;n<=4;n++){
                    String slot="line-"+n,rowId=slots.optString(slot);JSONObject row=rows.optJSONObject(rowId);
                    if(row!=null&&row.has("checksip")&&!row.isNull("checksip")&&row.optInt("checksip")==0)continue;
                    accounts.put(new JSONObject().put("account_id",slot).put("label","线路 "+n+(row==null?"（未配置）":" · "+row.optString("account"))));
                    JSONObject reg=new JSONObject().put("target","nexui").put("account_id",slot).put("state",row==null?"unregistered":RemoteSipConfig.registration(statuses.optString(rowId)))
                            .put("reason",row==null?"未配置":"").put("sampled_at",sampled);
                    File applied=new File(ROOT,slot+".applied.json");if(applied.exists()){
                        JSONObject a=new JSONObject(RescueFiles.read(applied,4000));if(row!=null&&rowId.equals(a.optString("row_id"))&&configHash(row).equals(a.optString("config_hash")))reg.put("config_task_id",a.getString("task_id"));
                    }
                    registrations.put(reg);
                }
                targets.put(new JSONObject().put("target","nexui").put("label","Nexui 电话").put("auth_username_supported",false).put("realm_supported",false).put("accounts",accounts));
            }catch(Exception unavailable){System.err.println("Nexui状态读取失败："+unavailable);}
            try{
                Bundle b;try(RemoteContent provider=new RemoteContent(QUIK)){b=provider.call("status",null);}
                if(b==null||!b.getBoolean("ok")||b.getInt("api")!=1)throw new IOException();
                targets.put(new JSONObject().put("target","quik").put("label","QUIK 短信").put("auth_username_supported",false)
                        .put("accounts",new JSONArray().put(new JSONObject().put("account_id","default").put("label","短信账号"))));
                JSONObject reg=new JSONObject().put("target","quik").put("account_id","default").put("state",b.getString("state","unknown"))
                        .put("sampled_at",b.getLong("sampled_at")).put("reason","");
                if(!b.getString("config_task_id","").isEmpty())reg.put("config_task_id",b.getString("config_task_id"));registrations.put(reg);
            }catch(Exception unavailable){System.err.println("QUIK状态读取失败："+unavailable);}
        }
        return new JSONObject().put("sip_targets",targets).put("sip_registrations",registrations).put("managed_sip_account",targets.length()>0);
    }
}
