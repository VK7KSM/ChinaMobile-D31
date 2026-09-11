package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 仅处理已被定制短信替代的旧包；保留应用数据与真实启用状态。 */
public final class RemoteLegacyQuikCheck {
    private static final String LEGACY="dev.octoshrimpy.quik.fdroid";
    private static final String CURRENT="net.elfradio.d31phone.debug";
    public static void main(String[] args) {
        try {run(args);System.exit(0);}
        catch(Exception failure){failure.printStackTrace(System.err);System.err.flush();System.exit(1);}
    }
    private static void run(String[] args) throws Exception {
        if(args.length!=2 || !(args[0].equals("retire") || args[0].equals("restore"))
                || !args[1].matches("[a-f0-9]{64}") || android.system.Os.getuid()!=0
                || android.os.Build.VERSION.SDK_INT!=23 || !"hct6735_66_m0".equals(android.os.Build.DEVICE))
            throw new IOException("只允许已确认D31的旧短信包处理");
        android.system.Os.umask(0077);
        if(android.os.Looper.getMainLooper()==null)android.os.Looper.prepareMainLooper();
        Class<?> type=Class.forName("android.app.ActivityThread");
        Context context=(Context)type.getMethod("getSystemContext").invoke(type.getMethod("systemMain").invoke(null));
        PackageManager pm=context.getPackageManager();
        try(RemoteMaintenance.Lease lease=RemoteMaintenance.acquire()) {
            if(lease==null)throw new IOException("维护正在进行");
            RemoteMaintenance.requireUnreserved();RemoteMaintenance.requireRepairReady();
            PackageInfo old=pm.getPackageInfo(LEGACY,PackageManager.GET_DISABLED_COMPONENTS);
            PackageInfo current=pm.getPackageInfo(CURRENT,0);
            if(old.versionCode!=2238 || current.versionCode<10 || !current.applicationInfo.enabled
                    || !CURRENT.equals(RemoteDeviceName.setting("secure","sms_default_application").trim())
                    || !args[1].equals(RescueFiles.sha256(new File(old.applicationInfo.sourceDir))))
                throw new IOException("旧包摘要、定制包或默认短信身份不符");
            File directory=new File(RemoteUpdatePlatform.RUNTIME,"legacy-quik-"+args[1]);
            if(!directory.isDirectory()&&!directory.mkdir())throw new IOException("原像目录不可用");
            File before=new File(directory,"before.json");
            JSONObject original;
            if(before.isFile()) original=new JSONObject(RescueFiles.read(before,4096));
            else {
                if(!args[0].equals("retire"))throw new IOException("没有真实恢复原像");
                original=new JSONObject().put("package",LEGACY).put("sha256",args[1])
                        .put("enabled_state",pm.getApplicationEnabledSetting(LEGACY))
                        .put("default_sms",CURRENT).put("current_enabled_state",pm.getApplicationEnabledSetting(CURRENT));
                if(original.getInt("enabled_state")!=PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    throw new IOException("旧包启用状态与预登记不符");
                RescueFiles.write(before,original.toString());
            }
            if(!LEGACY.equals(original.getString("package")) || !args[1].equals(original.getString("sha256")))
                throw new IOException("恢复原像不匹配");
            int target=args[0].equals("retire")?PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:original.getInt("enabled_state");
            File intent=File.createTempFile(args[0]+"-", ".json",directory);
            RescueFiles.write(intent,new JSONObject().put("action",args[0]).put("target_state",target).toString());
            pm.setApplicationEnabledSetting(LEGACY,target,0);
            if(pm.getApplicationEnabledSetting(LEGACY)!=target
                    || !CURRENT.equals(RemoteDeviceName.setting("secure","sms_default_application").trim())
                    || pm.getApplicationEnabledSetting(CURRENT)!=original.getInt("current_enabled_state"))
                throw new IOException("启用状态或默认短信回读不符，保留原像");
            JSONObject result=new JSONObject().put("action",args[0]).put("old_state",original.getInt("enabled_state"))
                    .put("new_state",target).put("default_sms_unchanged",true).put("custom_state_unchanged",true)
                    .put("apk_unchanged",args[1].equals(RescueFiles.sha256(new File(old.applicationInfo.sourceDir))))
                    .put("data_cleared",false).put("ok",true);
            RescueFiles.write(new File(intent.getPath()+".result.json"),result.toString());
            System.out.println(result);
        }
    }
}
