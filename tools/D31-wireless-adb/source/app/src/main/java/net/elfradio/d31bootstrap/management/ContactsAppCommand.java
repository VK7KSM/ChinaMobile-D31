package net.elfradio.d31bootstrap.management;

import android.content.Context;
import java.io.IOException;
import net.elfradio.d31bootstrap.RemoteContactsAccess;
import org.json.JSONObject;

/** 现有root_exec入口，仅输出绑定元数据；公共维护接线缺失时失败关闭。 */
public final class ContactsAppCommand {
    static String validate(String[] args) throws IOException {
        if (args == null || args.length != 2 || !"metadata".equals(args[0])) throw new IOException("CONTACTS_ARGUMENTS_INVALID");
        return ContactsAppContract.digest(args[1]);
    }

    public static void main(String[] args) {
        JSONObject result;
        try {
            String digest = validate(args); ContactsAppContract.device();
            if (android.os.Process.myUid() != 0) throw new IOException("CONTACTS_ROOT_REQUIRED");
            try (AutoCloseable lease = RemoteContactsAccess.acquire(digest)) {
                if (lease == null) throw new IOException("CONTACTS_MAINTENANCE_UNAVAILABLE");
                if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
                Class<?> type = Class.forName("android.app.ActivityThread");
                Object thread = type.getMethod("systemMain").invoke(null);
                Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
                try (ContactsAppBridge bridge = new ContactsAppBridge(context)) {
                    result = bridge.checkBinding(digest, new SystemManagement.Control() {
                        public void check() throws Exception { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
                        public void before(JSONObject value) throws Exception { throw new IOException("CONTACTS_WRITES_FORBIDDEN"); }
                    }).put("maintenanceGatePassed", true).put("activeApkHashMatched", true);
                }
            }
        } catch (Exception failed) {
            try {
                result = ContactsAppContract.metadata(ContactsAppContract.code(failed)).put("remoteOutcomeKnown", false)
                        .put("unbindConfirmed", JSONObject.NULL);
            } catch (Exception impossible) { result = new JSONObject(); }
        }
        System.out.println(result.toString());
        System.exit(result.optBoolean("ok", false) ? 0 : 1);
    }
    private ContactsAppCommand() { }
}
