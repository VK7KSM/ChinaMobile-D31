package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** 本地Binder合同，不是Web任务或新增Web能力。 */
final class AppMediaContract {
    static final String PACKAGE="net.elfradio.d31bootstrap";
    static final String SERVICE=PACKAGE+".media.AppMediaService";
    static final String ACTION=PACKAGE+".media.CONTROL";
    static final String DESCRIPTOR=PACKAGE+".media.IAppMediaControl";
    static final int EXECUTE=1,CANCEL=2,HELLO=1,RESULT=2,MAX_BYTES=16384;
    static final long WAIT_MS=6000,DIAGNOSTIC_WAIT_MS=10000,LEASE_MS=15000;
    static void request(String id,String boot,long started,long now)throws IOException {
        request(id,boot,started,now,WAIT_MS);
    }
    static void request(String id,String boot,long started,long now,long window)throws IOException {
        if(id==null||!id.matches("[a-f0-9-]{36}")||boot==null||!boot.matches("[a-f0-9-]{36}")
                ||(window!=WAIT_MS&&window!=DIAGNOSTIC_WAIT_MS)||started<0||now<started||now-started>window)
            throw new IOException("MEDIA_BRIDGE_EXPIRED_OR_INVALID");
    }
    static void caller(int actualUid)throws IOException {if(actualUid!=0)throw new IOException("MEDIA_BRIDGE_ROOT_REQUIRED");}
    static JSONObject command(String json)throws Exception {
        if(json==null||json.length()>MAX_BYTES||json.getBytes("UTF-8").length>MAX_BYTES)throw new IOException("MEDIA_BRIDGE_SIZE");
        JSONObject value=new JSONObject(json);String op=value.getString("operation");
        if(!"prepare".equals(op)&&!"start".equals(op)&&!"stop".equals(op)&&!"query".equals(op)&&!"local_audio_capture".equals(op))throw new IOException("MEDIA_BRIDGE_OPERATION");
        if(("prepare".equals(op)||"start".equals(op)||"local_audio_capture".equals(op))&&!value.optString("apk_sha256").matches("[a-f0-9]{64}"))throw new IOException("MEDIA_BRIDGE_APK_HASH");
        if("local_audio_capture".equals(op)){
            Object duration=value.opt("duration_ms");
            if(!value.optString("diagnostic_id").matches("[A-Za-z0-9_-]{1,96}")||!(duration instanceof Integer)
                    ||((Integer)duration)<1||((Integer)duration)>LocalAudioCapture.MAX_DURATION_MS)
                throw new IOException("MEDIA_LOCAL_AUDIO_ARGUMENT");
        }
        if("stop".equals(op)&&!value.optString("session_id").matches("[A-Za-z0-9_-]{1,96}"))throw new IOException("MEDIA_BRIDGE_SESSION_ID");
        if("query".equals(op)&&!value.optString("session_id").matches("[A-Za-z0-9_-]{0,96}"))throw new IOException("MEDIA_BRIDGE_SESSION_ID");
        if("start".equals(op)){
            String mode=value.getJSONObject("offer").optString("mode");
            if(!"microphone".equals(mode)&&!"video".equals(mode))throw new IOException("MEDIA_MODE_NOT_IMPLEMENTED");
        }
        return value;
    }
    static long executionWindow(JSONObject validatedCommand)throws Exception {
        JSONObject checked=command(validatedCommand.toString());
        return "local_audio_capture".equals(checked.getString("operation"))?DIAGNOSTIC_WAIT_MS:WAIT_MS;
    }
    static void reply(int sender,int expected,String id,String boot,long started,long now,String json)throws Exception {
        reply(sender,expected,id,boot,started,now,json,WAIT_MS);
    }
    static void reply(int sender,int expected,String id,String boot,long started,long now,String json,long window)throws Exception {
        request(id,boot,started,now,window);
        if(expected<10000||sender!=expected||json==null||json.length()>MAX_BYTES||json.getBytes("UTF-8").length>MAX_BYTES)
            throw new IOException("MEDIA_BRIDGE_REPLY_IDENTITY_OR_SIZE");
        JSONObject value=new JSONObject(json);
        if(!id.equals(value.getString("request_id"))||!boot.equals(value.getString("boot_id"))
                ||!(value.get("started_elapsed_ms") instanceof Number)||value.getLong("started_elapsed_ms")!=started)
            throw new IOException("MEDIA_BRIDGE_REPLY_MISMATCH");
    }
    static Object start(Class<?> serviceInterface,Object manager,Class<?> appThread,Class<?> intentClass,Object intent)throws Exception {
        try{
            // 复用已实测的API23 AMS签名；root的callingPackage是空串，不能冒用APP包名。
            return serviceInterface.getMethod("startService",appThread,intentClass,String.class,String.class,int.class)
                    .invoke(manager,null,intent,null,"",0);
        }catch(InvocationTargetException wrapped){
            Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;
            if(cause instanceof Error)throw (Error)cause;throw wrapped;
        }
    }
    static String bootId()throws Exception {
        try(InputStream in=new FileInputStream("/proc/sys/kernel/random/boot_id")){
            byte[] bytes=new byte[65];int n=in.read(bytes);
            if(n<1||n>64||in.read()!=-1)throw new IOException("MEDIA_BOOT_ID_INVALID");
            String value=new String(bytes,0,n,"US-ASCII").trim();
            if(!value.matches("[a-f0-9-]{36}"))throw new IOException("MEDIA_BOOT_ID_INVALID");return value;
        }
    }
    private AppMediaContract(){}
}
