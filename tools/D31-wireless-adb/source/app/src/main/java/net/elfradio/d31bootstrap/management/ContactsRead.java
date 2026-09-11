package net.elfradio.d31bootstrap.management;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** 两种来源分别读取；普通Android号码行不得冒充Nexui联系人或用于原厂写入。 */
public final class ContactsRead {
    static final String ANDROID_AUTHORITY = "com.android.contacts";
    static final String NEXUI_AUTHORITY = "com.starnet.contactservice.contact.provider2";
    private static final long MAX_WEB_ID = 9007199254740991L;
    private static final int LIMIT = 1000;
    private static final int TEXT_LIMIT = 14000;

    interface Rows extends AutoCloseable {
        boolean next() throws Exception;
        long id() throws Exception;
        String name() throws Exception;
        String phone() throws Exception;
        void close() throws Exception;
    }

    public static JSONObject validate(String type, JSONObject params) throws Exception {
        if (!"contacts_read".equals(type) || params == null || params.length() != 0)
            throw new IOException("仅支持现有contacts_read空参数读取合同，不支持通讯录写入或自选URI");
        return new JSONObject();
    }

    public static JSONObject inventory(Context context) throws Exception {
        return ContactsStructureCheck.inventory(context);
    }

    /** 结果contacts符合Web形状，但调用者必须先明确普通Android来源，不默认接到Nexui管理。 */
    public static JSONObject readAndroid(SystemManagement.Control control) throws Exception {
        requireControl(control);
        control.check();
        try (ContactsStructureCheck.Provider provider = new ContactsStructureCheck.Provider(ANDROID_AUTHORITY)) {
            final Cursor cursor = provider.query(Phone.CONTENT_URI,
                    new String[]{Phone._ID, Phone.DISPLAY_NAME, Phone.NUMBER}, null, Phone._ID + " ASC");
            try (Rows rows = new Rows() {
                public boolean next() { return cursor.moveToNext(); }
                public long id() throws Exception {
                    int column = cursor.getColumnIndexOrThrow(Phone._ID);
                    if (cursor.isNull(column) || cursor.getType(column) != Cursor.FIELD_TYPE_INTEGER)
                        throw new IOException("Android号码行编号不是整数");
                    return cursor.getLong(column);
                }
                public String name() throws Exception { return text(Phone.DISPLAY_NAME); }
                public String phone() throws Exception { return text(Phone.NUMBER); }
                private String text(String field) throws IOException {
                    int column = cursor.getColumnIndexOrThrow(field);
                    if (cursor.isNull(column)) return null;
                    if (cursor.getType(column) != Cursor.FIELD_TYPE_STRING) throw new IOException("联系人文本列类型不符");
                    return cursor.getString(column);
                }
                public void close() { cursor.close(); }
            }) {
                return readRows(rows, control, System.currentTimeMillis());
            }
        }
    }

    static JSONObject readRows(Rows rows, SystemManagement.Control control, long sampledAt) throws Exception {
        requireControl(control);
        if (sampledAt <= 0 || sampledAt > MAX_WEB_ID) throw new IOException("联系人采样时间无效");
        JSONArray items = new JSONArray();
        Set<Long> seen = new HashSet<>();
        boolean truncated = false;
        String reason = "";
        int chars = 0;
        while (true) {
            control.check();
            if (!rows.next()) break;
            if (items.length() == LIMIT) { truncated = true; reason = "item_limit"; break; }
            long id = rows.id();
            String name = rows.name(), phone = rows.phone();
            if (name == null) name = "";
            if (phone == null) phone = "";
            if (id <= 0 || id > MAX_WEB_ID || !seen.add(id) || name.length() > 500 || phone.length() > 200)
                throw new IOException("联系人行不满足现有Web编号或文本合同");
            JSONObject row = new JSONObject().put("id", id).put("name", name).put("phone", phone);
            int length = row.toString().length() + 1;
            if (chars + length > TEXT_LIMIT) { truncated = true; reason = "receipt_limit"; break; }
            items.put(row); chars += length;
        }
        control.check();
        JSONObject contacts = new JSONObject().put("sampled_at_ms", sampledAt).put("items", items).put("truncated", truncated);
        return new JSONObject().put("ok", true).put("read_only", true).put("source", "android_contacts_phone_rows")
                .put("authority", ANDROID_AUTHORITY).put("nexui_equivalence", "NOT_VERIFIED")
                .put("truncation_reason", reason).put("contacts", contacts);
    }

    /** 仅复用原厂静态代码已经出现的按lookup查询；保留原厂JSON，不映射为Android行编号。 */
    public static JSONObject nexuiLookup(String lookup, SystemManagement.Control control) throws Exception {
        requireControl(control);
        validateLookup(lookup);
        control.check();
        Uri uri = Uri.parse("content://" + NEXUI_AUTHORITY + "/getContactByLookup");
        try (ContactsStructureCheck.Provider provider = new ContactsStructureCheck.Provider(NEXUI_AUTHORITY);
             Cursor cursor = provider.query(uri, new String[]{"getContactByLookup"}, new String[]{lookup}, null)) {
            control.check();
            String raw = null;
            if (cursor.moveToFirst()) {
                int column = cursor.getColumnIndexOrThrow("getContactByLookup");
                if (cursor.isNull(column)) throw new IOException("原厂联系人列为空，尚不能解释为不存在");
                raw = cursor.getString(column);
                if (cursor.moveToNext()) throw new IOException("原厂lookup返回多行，合同尚未确认");
            }
            JSONObject result = vendorResult(raw, System.currentTimeMillis());
            control.check();
            return result;
        }
    }

    static void validateLookup(String lookup) throws IOException {
        if (lookup == null || lookup.isEmpty() || lookup.length() > 256) throw new IOException("必须显式提供已有原厂lookup");
        for (int i = 0; i < lookup.length(); i++) if (Character.isISOControl(lookup.charAt(i))) throw new IOException("lookup包含控制字符");
    }

    static JSONObject vendorResult(String raw, long sampledAt) throws Exception {
        if (sampledAt <= 0 || sampledAt > MAX_WEB_ID) throw new IOException("原厂联系人采样时间无效");
        JSONObject out = new JSONObject().put("ok", true).put("read_only", true).put("source", "nexui_lookup")
                .put("authority", NEXUI_AUTHORITY).put("sampled_at_ms", sampledAt).put("list_complete", false)
                .put("android_equivalence", "NOT_VERIFIED");
        if (raw == null) return out.put("found", false).put("query_result", "empty_cursor");
        if (raw.length() > 8000) throw new IOException("原厂单联系人返回超限，未截断冒充完整记录");
        if ("null".equals(raw.trim())) return out.put("found", false).put("query_result", "json_null");
        JSONTokener parser = new JSONTokener(raw);
        Object record = parser.nextValue();
        if (!(record instanceof JSONObject) || parser.nextClean() != 0) throw new IOException("原厂联系人JSON形状或尾部无效");
        return out.put("found", true).put("record", record);
    }

    private static void requireControl(SystemManagement.Control control) throws IOException {
        if (control == null) throw new IOException("缺少读取取消控制");
    }
    private ContactsRead() { }
}
