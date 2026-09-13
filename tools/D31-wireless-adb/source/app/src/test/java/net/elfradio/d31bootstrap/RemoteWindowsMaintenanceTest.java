package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteWindowsMaintenanceTest {
    @Test public void reservationExpiresOnlyOnDifferentBoot() throws Exception {
        String first = "11111111-1111-1111-1111-111111111111";
        JSONObject record = new JSONObject().put("boot", first).put("id", "11111111111111111111111111111111");
        assertTrue(RemoteWindowsMaintenance.sameBoot(record, first));
        assertFalse(RemoteWindowsMaintenance.sameBoot(record, "22222222-2222-2222-2222-222222222222"));
    }
    @Test public void malformedReservationCannotBeAssumedExpired() throws Exception {
        try { RemoteWindowsMaintenance.sameBoot(new JSONObject().put("boot", "bad").put("id", "bad"), "new"); fail(); }
        catch (java.io.IOException expected) { }
    }
}
