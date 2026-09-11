package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteRepairRequestTest {
    private JSONObject query() throws Exception { return new JSONObject().put("operation", "query")
            .put("task_id", "repair-1").put("plan_sha256", RemoteProtocol.hash("plan")); }
    @Test public void queryRequiresNoDeviceOrNetwork() throws Exception {
        RemoteRepairRequest r = new RemoteRepairRequest(query());
        assertEquals("query", r.operation); assertNull(r.plan); r.matches(query());
    }
    @Test public void cannotInjectPathOrCommands() throws Exception {
        for (String field : new String[]{"path", "root", "command", "mapping", "plan"}) {
            try { new RemoteRepairRequest(query().put(field, "anything")); fail(field); }
            catch (IOException expected) { }
        }
    }
    @Test public void taskTraversalAndDigestMismatchRefused() throws Exception {
        try { new RemoteRepairRequest(query().put("task_id", "../other")); fail(); } catch (IOException expected) { }
        RemoteRepairRequest r = new RemoteRepairRequest(query());
        try { r.matches(query().put("plan_sha256", RemoteProtocol.hash("other"))); fail(); } catch (IOException expected) { }
    }
    @Test public void arbitraryOperationRefused() throws Exception {
        try { new RemoteRepairRequest(query().put("operation", "reboot")); fail(); } catch (IOException expected) { }
    }
}
