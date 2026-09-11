package net.elfradio.d31bootstrap.lostmode;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

/** 只读适配准备；不接收set/wipe，不保存口令、不创建任何清除计时器。 */
public final class LostModeReadiness {
    public interface Locks { boolean locked()throws Exception; boolean secure()throws Exception; }
    private LostModeReadiness(){}
    public static JSONObject snapshot(Context context)throws Exception {
        final KeyguardManager keys=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        return snapshot(Build.VERSION.SDK_INT,new Locks(){
            public boolean locked(){if(keys==null)throw new IllegalStateException();return keys.isKeyguardLocked();}
            public boolean secure(){if(keys==null)throw new IllegalStateException();return keys.isKeyguardSecure();}
        });
    }
    public static JSONObject snapshot(int sdk,Locks locks)throws Exception {
        JSONObject result=new JSONObject().put("schemaVersion",1).put("operation","lost_mode_readiness").put("api_level",sdk)
                .put("managed_lost_tasks",false).put("managed_lost_v2",false).put("state","NOT_READY")
                .put("reason","D31_SYSTEM_CREDENTIAL_ADAPTER_NOT_VERIFIED").put("contract_version",2)
                .put("read_only",true).put("lock_write_supported",false).put("wipe_supported",false);
        try {result.put("system_lock",new JSONObject().put("state","OBSERVED").put("locked",locks.locked()).put("secure",locks.secure()));}
        catch(Exception unavailable){result.put("system_lock",new JSONObject().put("state","READ_FAILED"));}
        return result;
    }
}
