package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.Arrays;
import org.json.JSONObject;

/** 只读前置证据；不加载JNI、不构造AudioRecord，不把权限或AppOps当作采集成功。 */
public final class MediaReadiness {
    private MediaReadiness(){}
    static boolean applicationIdentityMatches(int uid,String contextPackage,String expectedPackage,String[] packages){
        int appId=uid%100000;
        return uid>=0&&appId>=10000&&appId<20000&&expectedPackage!=null&&expectedPackage.equals(contextPackage)
                &&packages!=null&&Arrays.asList(packages).contains(expectedPackage);
    }
    static void requireApplicationIdentity(Context context,String expectedPackage)throws Exception {
        if(context==null||!applicationIdentityMatches(android.os.Process.myUid(),context.getPackageName(),expectedPackage,
                context.getPackageManager().getPackagesForUid(android.os.Process.myUid())))
            throw new java.io.IOException("MEDIA_APPLICATION_IDENTITY_REQUIRED");
    }
    public static JSONObject snapshot(Context context, String expectedApplicationPackage, AudioGuard combinedGuard)throws Exception {
        JSONObject result=new JSONObject().put("schemaVersion",1).put("operation","media_readiness")
                .put("state","NOT_READY").put("implementation_mode","microphone")
                .put("managed_media",false).put("managed_alarm_tasks",false)
                .put("jni_loaded","NOT_CHECKED").put("audio_record_identity","NOT_VERIFIED")
                .put("device_audio_capture","NOT_TESTED");
        boolean granted=false,packageMatched=false;
        try {
            if(context==null||expectedApplicationPackage==null)throw new IllegalStateException();
            granted=context.checkPermission(Manifest.permission.RECORD_AUDIO,android.os.Process.myPid(),android.os.Process.myUid())==PackageManager.PERMISSION_GRANTED;
            String[] packages=context.getPackageManager().getPackagesForUid(android.os.Process.myUid());
            packageMatched=applicationIdentityMatches(android.os.Process.myUid(),context.getPackageName(),expectedApplicationPackage,packages);
            result.put("record_permission",granted?"GRANTED":"DENIED").put("process_package_match",packageMatched);
        }catch(Exception unavailable){result.put("record_permission","READ_FAILED").put("process_package_match",JSONObject.NULL);}
        try {
            // API23的AudioRecord.Builder不接收Context；单看传入Context包名不能证明实际归属。
            Object actual=Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null);
            if(!(actual instanceof String)||((String)actual).isEmpty()||context==null)throw new IllegalStateException();
            result.put("framework_package_match",actual.equals(expectedApplicationPackage));
            AppOpsManager ops=(AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
            if(ops==null)throw new IllegalStateException();
            int mode=ops.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO,android.os.Process.myUid(),(String)actual);
            result.put("record_appop",mode==AppOpsManager.MODE_ALLOWED?"ALLOWED":"NOT_ALLOWED");
        }catch(Exception unavailable){result.put("framework_package_match",JSONObject.NULL).put("record_appop","READ_FAILED");}
        try {if(combinedGuard==null)throw new IllegalStateException();combinedGuard.requireIdle();result.put("audio_occupancy","IDLE_OBSERVED");}
        catch(Exception unknownOrBusy){result.put("audio_occupancy","NOT_CONFIRMED_IDLE");}
        return result;
    }
}
