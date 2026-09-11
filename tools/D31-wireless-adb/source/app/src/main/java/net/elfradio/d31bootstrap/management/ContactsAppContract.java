package net.elfradio.d31bootstrap.management;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** 本地按需绑定验收合同，不定义Web任务，不承载联系人消息。 */
final class ContactsAppContract {
    static final String PACKAGE = "net.elfradio.d31bootstrap";
    static final String SERVICE = PACKAGE + ".management.ContactsAppService";
    static final String ACTION = PACKAGE + ".management.CONTACTS_BIND_CHECK";
    static final String DESCRIPTOR = PACKAGE + ".management.IContactsBindCheck";
    static final int EXECUTE = 1, CANCEL = 2, EXECUTE_LOCAL = 3, INSPECT_LOCAL = 4, HELLO = 1, RESULT = 2, MAX_BYTES = 8192;
    static final long HANDSHAKE_MS = 3000, WORK_MS = 10000, REPLY_MS = 12000, BIND_MS = 5000;

    static void request(String id, String boot, long started, long now, long window) throws IOException {
        if (id == null || !id.matches("[a-f0-9-]{36}") || boot == null || !boot.matches("[a-f0-9-]{36}")
                || started < 0 || now < started || now - started >= window)
            throw new IOException("CONTACTS_REQUEST_EXPIRED_OR_INVALID");
    }

    static String digest(String value) throws IOException {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IOException("CONTACTS_APK_HASH_INVALID");
        return value;
    }

    static JSONObject envelope(String id, String boot, long started, JSONObject result) throws Exception {
        JSONObject value = new JSONObject().put("request_id", id).put("boot_id", boot).put("started_elapsed_ms", started);
        if (result != null) value.put("result", result);
        if (value.toString().getBytes("UTF-8").length > MAX_BYTES) throw new IOException("CONTACTS_REPLY_LIMIT");
        return value;
    }

    static JSONObject reply(int sender, int expected, String id, String boot, long started, long now, String encoded) throws Exception {
        request(id, boot, started, now, REPLY_MS);
        if (expected < 10000 || sender != expected || encoded == null || encoded.length() > MAX_BYTES
                || encoded.getBytes("UTF-8").length > MAX_BYTES) throw new IOException("CONTACTS_REPLY_IDENTITY_OR_SIZE");
        JSONObject value = new JSONObject(encoded);
        if (!id.equals(value.getString("request_id")) || !boot.equals(value.getString("boot_id"))
                || !(value.get("started_elapsed_ms") instanceof Number) || value.getLong("started_elapsed_ms") != started)
            throw new IOException("CONTACTS_REPLY_MISMATCH");
        return value;
    }

    static void device() throws IOException {
        if (Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(Build.DEVICE)
                || !"hct6737t_66_m0".equals(Build.MODEL)) throw new IOException("CONTACTS_D31_API23_REQUIRED");
    }

    static boolean applicationIdentity(int uid, String name, String frameworkName, String[] packages) {
        return uid >= 10000 && uid < 20000 && PACKAGE.equals(name) && PACKAGE.equals(frameworkName)
                && packages != null && Arrays.asList(packages).contains(PACKAGE);
    }

    static void application(Context context) throws Exception {
        device();
        Object frameworkName = Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null);
        int uid = android.os.Process.myUid();
        if (context == null || !applicationIdentity(uid, context.getPackageName(),
                frameworkName instanceof String ? (String) frameworkName : null,
                context.getPackageManager().getPackagesForUid(uid))) throw new IOException("CONTACTS_APP_IDENTITY_REQUIRED");
    }

    static Object start(Object manager, Intent intent) throws Exception {
        try {
            return Class.forName("android.app.IActivityManager").getMethod("startService",
                    Class.forName("android.app.IApplicationThread"), Intent.class, String.class, String.class, int.class)
                    .invoke(manager, null, intent, null, "", 0);
        } catch (InvocationTargetException wrapped) {
            throw new IOException("CONTACTS_APP_START_FAILED", wrapped.getCause());
        }
    }

    static String bootId() throws Exception {
        try (FileInputStream in = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
            byte[] bytes = new byte[65]; int n = in.read(bytes);
            if (n < 1 || n > 64 || in.read() != -1) throw new IOException("CONTACTS_BOOT_ID_INVALID");
            String value = new String(bytes, 0, n, "US-ASCII").trim();
            if (!value.matches("[a-f0-9-]{36}")) throw new IOException("CONTACTS_BOOT_ID_INVALID");
            return value;
        }
    }

    static JSONObject metadata(String state) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("kind", "NEXUI_APP_BIND_CHECK")
                .put("state", state).put("ok", false).put("readOnly", true)
                .put("ownerPackage", ContactsNexuiAndroid.PACKAGE).put("bindFlags", 0)
                .put("vendorServiceStartRequested", false).put("contactRequestSent", false)
                .put("contacts_requested", false)
                .put("contactValuesRead", false).put("contactValuesEmitted", false).put("listComplete", false);
    }

    static String code(Exception error) {
        String value = error.getMessage();
        return value != null && value.matches("CONTACTS_[A-Z_0-9]{1,80}") ? value : "CONTACTS_BIND_CHECK_FAILED";
    }
    /** 恢复只取原请求的脱敏回执；错误编号/摘要不得借用另一任务的清理结果。 */
    static JSONObject localReceipt(JSONObject cached,String id,String hash)throws Exception{
        if(cached==null||!id.equals(cached.optString("operation_request_id"))||!hash.equals(cached.optString("operation_apk_sha256")))
            return ContactsLocalRead.unknown("CONTACTS_OPERATION_PENDING_OR_UNKNOWN",true);
        return new JSONObject(cached.toString());
    }

    /** 同一进程只保留有效期限内的编号；满时拒绝，不能淘汰仍可重放的编号。 */
    static final class Requests {
        private final Map<String, Long> seen = new LinkedHashMap<String, Long>();
        synchronized void claim(String id, String boot, long started, long now) throws IOException {
            request(id, boot, started, now, HANDSHAKE_MS);
            Iterator<Long> ages = seen.values().iterator();
            while (ages.hasNext()) if (now - ages.next() >= REPLY_MS) ages.remove();
            if (seen.containsKey(id)) throw new IOException("CONTACTS_DUPLICATE_REQUEST");
            if (seen.size() >= 64) throw new IOException("CONTACTS_REQUEST_CAPACITY");
            seen.put(id, started);
        }
    }
    private ContactsAppContract() { }
}
