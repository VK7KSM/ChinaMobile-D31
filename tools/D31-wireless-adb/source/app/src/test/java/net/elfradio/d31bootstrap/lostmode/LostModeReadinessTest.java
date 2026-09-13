package net.elfradio.d31bootstrap.lostmode;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class LostModeReadinessTest {
    @Test public void systemLockDoesNotClaimLostModeSupport()throws Exception{
        JSONObject value=LostModeReadiness.snapshot(23,new LostModeReadiness.Locks(){public boolean locked(){return true;}public boolean secure(){return true;}});
        assertEquals("NOT_READY",value.getString("state"));assertFalse(value.getBoolean("managed_lost_v2"));assertFalse(value.getBoolean("managed_lost_tasks"));
        assertTrue(value.getJSONObject("system_lock").getBoolean("locked"));assertFalse(value.has("enabled"));assertFalse(value.has("auto_wipe_enabled"));
    }
    @Test public void failedReadNeverInventsUnlockedState()throws Exception{
        JSONObject value=LostModeReadiness.snapshot(23,new LostModeReadiness.Locks(){public boolean locked(){throw new SecurityException();}public boolean secure(){return false;}});
        assertEquals("READ_FAILED",value.getJSONObject("system_lock").getString("state"));assertFalse(value.getJSONObject("system_lock").has("locked"));
    }
    @Test public void otherApiDoesNotBorrowD22Readiness()throws Exception{
        JSONObject value=LostModeReadiness.snapshot(27,null);assertFalse(value.getBoolean("wipe_supported"));assertFalse(value.getBoolean("lock_write_supported"));
    }
}
