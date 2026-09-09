package net.elfradio.d31system;

import org.junit.Test;
import static org.junit.Assert.*;

public class CellularDisplayTest {
    @Test public void numberAndCarrierFallback() {
        assertEquals("12345", CellularDisplay.displayName(" 12345 ", "Network", "SIM"));
        assertEquals("Network", CellularDisplay.displayName(null, "Network", "SIM"));
        assertEquals("SIM", CellularDisplay.displayName("", " ", "SIM"));
        assertEquals("移动网络", CellularDisplay.displayName(null, null, null));
    }
    @Test public void registeredTechnologyAndUnavailableService() {
        assertEquals("4G LTE", CellularDisplay.networkLabel(13, 0));
        assertEquals("4G LTE", CellularDisplay.networkLabel(19, 0));
        assertEquals("5G", CellularDisplay.networkLabel(20, 0));
        assertEquals("3G", CellularDisplay.networkLabel(15, 0));
        assertEquals("2G", CellularDisplay.networkLabel(2, 0));
        assertEquals("未注册网络", CellularDisplay.networkLabel(13, 1));
        assertEquals("仅限紧急呼叫", CellularDisplay.networkLabel(13, 2));
        assertEquals("移动网络已关闭", CellularDisplay.networkLabel(13, 3));
        assertEquals("等待网络", CellularDisplay.networkLabel(0, -1));
    }
}
