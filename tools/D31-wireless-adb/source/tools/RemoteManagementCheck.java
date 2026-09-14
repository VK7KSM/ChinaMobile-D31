package net.elfradio.d31bootstrap;

import android.content.Context;
import net.elfradio.d31bootstrap.management.SystemManagement;
import org.json.JSONObject;

/** 宿主只读诊断，完整异常仅保存本机私有证据。 */
public final class RemoteManagementCheck {
    public static void main(String[] args) throws Exception {
        if (android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE) || args.length != 1)
            throw new IllegalArgumentException("仅允许明确的D31只读检查");
        android.os.Looper.prepareMainLooper();
        Class<?> owner = Class.forName("android.app.ActivityThread");
        Object thread = owner.getMethod("systemMain").invoke(null);
        Context context = (Context) owner.getMethod("getSystemContext").invoke(thread);
        try {
            JSONObject result;
            if ("wifi-interface".equals(args[0])) {
                Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "wifi");
                Object wifi = Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
                for (java.lang.reflect.Method method : wifi.getClass().getMethods())
                    if (method.getName().matches("getConfiguredNetworks|getConnectionInfo|getWifiEnabledState|getWifiApEnabledState|getWifiApConfiguration"))
                        System.out.println(method.toGenericString());
                System.exit(0); return;
            } else if ("battery-interface".equals(args[0])) {
                Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
                for (java.lang.reflect.Method method : manager.getClass().getMethods())
                    if (method.getName().equals("registerReceiver")) System.out.println(method.toGenericString());
                try { new net.elfradio.d31bootstrap.telemetry.AndroidTelemetryAccess(context).battery(); }
                catch (Exception failure) { failure.printStackTrace(); }
                System.exit(0); return;
            } else if ("telemetry".equals(args[0])) result = new net.elfradio.d31bootstrap.telemetry.TelemetryCollector(
                    new net.elfradio.d31bootstrap.telemetry.AndroidTelemetryAccess(context),
                    net.elfradio.d31bootstrap.telemetry.AndroidTelemetryAccess.clock())
                    .collect(new net.elfradio.d31bootstrap.telemetry.TelemetryCollector.Limits(0, 300000)).toJson();
            else result = SystemManagement.execute(context, "system_config",
                    new JSONObject().put("group", args[0]).put("action", "read"));
            if ("telemetry".equals(args[0])) {
                JSONObject diagnostic = net.elfradio.d31bootstrap.telemetry.AppLocationCache.lastDiagnostic();
                if (diagnostic != null) result.put("private_location_diagnostic", diagnostic);
            }
            System.out.println(result); System.exit(0);
        } catch (Exception failure) { failure.printStackTrace(); System.exit(1); }
    }
}
