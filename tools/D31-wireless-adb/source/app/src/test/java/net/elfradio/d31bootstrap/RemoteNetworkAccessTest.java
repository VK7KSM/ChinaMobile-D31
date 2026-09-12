package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteNetworkAccessTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    @Test public void validatesOnlyExplicitLocalOperations() throws Exception {
        RemoteNetworkAccess.validate(new String[]{"prepare", HASH});
        RemoteNetworkAccess.validate(new String[]{"query", "case-1", HASH});
        RemoteNetworkAccess.validate(new String[]{"resume", "case-1", HASH});
        RemoteNetworkAccess.validate(new String[]{"cancel", "case-1", HASH});
        RemoteNetworkAccess.validate(new String[]{"begin", "case-1", HASH, "false", "10000"});
        RemoteNetworkAccess.validate(new String[]{"begin", "case-1", HASH, "true", "120000"});
        RemoteNetworkAccess.validate(new String[]{"guard", "case-1", HASH, "0123456789abcdef0123456789abcdef",
                "12345678-1234-1234-1234-123456789abc", "50000"});
        for (String[] args : new String[][] {null, {}, {"prepare"}, {"begin", "case-1", HASH, "true", "9999"},
                {"begin", "case-1", HASH, "true", "120001"}, {"begin", "case-1", HASH, "1", "10000"},
                {"begin", "case-1", HASH, "true", "010000"}, {"query", "../other", HASH},
                {"query", "case-1", HASH.toUpperCase(java.util.Locale.ROOT)}, {"confirm", "case-1", HASH},
                {"set", "case-1", HASH}, {"guard", "case-1", HASH, "bad", "bad", "50000"}}) {
            try { RemoteNetworkAccess.validate(args); fail("未拒绝无效或未开放操作"); } catch (IOException expected) { }
        }
    }
    @Test public void reservationRemainsVisibleToExistingSupervisor() throws Exception {
        JSONObject record = RemoteNetworkAccess.reservation("case-1", HASH);
        assertEquals("network-case-1", record.getString("task_id"));
        assertEquals(HASH, record.getString("plan_sha256"));
        RemoteNetworkAccess.requireOwner(record, "case-1", HASH);
    }
    @Test public void neverTakesAnotherRepairOrNetworkReservation() throws Exception {
        for (String key : new String[]{"kind", "network_task", "task_id", "plan_sha256", "schema_version"}) {
            JSONObject record = RemoteNetworkAccess.reservation("case-1", HASH);
            record.remove(key);
            try { RemoteNetworkAccess.requireOwner(record, "case-1", HASH); fail("接受不完整预留"); }
            catch (IOException expected) { }
        }
        for (JSONObject record : new JSONObject[]{
                RemoteNetworkAccess.reservation("case-2", HASH),
                RemoteNetworkAccess.reservation("case-1", "a" + HASH.substring(1)),
                RemoteNetworkAccess.reservation("case-1", HASH).put("kind", "repair")}) {
            try { RemoteNetworkAccess.requireOwner(record, "case-1", HASH); fail("接管了另一预留"); }
            catch (IOException expected) { }
        }
    }
}
