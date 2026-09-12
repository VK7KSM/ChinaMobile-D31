package net.elfradio.d31bootstrap.telemetry;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.telephony.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 复用D22无线定位观测及生产radio合同；不读取SSID、IMSI或更改网络开关。 */
public final class RemoteLocationRadio {
    public static final long MAX_AGE_MS = 120000;
    public static final long REPORT_MAX_AGE_MS = 900000;

    static boolean usableMac(String value) {
        if (value == null || !value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return false;
        String mac = value.toLowerCase(Locale.US);
        return (Integer.parseInt(mac.substring(0, 2), 16) & 3) == 0
                && !mac.equals("00:00:00:00:00:00") && !mac.startsWith("00:00:5e:");
    }

    static boolean recent(long atNanos, long nowNanos) {
        return atNanos > 0 && nowNanos >= atNanos && nowNanos - atNanos <= MAX_AGE_MS * 1000000L;
    }

    static JSONObject tower(String type, int id, int area, int mcc, int mnc, int strength) throws Exception {
        if (!Arrays.asList("gsm", "wcdma", "lte").contains(type)) return null;
        int max = "gsm".equals(type) ? 65535 : 268435455;
        if (id < 0 || id > max || area < 0 || area > 65535 || mcc < 1 || mcc > 999 || mnc < 0 || mnc > 999) return null;
        JSONObject row = new JSONObject().put("cellId", id).put("locationAreaCode", area)
                .put("mobileCountryCode", mcc).put("mobileNetworkCode", mnc);
        if (strength >= -150 && strength < 0) row.put("signalStrength", strength);
        return row;
    }

    /** 返回冻结观测；无可用数据也保留缺失原因，绝不凭SIM状态构造小区。 */
    static JSONObject capture(Context context) throws Exception {
        return capture(context, true);
    }

    static JSONObject capture(Context context, boolean allowScan) throws Exception {
        long beginWall = System.currentTimeMillis(), beginElapsed = SystemClock.elapsedRealtimeNanos();
        JSONObject value = new JSONObject();
        JSONArray wifiRows = new JSONArray(), cells = new JSONArray();
        long oldest = Long.MAX_VALUE;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                RemoteLocationWifi.Result scan = RemoteLocationWifi.capture(new RemoteLocationWifi.Access() {
                    public List<RemoteLocationWifi.Scan> results() {
                        List<RemoteLocationWifi.Scan> rows = new ArrayList<RemoteLocationWifi.Scan>();
                        List<ScanResult> raw = wifi.getScanResults();
                        if (raw != null) for (ScanResult row : raw)
                            rows.add(row == null ? null : new RemoteLocationWifi.Scan(row.BSSID, row.level, row.timestamp));
                        return rows;
                    }
                    public boolean enabled() { return wifi.isWifiEnabled(); }
                    public boolean start() { return wifi.startScan(); }
                    public long nowNanos() { return SystemClock.elapsedRealtimeNanos(); }
                    public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
                }, allowScan);
                value.put("wifi_raw_count", scan.rawCount).put("wifi_valid_count", scan.validCount)
                        .put("wifi_initial_raw_count", scan.initialRawCount).put("wifi_initial_valid_count", scan.initialValidCount)
                        .put("wifi_scan_result", scan.scanResult).put("wifi_scan_wait_ms", scan.waitMs)
                        .put("wifi_newest_age_ms", scan.newestAgeMs == null ? JSONObject.NULL : scan.newestAgeMs);
                for (RemoteLocationWifi.Scan row : scan.valid) {
                    wifiRows.put(new JSONObject().put("macAddress", row.mac.toLowerCase(Locale.US)).put("signalStrength", row.level));
                    oldest = Math.min(oldest, row.timestampMicros * 1000L);
                    if (wifiRows.length() == 6) break;
                }
                value.put("wifi_reason", wifiRows.length() >= 2 ? "observed" : scan.enabled ? "insufficient_fresh_access_points" : "wifi_disabled");
            } else value.put("wifi_reason", "provider_unavailable").put("wifi_scan_result", "provider_unavailable");
        } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw cancelled; }
        catch (SecurityException denied) { value.put("wifi_reason", "permission_denied").put("wifi_scan_result", "permission_denied"); }
        catch (Exception unavailable) { value.put("wifi_reason", "provider_unavailable").put("wifi_scan_result", "provider_unavailable"); }
        try {
            TelephonyManager phone = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            List<CellInfo> info = phone == null ? null : phone.getAllCellInfo();
            String selected = "";
            if (info != null) {
                info = new ArrayList<CellInfo>(info);
                Collections.sort(info, (a, b) -> Boolean.compare(b.isRegistered(), a.isRegistered()));
                Set<String> seen = new HashSet<String>();
                for (CellInfo cell : info) {
                    // D22允许注册小区零时间戳，但D31尚无相同证据，不能照搬为新鲜观测。
                    if (!recent(cell.getTimeStamp(), SystemClock.elapsedRealtimeNanos())) continue;
                    JSONObject row = null; String type = "";
                    if (cell instanceof CellInfoLte) {
                        CellInfoLte c = (CellInfoLte) cell; CellIdentityLte d = c.getCellIdentity(); type = "lte";
                        row = tower(type, d.getCi(), d.getTac(), d.getMcc(), d.getMnc(), c.getCellSignalStrength().getDbm());
                    } else if (cell instanceof CellInfoWcdma) {
                        CellInfoWcdma c = (CellInfoWcdma) cell; CellIdentityWcdma d = c.getCellIdentity(); type = "wcdma";
                        row = tower(type, d.getCid(), d.getLac(), d.getMcc(), d.getMnc(), c.getCellSignalStrength().getDbm());
                    } else if (cell instanceof CellInfoGsm) {
                        CellInfoGsm c = (CellInfoGsm) cell; CellIdentityGsm d = c.getCellIdentity(); type = "gsm";
                        row = tower(type, d.getCid(), d.getLac(), d.getMcc(), d.getMnc(), c.getCellSignalStrength().getDbm());
                    }
                    if (row == null || (!selected.isEmpty() && !selected.equals(type))) continue;
                    String key = type + ":" + row.getInt("cellId") + ":" + row.getInt("locationAreaCode") + ":"
                            + row.getInt("mobileCountryCode") + ":" + row.getInt("mobileNetworkCode");
                    if (!seen.add(key)) continue;
                    selected = type; cells.put(row); oldest = Math.min(oldest, cell.getTimeStamp());
                    if (cells.length() == 4) break;
                }
            }
            if (!selected.isEmpty()) value.put("radioType", selected);
            value.put("cell_reason", cells.length() > 0 ? "observed" : "no_fresh_cell_observation");
        } catch (SecurityException denied) { value.put("cell_reason", "permission_denied"); }
        catch (Exception unavailable) { value.put("cell_reason", "provider_unavailable"); }
        long elapsed = SystemClock.elapsedRealtimeNanos(), wall = System.currentTimeMillis();
        if (elapsed < beginElapsed || wall < beginWall || Math.abs((wall - beginWall) - (elapsed - beginElapsed) / 1000000L) > 1000)
            return new JSONObject().put("sampled_at_ms", wall).put("wifiAccessPoints", new JSONArray())
                    .put("cellTowers", new JSONArray()).put("wifi_reason", "clock_unstable").put("cell_reason", "clock_unstable");
        // 使用最旧有效观测的真实采样时间，不能把读取缓存的时间伪装成扫描时间。
        long sampled = oldest == Long.MAX_VALUE ? wall : wall - Math.max(0, (elapsed - oldest) / 1000000L);
        return value.put("sampled_at_ms", sampled).put("wifiAccessPoints", wifiRows).put("cellTowers", cells);
    }

    /** 新观测进入缓存仍须满足两分钟新鲜度，不放宽扫描或桥回包准入。 */
    public static JSONObject validated(JSONObject radio, long now) throws Exception {
        return validated(radio, now, MAX_AGE_MS);
    }

    /** 已冻结观测按Web十五分钟合同参与新报告，保留原始采样时间。 */
    public static JSONObject forReport(JSONObject radio, long now) throws Exception {
        return validated(radio, now, REPORT_MAX_AGE_MS);
    }

    private static JSONObject validated(JSONObject radio, long now, long maxAgeMs) throws Exception {
        Object time = radio.opt("sampled_at_ms");
        if (!(time instanceof Number) || ((Number) time).doubleValue() != ((Number) time).longValue()
                || ((Number) time).longValue() <= 0 || now < ((Number) time).longValue()
                || now - ((Number) time).longValue() > maxAgeMs) throw new IllegalArgumentException("STALE_RADIO_OBSERVATION");
        JSONArray input = radio.optJSONArray("wifiAccessPoints"), wifi = new JSONArray(), cells = new JSONArray();
        List<JSONObject> rows = new ArrayList<JSONObject>(); Set<String> seen = new HashSet<String>();
        if (input != null) for (int i = 0; i < Math.min(20, input.length()); i++) {
            JSONObject row = input.optJSONObject(i); if (row == null) continue;
            String mac = row.optString("macAddress", "").toLowerCase(Locale.US);
            if (!usableMac(mac) || !integer(row.opt("signalStrength"), -127, -1) || !seen.add(mac)) continue;
            rows.add(new JSONObject().put("macAddress", mac).put("signalStrength", row.getInt("signalStrength")));
        }
        Collections.sort(rows, (a, b) -> Integer.compare(b.optInt("signalStrength"), a.optInt("signalStrength")));
        if (rows.size() >= 2) for (int i = 0; i < Math.min(6, rows.size()); i++) wifi.put(rows.get(i));
        String type = radio.optString("radioType", "");
        input = radio.optJSONArray("cellTowers"); seen.clear();
        if (input != null && Arrays.asList("gsm", "wcdma", "lte").contains(type)) {
            for (int i = 0; i < Math.min(6, input.length()); i++) {
                JSONObject row = input.optJSONObject(i); if (row == null) continue;
                if (!integer(row.opt("cellId"), 0, "gsm".equals(type) ? 65535 : 268435455)
                        || !integer(row.opt("locationAreaCode"), 0, 65535) || !integer(row.opt("mobileCountryCode"), 1, 999)
                        || !integer(row.opt("mobileNetworkCode"), 0, 999)) continue;
                JSONObject clean = tower(type, row.getInt("cellId"), row.getInt("locationAreaCode"), row.getInt("mobileCountryCode"),
                        row.getInt("mobileNetworkCode"), integer(row.opt("signalStrength"), -150, -1) ? row.getInt("signalStrength") : 0);
                String key = clean.getInt("cellId") + ":" + clean.getInt("locationAreaCode") + ":"
                        + clean.getInt("mobileCountryCode") + ":" + clean.getInt("mobileNetworkCode");
                if (seen.add(key)) cells.put(clean);
            }
        }
        JSONObject result = new JSONObject().put("sampled_at_ms", time).put("wifiAccessPoints", wifi).put("cellTowers", cells);
        if (cells.length() > 0) result.put("radioType", type);
        for (String field : new String[]{"wifi_reason", "cell_reason", "wifi_scan_result"}) {
            String reason = radio.optString(field, "");
            if (reason.matches("[a-z_]{1,48}")) result.put(field, reason);
        }
        for (String field : new String[]{"wifi_raw_count", "wifi_valid_count", "wifi_initial_raw_count", "wifi_initial_valid_count"})
            if (integer(radio.opt(field), 0, 10000)) result.put(field, radio.getInt(field));
        for (String field : new String[]{"wifi_scan_wait_ms", "wifi_newest_age_ms"})
            if (integer(radio.opt(field), 0, Long.MAX_VALUE)) result.put(field, radio.getLong(field));
        return result;
    }
    private static boolean integer(Object value, long min, long max) {
        if (!(value instanceof Number)) return false;
        double n = ((Number) value).doubleValue(); return n >= min && n <= max && n == Math.floor(n);
    }
    private RemoteLocationRadio() { }
}
