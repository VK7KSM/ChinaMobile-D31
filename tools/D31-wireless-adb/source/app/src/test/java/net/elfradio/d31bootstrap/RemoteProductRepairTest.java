package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 离线故障注入，不接触系统权限或设备。 */
public class RemoteProductRepairTest {
    @Test public void migratedReceiversAndOldCatalogAreNotWritable() throws Exception {
        for (String name : new String[]{"BootReceiver", "VendorNetworkReceiver"}) {
            Platform p = new Platform(); Memory store = new Memory();
            String id = "component:net.elfradio.d31bootstrap:net.elfradio.d31bootstrap." + name;
            RemoteProductRepair tx = new RemoteProductRepair(p, store);
            try { tx.inspect(id); fail(); } catch (IOException expected) { }
            try {
                tx.apply(plan().put("item_id", id)
                        .put("expected", new JSONObject().put("setting", 2).put("enabled", false))
                        .put("target", new JSONObject().put("setting", 1).put("enabled", true)));
                fail();
            } catch (IOException expected) { }
            assertEquals(0, p.reads); assertEquals(0, p.writes); assertTrue(store.records.isEmpty());
        }
        Platform p = new Platform();
        try { new RemoteProductRepair(p, new Memory()).apply(plan().put("catalog", "d31-product-repair-1")); fail(); }
        catch (IOException expected) { }
        assertEquals(0, p.writes); assertEquals(19, RemoteProductRepair.catalog().size());
    }
    static class Crash extends Error { }
    static class Memory implements RemoteProductRepair.Store {
        final Map<String, String> records = new HashMap<>();
        String owner, digest; String crashAt;
        public JSONObject load(String id) throws Exception {
            return records.containsKey(id) ? new JSONObject(records.get(id)) : null;
        }
        public void save(String id, JSONObject record) throws Exception {
            records.put(id, record.toString());
            if (record.getString("phase").equals(crashAt)) { crashAt = null; throw new Crash(); }
        }
        public void reserve(String id, String hash) throws Exception {
            if (owner != null && (!owner.equals(id) || !digest.equals(hash))) throw new IOException("另一事务预留");
            owner = id; digest = hash;
        }
        public void release(String id, String hash) throws Exception {
            if (owner != null && (!owner.equals(id) || !digest.equals(hash))) throw new IOException("不能释放其它事务");
            owner = null;
        }
    }
    static class Platform implements RemoteProductRepair.Platform {
        Object value = false;
        String build = "test-build";
        int version = 1, writes, reads;
        boolean crashWrite, failWrite, failAfterWrite, thirdValue;
        public String build() { return build; }
        public JSONObject identity(RemoteProductRepair.Item item) throws Exception { return new JSONObject().put("version", version); }
        public Object read(RemoteProductRepair.Item item) throws Exception {
            reads++;
            if (failAfterWrite && writes == 1) { failAfterWrite = false; throw new IOException("注入后读失败"); }
            return value;
        }
        public void write(RemoteProductRepair.Item item, Object target) throws Exception {
            writes++; value = thirdValue ? 2 : target;
            if (crashWrite) { crashWrite = false; throw new Crash(); }
            if (failWrite) { failWrite = false; throw new IOException("注入写后异常"); }
        }
    }
    static JSONObject plan() throws Exception {
        return new JSONObject().put("catalog", RemoteProductRepair.CATALOG).put("operation_id", "test-1")
                .put("item_id", "permission:net.elfradio.d31bootstrap:android.permission.CAMERA")
                .put("build_fingerprint", "test-build").put("expected", false).put("target", true);
    }
    static void phase(String phase, JSONObject result) throws Exception { assertEquals(phase, result.getString("phase")); }
    @Test public void successAndReplayArePersistent() throws Exception {
        Memory store = new Memory(); Platform platform = new Platform();
        RemoteProductRepair tx = new RemoteProductRepair(platform, store);
        phase("SUCCEEDED", tx.apply(plan())); assertEquals(1, platform.writes); assertNull(store.owner);
        platform.build = "different";
        phase("SUCCEEDED", new RemoteProductRepair(platform, store).query("test-1"));
        phase("SUCCEEDED", tx.apply(plan())); assertEquals(1, platform.writes);
    }
    @Test public void sameIdCannotChangePlan() throws Exception {
        Memory store = new Memory(); Platform p = new Platform(); RemoteProductRepair tx = new RemoteProductRepair(p, store);
        tx.apply(plan());
        try { tx.apply(plan().put("expected", true)); fail(); } catch (IOException expected) { }
        assertEquals(1, p.writes);
    }
    @Test public void identicalRealValueNeverWrites() throws Exception {
        Memory store = new Memory(); Platform p = new Platform(); p.value = true;
        phase("NOOP", new RemoteProductRepair(p, store).apply(plan().put("expected", true)));
        assertEquals(0, p.writes);
    }
    @Test public void effectiveComponentEnabledDoesNotRewriteDefault() throws Exception {
        Memory store = new Memory(); Platform p = new Platform();
        p.value = new JSONObject().put("setting", 0).put("enabled", true);
        JSONObject request = plan().put("item_id", "component:net.elfradio.d31system:net.elfradio.d31system.SystemReceiver")
                .put("expected", p.value).put("target", new JSONObject().put("setting", 1).put("enabled", true));
        phase("NOOP", new RemoteProductRepair(p, store).apply(request)); assertEquals(0, p.writes);
    }
    @Test public void wrongBuildOrStaleValueRejectsBeforeWrite() throws Exception {
        for (boolean wrongBuild : new boolean[]{true, false}) {
            Memory store = new Memory(); Platform p = new Platform();
            if (wrongBuild) p.build = "other"; else p.value = true;
            phase("REJECTED", new RemoteProductRepair(p, store).apply(plan()));
            assertEquals(0, p.writes); assertNull(store.owner);
        }
    }
    @Test public void writeOrPostReadFailureRollsBack() throws Exception {
        for (boolean writeFailure : new boolean[]{true, false}) {
            Memory store = new Memory(); Platform p = new Platform();
            p.failWrite = writeFailure; p.failAfterWrite = !writeFailure;
            phase("ROLLED_BACK", new RemoteProductRepair(p, store).apply(plan()));
            assertEquals(false, p.value); assertEquals(2, p.writes); assertNull(store.owner);
        }
    }
    @Test public void crashAfterWriteRecoversOriginalNumber() throws Exception {
        Memory store = new Memory(); Platform p = new Platform(); p.crashWrite = true;
        try { new RemoteProductRepair(p, store).apply(plan()); fail(); } catch (Crash expected) { }
        assertEquals(true, p.value); assertEquals("test-1", store.owner);
        phase("APPLYING", new RemoteProductRepair(p, store).query("test-1"));
        phase("ROLLED_BACK", new RemoteProductRepair(p, store).recover("test-1"));
        assertEquals(false, p.value); assertEquals(2, p.writes);
    }
    @Test public void crashBeforeWriteDoesNotPerformNewWrite() throws Exception {
        for (String stage : new String[]{"PREPARED", "APPLYING"}) {
            Memory store = new Memory(); Platform p = new Platform(); store.crashAt = stage;
            try { new RemoteProductRepair(p, store).apply(plan()); fail(); } catch (Crash expected) { }
            phase(stage.equals("PREPARED") ? "REJECTED" : "ROLLED_BACK",
                    new RemoteProductRepair(p, store).recover("test-1"));
            assertEquals(0, p.writes);
        }
    }
    @Test public void crashAfterTerminalSaveDoesNotRollback() throws Exception {
        Memory store = new Memory(); Platform p = new Platform(); store.crashAt = "SUCCEEDED";
        try { new RemoteProductRepair(p, store).apply(plan()); fail(); } catch (Crash expected) { }
        phase("SUCCEEDED", new RemoteProductRepair(p, store).recover("test-1"));
        assertEquals(true, p.value); assertEquals(1, p.writes); assertNull(store.owner);
    }
    @Test public void recoveryIdentityChangeKeepsReservation() throws Exception {
        Memory store = new Memory(); Platform p = new Platform(); p.crashWrite = true;
        try { new RemoteProductRepair(p, store).apply(plan()); fail(); } catch (Crash expected) { }
        p.version++;
        phase("RECOVERY_REQUIRED", new RemoteProductRepair(p, store).recover("test-1"));
        assertEquals(1, p.writes); assertEquals("test-1", store.owner);
    }
    @Test public void thirdValueIsNeverOverwritten() throws Exception {
        Memory store = new Memory(); Platform p = new Platform(); p.value = 1; p.thirdValue = true;
        JSONObject request = plan().put("item_id", "appop:net.elfradio.d31bootstrap:CAMERA").put("expected", 1).put("target", 0);
        phase("RECOVERY_REQUIRED", new RemoteProductRepair(p, store).apply(request));
        assertEquals(2, p.value); assertEquals(1, p.writes); assertEquals("test-1", store.owner);
    }
    @Test public void forbiddenCatalogAndShellFieldsRejected() throws Exception {
        String[] ids = {"appop:com.starnet.getnumber:SEND_SMS", "package:com.starnet.getnumber:enabled",
                "appop:net.elfradio.d31phone.debug:WRITE_SMS", "appop:net.elfradio.d31bootstrap:FINE_LOCATION", "setting:default_sms_is_quik",
                "permission:net.elfradio.d31bootstrap:android.permission.WRITE_SECURE_SETTINGS", "x; reboot"};
        for (String id : ids) {
            Platform p = new Platform();
            try { new RemoteProductRepair(p, new Memory()).apply(plan().put("item_id", id)); fail(id); }
            catch (IOException expected) { }
            assertEquals(0, p.reads); assertEquals(0, p.writes);
        }
        try { new RemoteProductRepair(new Platform(), new Memory()).apply(plan().put("command", "id")); fail(); }
        catch (IOException expected) { }
    }
    @Test public void typedValuesAreStrict() throws Exception {
        for (Object wrong : new Object[]{"true", 1, JSONObject.NULL}) {
            try { new RemoteProductRepair(new Platform(), new Memory()).apply(plan().put("target", wrong)); fail(); }
            catch (IOException expected) { }
        }
    }
    @Test public void busyReservationPreventsWrite() throws Exception {
        Memory store = new Memory(); store.owner = "other"; store.digest = "other"; Platform p = new Platform();
        try { new RemoteProductRepair(p, store).apply(plan()); fail(); } catch (IOException expected) { }
        assertEquals(0, p.writes); assertEquals("other", store.owner);
    }
    @Test public void digestIgnoresObjectKeyOrder() throws Exception {
        JSONObject a = plan().put("item_id", "component:net.elfradio.d31system:net.elfradio.d31system.SystemReceiver")
                .put("expected", new JSONObject().put("enabled", false).put("setting", 2))
                .put("target", new JSONObject().put("enabled", true).put("setting", 1));
        JSONObject b = new JSONObject(a.toString()).put("target", new JSONObject().put("setting", 1).put("enabled", true));
        assertEquals(RemoteProductRepair.digest(a), RemoteProductRepair.digest(b));
    }
    @Test public void inspectReadsActualPreimageWithoutTouchingStore() throws Exception {
        Platform p = new Platform();
        JSONObject observed = new RemoteProductRepair(p, null).inspect(plan().getString("item_id"));
        assertEquals(false, observed.get("expected")); assertEquals(true, observed.get("target"));
        assertEquals("test-build", observed.getString("build_fingerprint"));
        assertEquals("OBSERVED", observed.getString("state")); assertEquals(0, p.writes);
        JSONObject generated = new JSONObject();
        for (String key : new String[]{"catalog", "item_id", "build_fingerprint", "expected", "target"})
            generated.put(key, observed.get(key));
        generated.put("operation_id", "from-inspect");
        phase("SUCCEEDED", new RemoteProductRepair(p, new Memory()).apply(generated));
    }
    @Test public void inspectRefusesChangingPreimage() throws Exception {
        Platform p = new Platform() {
            @Override public Object read(RemoteProductRepair.Item item) { return ++reads == 1; }
        };
        try { new RemoteProductRepair(p, null).inspect(plan().getString("item_id")); fail(); }
        catch (IOException expected) { }
        assertEquals(0, p.writes);
    }
    @Test public void inspectCatalogPreservesUnreadableItems() throws Exception {
        Platform p = new Platform() {
            @Override public Object read(RemoteProductRepair.Item item) throws Exception {
                if (item.kind.equals("permission")) return false;
                throw new IOException("注入不可读取");
            }
        };
        JSONObject catalog = new RemoteProductRepair(p, null).inspect();
        assertEquals("PARTIAL", catalog.getString("status"));
        assertEquals(RemoteProductRepair.catalog().size(), catalog.getInt("total"));
        assertEquals(12, catalog.getInt("observed")); assertEquals(0, p.writes);
        for (int i = 0; i < catalog.getJSONArray("items").length(); i++) {
            JSONObject row = catalog.getJSONArray("items").getJSONObject(i);
            if (row.getString("state").equals("READ_FAILED")) assertFalse(row.has("expected"));
        }
    }
}
