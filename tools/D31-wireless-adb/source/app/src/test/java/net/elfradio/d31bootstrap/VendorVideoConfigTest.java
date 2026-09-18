package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VendorVideoConfigTest {
    @Test public void enabledValueNeedsNoWrite() throws Exception {
        assertNull(VendorVideoConfig.desired("SipInfo", "SIP FIR", "{\"enable\":true,\"title\":\"SIP FIR\",\"type\":0}"));
    }
    @Test public void disabledValueKeepsTitleAndTypeAndEnables() throws Exception {
        JSONObject next = new JSONObject(VendorVideoConfig.desired("ForceFir", "x", "{\"enable\":false,\"title\":\"强制FIR\",\"type\":0}"));
        assertTrue(next.getBoolean("enable"));
        assertEquals("强制FIR", next.getString("title"));
        assertEquals(0, next.getInt("type"));
    }
    @Test public void missingValueIsCreatedWithDefaultTitle() throws Exception {
        JSONObject next = new JSONObject(VendorVideoConfig.desired("SipInfo", "SIP FIR", "null"));
        assertTrue(next.getBoolean("enable"));
        assertEquals("SIP FIR", next.getString("title"));
        assertEquals(0, next.getInt("type"));
        assertNotNull(VendorVideoConfig.desired("SipInfo", "SIP FIR", null));
    }
    @Test public void requiredKeysAreExactlyTheTwoSwitches() {
        assertArrayEquals(new String[]{"SipInfo", "ForceFir"}, VendorVideoConfig.REQUIRED);
    }
}
