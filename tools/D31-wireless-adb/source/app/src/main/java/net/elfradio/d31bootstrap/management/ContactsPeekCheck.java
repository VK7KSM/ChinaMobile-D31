package net.elfradio.d31bootstrap.management;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import java.io.IOException;
import org.json.JSONObject;

/** 独立只读检查已有服务Binder，不绑定、不启动、不发送通讯录消息。 */
public final class ContactsPeekCheck {
    private static final String PACKAGE = "com.starnet.dial";
    private static final String SERVICE = "com.starnet.contactservice.remote.RemoteContactService";
    private static final String ACTION = "com.starnet.contactservice.RemoteContactService";

    public static void main(String[] args) {
        String phase = "GUARD";
        try {
            if (args == null || args.length != 1 || !"peek".equals(args[0])
                    || android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IOException("D31_PEEK_ONLY");
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            phase = "COMPONENT";
            ComponentName component = new ComponentName(PACKAGE, SERVICE);
            ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
            if (!PACKAGE.equals(info.packageName) || !SERVICE.equals(info.name) || !info.enabled
                    || !info.exported || !info.applicationInfo.enabled) throw new IOException("COMPONENT_MISMATCH");
            phase = "PEEK";
            Class<?> managerType = Class.forName("android.app.IActivityManager");
            Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            // D31冻结AMS要求callingPackage非null；此调用没有IApplicationThread参数。
            Object result = managerType.getMethod("peekService", Intent.class, String.class, String.class)
                    .invoke(manager, new Intent(ACTION).setComponent(component), null, context.getPackageName());
            if (result != null && !(result instanceof IBinder)) throw new IOException("BINDER_TYPE_MISMATCH");
            IBinder binder = (IBinder) result;
            phase = "DESCRIPTOR";
            boolean available = binder != null;
            boolean messenger = available && "android.os.IMessenger".equals(binder.getInterfaceDescriptor());
            boolean alive = available && binder.isBinderAlive();
            JSONObject metadata = metadata().put("ok", available && messenger && alive)
                    .put("phase", "COMPLETE").put("componentMatched", true).put("binderAvailable", available)
                    .put("messengerDescriptorMatched", messenger).put("binderAlive", alive)
                    .put("state", !available ? "EXISTING_BINDER_UNAVAILABLE" : messenger && alive ? "EXISTING_MESSENGER_FOUND" : "BINDER_MISMATCH");
            System.out.println(metadata.toString());
            System.exit(metadata.getBoolean("ok") ? 0 : 1);
        } catch (Exception failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            try { System.out.println(metadata().put("ok", false).put("phase", phase)
                    .put("failureClass", root.getClass().getSimpleName()).toString()); }
            catch (Exception ignored) { System.err.println("CONTACTS_PEEK_CHECK_FAILED"); }
            System.exit(1);
        }
    }

    private static JSONObject metadata() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("kind", "NEXUI_EXISTING_BINDER_CHECK")
                .put("readOnly", true).put("ownerPackage", PACKAGE).put("bindingRequested", false)
                .put("serviceStartRequested", false).put("contactRequestSent", false)
                .put("contactValuesEmitted", false).put("listComplete", false);
    }
    private ContactsPeekCheck() { }
}
