package net.elfradio.d31bootstrap.management;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 可用Build-RemoteCheck单文件构建；仅结构与计数，不输出个人联系人字段。 */
public final class ContactsStructureCheck {
    static final String ANDROID_AUTHORITY = "com.android.contacts";
    static final String NEXUI_AUTHORITY = "com.starnet.contactservice.contact.provider2";

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !"structure".equals(args[0]) || android.os.Process.myUid() != 0
                    || android.os.Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IOException("仅允许D31只读结构检查");
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
            JSONObject result = inspect(context);
            System.out.println(result.toString());
            System.exit(result.getBoolean("checks_ok") ? 0 : 1);
        } catch (Exception error) {
            System.err.println("通讯录结构检查失败：" + root(error).getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static JSONObject inventory(Context context) throws Exception {
        if (context == null) throw new IOException("缺少系统上下文");
        JSONArray providers = new JSONArray();
        PackageManager pm = context.getPackageManager();
        for (String authority : new String[]{ANDROID_AUTHORITY, NEXUI_AUTHORITY}) {
            ProviderInfo info = pm.resolveContentProvider(authority, PackageManager.GET_DISABLED_COMPONENTS);
            JSONObject item = new JSONObject().put("authority", authority).put("resolved", info != null);
            if (info != null) item.put("package", info.packageName).put("component", info.name)
                    .put("exported", info.exported).put("enabled", info.enabled && info.applicationInfo.enabled)
                    .put("apk_path", info.applicationInfo.sourceDir)
                    .put("read_permission", info.readPermission == null ? JSONObject.NULL : info.readPermission)
                    .put("write_permission", info.writePermission == null ? JSONObject.NULL : info.writePermission);
            providers.put(item);
        }
        return new JSONObject().put("read_only", true).put("queried_contacts", false)
                .put("sampled_at_ms", System.currentTimeMillis()).put("providers", providers)
                .put("nexui_equivalence", "NOT_VERIFIED");
    }

    public static JSONObject inspect(Context context) throws Exception {
        JSONObject result = inventory(context).put("contact_values_emitted", false);
        boolean ok = true;
        try (Provider provider = new Provider(ANDROID_AUTHORITY);
             Cursor cursor = provider.query(Phone.CONTENT_URI, new String[]{Phone._ID}, null, null)) {
            result.put("android_phone_rows", new JSONObject().put("queried", true).put("count", cursor.getCount())
                    .put("columns", new JSONArray(java.util.Arrays.asList(cursor.getColumnNames()))));
        } catch (Exception error) {
            ok = false;
            result.put("android_phone_rows", new JSONObject().put("queried", false).put("error_type", root(error).getClass().getSimpleName()));
        }
        try (Provider provider = new Provider(NEXUI_AUTHORITY)) {
            result.put("nexui", new JSONObject().put("external_reference", true).put("list_queried", false)
                    .put("list_contract", "NOT_VERIFIED"));
        } catch (Exception error) {
            ok = false;
            result.put("nexui", new JSONObject().put("external_reference", false).put("list_queried", false)
                    .put("error_type", root(error).getClass().getSimpleName()));
        }
        result.remove("queried_contacts");
        return result.put("checks_ok", ok);
    }

    /** 复用D31已验证的API23外部Provider引用；不暴露写入或call接口。 */
    static final class Provider implements AutoCloseable {
        private final Class<?> managerType;
        private final Object manager;
        private final IBinder token = new Binder();
        private final String authority;
        private Object provider;
        private boolean acquired;

        Provider(String authority) throws Exception {
            if (!ANDROID_AUTHORITY.equals(authority) && !NEXUI_AUTHORITY.equals(authority)) throw new IOException("通讯录来源未核对");
            this.authority = authority;
            managerType = Class.forName("android.app.IActivityManager");
            manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            Object holder = managerType.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                    .invoke(manager, authority, 0, token);
            if (holder == null) throw new IOException("通讯录提供程序不可用");
            acquired = true;
            try {
                provider = holder.getClass().getField("provider").get(holder);
                if (provider == null) throw new IOException("通讯录提供程序尚未就绪");
            } catch (Exception failure) {
                try { close(); } catch (Exception release) { failure.addSuppressed(release); }
                throw failure;
            }
        }

        Cursor query(Uri uri, String[] projection, String[] selectionArgs, String sort) throws Exception {
            if (!authority.equals(uri.getAuthority())) throw new IOException("通讯录查询来源不符");
            Object value = Class.forName("android.content.IContentProvider").getMethod("query", String.class, Uri.class,
                    String[].class, String.class, String[].class, String.class, Class.forName("android.os.ICancellationSignal"))
                    .invoke(provider, null, uri, projection, null, selectionArgs, sort, null);
            if (!(value instanceof Cursor)) throw new IOException("通讯录查询未返回游标");
            return (Cursor) value;
        }

        public void close() throws Exception {
            if (acquired) {
                managerType.getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(manager, authority, token);
                acquired = false;
            }
        }
    }

    private static Throwable root(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error;
    }
    private ContactsStructureCheck() { }
}
