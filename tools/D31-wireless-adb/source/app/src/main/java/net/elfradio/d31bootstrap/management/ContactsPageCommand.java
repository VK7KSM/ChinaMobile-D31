package net.elfradio.d31bootstrap.management;

import android.content.Context;
import java.io.IOException;
import net.elfradio.d31bootstrap.RemoteAppOperation;
import net.elfradio.d31bootstrap.RemoteContactsAccess;
import org.json.JSONObject;

/** 显式只读LOCAL分页入口。open须由公共维护层登记独立操作类型，未接线时失败关闭。 */
public final class ContactsPageCommand {
    public static final String OPERATION = "read-local-pages";
    static void pageArguments(String id, int offset, int limit) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || offset < 0 || offset > ContactsNexui.MAX_RECORDS || limit < 1 || limit > ContactsNexui.MAX_PAGE)
            throw new IOException("CONTACTS_PAGE_ARGUMENTS_INVALID");
    }
    static String validate(String[] args) throws Exception {
        if (args == null || args.length < 3) throw new IOException("CONTACTS_PAGE_ARGUMENTS_INVALID");
        String hash = ContactsAppContract.digest(args[1]);
        if ("open".equals(args[0]) && args.length == 3 && args[2].matches("[A-Za-z0-9_-]{1,96}")) return hash;
        if ("close".equals(args[0]) && args.length == 3) { pageArguments(args[2], 0, 1); return hash; }
        if ("page".equals(args[0]) && args.length == 5 && args[3].matches("0|[1-9][0-9]{0,3}") && args[4].matches("[1-9][0-9]?")) {
            pageArguments(args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4])); return hash;
        }
        throw new IOException("CONTACTS_PAGE_ARGUMENTS_INVALID");
    }
    static JSONObject receipt(String state) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("kind", "NEXUI_APP_LOCAL_PAGE")
                .put("state", state).put("ok", false).put("readOnly", true).put("source", "nexui_messenger")
                .put("contact_type", "LOCAL").put("ownerPackage", ContactsNexuiAndroid.PACKAGE)
                .put("vendorRequestSent", false).put("all_sources_complete", false);
    }
    /** 生产任务适配复用同一执行与异常路径；调用者负责有界结果和原任务回执。 */
    public static JSONObject execute(String[] args) {
        JSONObject result;
        try {
            String hash = validate(args); ContactsAppContract.device();
            if (android.os.Process.myUid() != 0) throw new IOException("CONTACTS_ROOT_REQUIRED");
            Context context = RemoteAppOperation.context();
            if ("open".equals(args[0])) {
                // 不借用metadata编号，避免同号重试把旧计数回执当成新内容快照。
                try (RemoteAppOperation operation = RemoteAppOperation.begin(context, OPERATION, args[2], hash, new JSONObject())) {
                    if (!operation.shouldExecute()) result = operation.previousResult();
                    else try (ContactsAppBridge bridge = new ContactsAppBridge(context)) {
                        result = bridge.openLocalPages(hash, RemoteAppOperation.control(), operation::arm);
                        operation.finish(result);
                    }
                    JSONObject status = operation.status();
                    result.put("app_operation", status).put("operation_id", args[2])
                            .put("maintenanceGatePassed", true).put("activeApkHashMatched", true);
                    if (!status.getBoolean("reservation_released")) { result.put("ok", false); result.remove("page_snapshot"); }
                }
            } else {
                try (AutoCloseable lease = RemoteContactsAccess.acquire(hash);
                        ContactsAppBridge bridge = new ContactsAppBridge(context)) {
                    if (lease == null) throw new IOException("CONTACTS_MAINTENANCE_UNAVAILABLE");
                    result = "page".equals(args[0])
                            ? bridge.readLocalPage(hash, args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), RemoteAppOperation.control())
                            : bridge.closeLocalPages(hash, args[2], RemoteAppOperation.control());
                    result.put("maintenanceGatePassed", true).put("activeApkHashMatched", true);
                }
            }
        } catch (Exception failure) {
            try {
                String code = failure.getMessage();
                result = receipt(code != null && code.matches("APP_OPERATION_[A-Z0-9_]{1,80}") ? code : ContactsAppContract.code(failure));
            } catch (Exception ignored) { result = new JSONObject(); }
        }
        return result;
    }
    public static void main(String[] args) {
        JSONObject result = execute(args);
        System.out.println(result.toString());
        System.exit(result.optBoolean("ok", false) ? 0 : 1);
    }
    private ContactsPageCommand() { }
}
