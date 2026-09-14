package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 单项产品配置事务；调用方必须在整个调用期间持有公共维护锁。 */
public final class RemoteProductRepair {
    public static final String CATALOG = "d31-product-repair-2";
    public interface Platform {
        String build() throws Exception;
        JSONObject identity(Item item) throws Exception;
        Object read(Item item) throws Exception;
        void write(Item item, Object value) throws Exception;
    }
    public interface Store {
        JSONObject load(String id) throws Exception;
        void save(String id, JSONObject record) throws Exception;
        void reserve(String id, String digest) throws Exception;
        void release(String id, String digest) throws Exception;
    }
    public static final class Item {
        public final String id, kind, pkg, name;
        private Item(String kind, String pkg, String name) {
            this.kind = kind; this.pkg = pkg; this.name = name;
            id = kind + ":" + pkg + ":" + name;
        }
    }
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    static {
        permissions("net.elfradio.d31bootstrap", "CAMERA", "RECORD_AUDIO", "READ_PHONE_STATE",
                "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION");
        permissions("net.elfradio.d31phone.debug", "READ_SMS", "SEND_SMS", "RECEIVE_SMS",
                "READ_CONTACTS", "READ_EXTERNAL_STORAGE");
        permissions("net.elfradio.d31system", "READ_PHONE_STATE");
        permissions("com.loudtalks", "RECORD_AUDIO");
        // FINE_LOCATION的操作开关映射到COARSE_LOCATION，不能伪装成独立单项事务。
        for (String op : new String[]{"CAMERA", "RECORD_AUDIO", "COARSE_LOCATION"})
            add("appop", "net.elfradio.d31bootstrap", op);
        for (String op : new String[]{"READ_SMS", "SEND_SMS"}) add("appop", "net.elfradio.d31phone.debug", op);
        // 旧广播入口已交接系统核心并明确停用，不属于权限修复的启用目标。
        component("net.elfradio.d31bootstrap", "RemoteManualReceiver");
        component("net.elfradio.d31system", "SystemReceiver");
    }
    private static void permissions(String pkg, String... names) {
        for (String name : names) add("permission", pkg, "android.permission." + name);
    }
    private static void component(String pkg, String name) { add("component", pkg, pkg + "." + name); }
    private static void add(String kind, String pkg, String name) {
        Item item = new Item(kind, pkg, name); ITEMS.put(item.id, item);
    }
    public static Collection<Item> catalog() { return Collections.unmodifiableCollection(ITEMS.values()); }
    public static Item item(String id) throws IOException {
        Item item = ITEMS.get(id);
        if (item == null) throw new IOException("修复项不在固定目录");
        return item;
    }
    static void keys(JSONObject value, String... names) throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList(names));
        if (value.length() != allowed.size()) throw new IOException("请求字段不完整或有额外字段");
        Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) if (!allowed.contains(iterator.next())) throw new IOException("未知请求字段");
    }
    static String string(JSONObject value, String key) throws Exception {
        Object result = value.get(key);
        if (!(result instanceof String)) throw new IOException("字段必须是字符串");
        return (String) result;
    }
    static void validId(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw new IOException("操作编号无效");
    }
    static void value(Item item, Object value, boolean target) throws Exception {
        if (item.kind.equals("permission")) {
            if (!(value instanceof Boolean) || target && !Boolean.TRUE.equals(value))
                throw new IOException("权限目标仅允许授予");
        } else if (item.kind.equals("appop")) {
            if (!(value instanceof Integer) || (Integer) value < 0 || (Integer) value > 3
                    || target && (Integer) value != 0) throw new IOException("操作模式无效");
        } else {
            if (!(value instanceof JSONObject)) throw new IOException("组件值无效");
            JSONObject object = (JSONObject) value; keys(object, "setting", "enabled");
            Object setting = object.get("setting"), enabled = object.get("enabled");
            if (!(setting instanceof Integer) || (Integer) setting < 0 || (Integer) setting > 2
                    || !(enabled instanceof Boolean) || target && ((Integer) setting != 1 || !((Boolean) enabled)))
                throw new IOException("组件目标仅允许显式启用");
        }
    }
    static boolean same(Object left, Object right) throws Exception {
        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject a = (JSONObject) left, b = (JSONObject) right;
            if (a.length() != b.length()) return false;
            Iterator<String> keys = a.keys();
            while (keys.hasNext()) { String key = keys.next(); if (!b.has(key) || !same(a.get(key), b.get(key))) return false; }
            return true;
        }
        return left != null && left.equals(right);
    }
    private final Platform platform;
    private final Store store;
    public RemoteProductRepair(Platform platform, Store store) { this.platform = platform; this.store = store; }

    /** 双读前像与包身份；不访问事务存储，不把多项读取称为原子快照。 */
    public JSONObject inspect(String itemId) throws Exception {
        Item item = item(itemId);
        String build = platform.build();
        JSONObject identity = platform.identity(item);
        Object expected = platform.read(item); value(item, expected, false);
        if (build == null || build.isEmpty() || build.length() > 512
                || !build.equals(platform.build()) || !same(identity, platform.identity(item))
                || !same(expected, platform.read(item))) throw new IOException("读取期间产品状态变化");
        Object target = item.kind.equals("permission") ? Boolean.TRUE : item.kind.equals("appop") ? Integer.valueOf(0)
                : new JSONObject().put("setting", 1).put("enabled", true);
        return new JSONObject().put("catalog", CATALOG).put("item_id", item.id).put("kind", item.kind)
                .put("expected", expected).put("target", target).put("build_fingerprint", build)
                .put("identity", identity).put("captured_at_ms", System.currentTimeMillis())
                .put("state", "OBSERVED").put("user_id", 0).put("atomic_snapshot", false);
    }
    public JSONObject inspect() throws Exception {
        JSONArray items = new JSONArray(); int observed = 0;
        for (Item item : catalog()) {
            try { items.put(inspect(item.id)); observed++; }
            catch (Exception failure) {
                items.put(new JSONObject().put("catalog", CATALOG).put("item_id", item.id).put("kind", item.kind)
                        .put("state", "READ_FAILED").put("reason", failure.getClass().getSimpleName()));
            }
        }
        return new JSONObject().put("catalog", CATALOG).put("items", items).put("observed", observed)
                .put("total", items.length()).put("status", observed == items.length() ? "COMPLETE" : "PARTIAL")
                .put("atomic_snapshot", false).put("system_consistency", "NOT_ASSESSED");
    }

    public JSONObject query(String id) throws Exception {
        validId(id); JSONObject result = store.load(id);
        if (result == null) throw new IOException("原操作编号不存在");
        return result;
    }
    public JSONObject apply(JSONObject plan) throws Exception {
        keys(plan, "catalog", "operation_id", "item_id", "build_fingerprint", "expected", "target");
        if (!CATALOG.equals(string(plan, "catalog"))) throw new IOException("修复目录版本不符");
        String id = string(plan, "operation_id"); validId(id);
        Item item = item(string(plan, "item_id"));
        String build = string(plan, "build_fingerprint");
        if (build.isEmpty() || build.length() > 512) throw new IOException("构建指纹无效");
        value(item, plan.get("expected"), false); value(item, plan.get("target"), true);
        JSONObject old = store.load(id);
        if (old != null) {
            if (!same(old.getJSONObject("plan"), plan)) throw new IOException("操作编号已绑定不同参数");
            return recover(id);
        }
        JSONObject record = new JSONObject().put("schema", 1).put("plan", new JSONObject(plan.toString()))
                .put("phase", "PREPARED").put("created_at_ms", System.currentTimeMillis())
                .put("verification_scope", "SINGLE_PRODUCT_CONFIGURATION").put("system_consistency", "NOT_ASSESSED");
        // 先持久化原号，再预留维护；此阶段不允许写系统配置。
        store.save(id, record);
        String digest = digest(plan); store.reserve(id, digest);
        try {
            if (!build.equals(platform.build())) throw new IOException("设备构建已变化");
            JSONObject identity = platform.identity(item);
            Object before = platform.read(item); value(item, before, false);
            record.put("identity", identity).put("before", before);
            if (!same(before, plan.get("expected"))) throw new IOException("实际原值与请求不符");
            if (same(before, plan.get("target")) || item.kind.equals("component")
                    && ((JSONObject) before).getBoolean("enabled")) return finish(id, record, "NOOP");
            record.put("phase", "APPLYING"); store.save(id, record);
            checkIdentity(record, item);
            if (!same(platform.read(item), before)) throw new IOException("写前状态发生变化");
            platform.write(item, plan.get("target"));
            checkIdentity(record, item);
            Object after = platform.read(item); record.put("after", after);
            if (!same(after, plan.get("target"))) throw new IOException("后读未达到目标");
            return finish(id, record, "SUCCEEDED");
        } catch (Exception failure) {
            record.put("reason", failure.getClass().getSimpleName());
            if (record.getString("phase").equals("PREPARED")) return finish(id, record, "REJECTED");
            // 成功记录已落盘但释放失败时，不反向覆盖已完成结果。
            if (terminal(record.getString("phase"))) throw failure;
            record.put("phase", "ROLLING_BACK"); store.save(id, record);
            return rollback(id, record, item);
        }
    }
    public JSONObject recover(String id) throws Exception {
        JSONObject record = query(id); JSONObject plan = record.getJSONObject("plan");
        String phase = record.getString("phase");
        if (terminal(phase)) { store.release(id, digest(plan)); return record; }
        store.reserve(id, digest(plan));
        if (phase.equals("PREPARED")) return finish(id, record, "REJECTED");
        if (!Arrays.asList("APPLYING", "ROLLING_BACK", "RECOVERY_REQUIRED").contains(phase))
            throw new IOException("未知持久事务阶段");
        record.put("phase", "ROLLING_BACK"); store.save(id, record);
        return rollback(id, record, item(plan.getString("item_id")));
    }
    private JSONObject rollback(String id, JSONObject record, Item item) throws Exception {
        try {
            checkIdentity(record, item);
            Object current = platform.read(item), before = record.get("before");
            if (!same(current, before)) {
                if (!same(current, record.getJSONObject("plan").get("target")))
                    throw new IOException("现场出现第三值，保留维护预留并拒绝覆盖");
                platform.write(item, before);
            }
            checkIdentity(record, item);
            Object restored = platform.read(item); record.put("restored", restored);
            if (!same(restored, before)) throw new IOException("回滚后读不符");
        } catch (Exception failure) {
            record.put("phase", "RECOVERY_REQUIRED").put("reason", failure.getClass().getSimpleName());
            store.save(id, record); return record;
        }
        return finish(id, record, "ROLLED_BACK");
    }
    private void checkIdentity(JSONObject record, Item item) throws Exception {
        if (!record.getJSONObject("plan").getString("build_fingerprint").equals(platform.build())
                || !same(record.getJSONObject("identity"), platform.identity(item)))
            throw new IOException("构建或包身份变化，拒绝覆盖");
    }
    private JSONObject finish(String id, JSONObject record, String phase) throws Exception {
        // 写盘失败保留原非终态，使恢复仍有机会收敛，不能仅靠内存成功释放预留。
        JSONObject finished = new JSONObject(record.toString()).put("phase", phase)
                .put("finished_at_ms", System.currentTimeMillis());
        store.save(id, finished); record.put("phase", phase);
        store.release(id, digest(record.getJSONObject("plan"))); return finished;
    }
    static boolean terminal(String phase) {
        return Arrays.asList("NOOP", "SUCCEEDED", "REJECTED", "ROLLED_BACK").contains(phase);
    }
    static String digest(JSONObject plan) throws Exception {
        // 固定字段顺序与长度前缀，避免JSON键顺序或分隔符影响原号绑定。
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        for (String key : new String[]{"catalog", "operation_id", "item_id", "build_fingerprint", "expected", "target"}) {
            Object value = plan.get(key);
            String text = value instanceof JSONObject ? ((JSONObject) value).getInt("setting") + ":"
                    + ((JSONObject) value).getBoolean("enabled") : value.toString();
            byte[] bytes = text.getBytes("UTF-8");
            hash.update((bytes.length + ":").getBytes("UTF-8")); hash.update(bytes);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : hash.digest()) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }
}
