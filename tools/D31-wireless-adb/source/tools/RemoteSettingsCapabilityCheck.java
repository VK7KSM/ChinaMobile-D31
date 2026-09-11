package net.elfradio.d31bootstrap;

import org.json.JSONObject;

public final class RemoteSettingsCapabilityCheck {
    private static boolean method(String owner,String name)throws Exception{
        for(java.lang.reflect.Method m:Class.forName(owner).getMethods())if(m.getName().equals(name))return true;
        return false;
    }
    private static boolean field(String owner,String name)throws Exception{
        try{Class.forName(owner).getField(name);return true;}catch(NoSuchFieldException absent){return false;}
    }
    private static boolean type(String name){try{Class.forName(name);return true;}catch(ClassNotFoundException absent){return false;}}
    public static void main(String[] args)throws Exception{
        JSONObject result=new JSONObject().put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("background_operation",field("android.app.AppOpsManager","OP_RUN_IN_BACKGROUND"))
            .put("activity_service_modern",method("android.app.ActivityManager","getService"))
            .put("activity_service_legacy",method("android.app.ActivityManagerNative","getDefault"))
            .put("configuration_locales",method("android.content.res.Configuration","getLocales"))
            .put("configuration_locale",field("android.content.res.Configuration","locale"))
            .put("alarm_listener",type("android.app.AlarmManager$OnAlarmListener"))
            .put("hotspot_legacy",method("android.net.wifi.WifiManager","setWifiApEnabled"))
            .put("hotspot_modern",method("android.net.ConnectivityManager","startTethering"))
            .put("user_handle_modern",method("android.os.UserHandle","getUserHandleForUid"))
            .put("data_enabled_legacy",method("android.telephony.TelephonyManager","getDataEnabled"));
        System.out.println(result.toString());
    }
}
