package net.elfradio.d31bootstrap;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONObject;

/**
 * 原厂视频引擎的关键帧请求开关。2026-09-18 实测：「SIP FIR」与「强制FIR」不打开时，
 * 丢包或抖动缓冲区溢出后引擎不会发出关键帧请求，画面冻结到对端下一个周期关键帧（最长约20秒）。
 * 核心启动后自检并写回，之后每小时复查；只改这两个键的enable字段，保留原厂title/type。
 */
final class VendorVideoConfig {
    static final Uri BASE = Uri.parse("content://com.starnet.videobox.provider.ConfigProvider/base");
    static final String[] REQUIRED = {"SipInfo", "ForceFir"};
    static final String[] TITLES = {"SIP FIR", "强制FIR"};

    /** 纯JSON判断：已启用返回null，否则返回需要写回的新JSON原文。 */
    static String desired(String key, String title, String current) throws Exception {
        JSONObject item;
        if (current == null || current.isEmpty() || "null".equals(current)) item = new JSONObject().put("title", title).put("type", 0);
        else item = new JSONObject(current);
        if (item.optBoolean("enable")) return null;
        item.put("enable", true);
        return item.toString();
    }

    /** 逐键读取、必要时写回并回读；返回每个键的结果：ok / fixed / failed。 */
    static JSONObject ensure() throws Exception {
        JSONObject result = new JSONObject();
        try (RemoteContent content = new RemoteContent(BASE)) {
            for (int i = 0; i < REQUIRED.length; i++) {
                String key = REQUIRED[i];
                String next = desired(key, TITLES[i], read(content, key));
                if (next == null) { result.put(key, "ok"); continue; }
                ContentValues values = new ContentValues();
                values.put(key, next);
                content.update(BASE, values);
                result.put(key, desired(key, TITLES[i], read(content, key)) == null ? "fixed" : "failed");
            }
        }
        return result;
    }

    private static String read(RemoteContent content, String key) throws Exception {
        Cursor cursor = content.query(BASE, new String[]{key});
        if (cursor == null) return null;
        try {
            if (!cursor.moveToFirst()) return null;
            int column = cursor.getColumnIndex(key);
            return column < 0 ? null : cursor.getString(column);
        } finally { cursor.close(); }
    }

    private VendorVideoConfig() {}
}
