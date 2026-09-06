package net.elfradio.d31bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BootReceiverTest {
    @Test
    public void storageMountIsDeferredDuringBootStabilization() {
        assertEquals(180, BootReceiver.earlyUsbDelaySeconds(0));
        assertEquals(1, BootReceiver.earlyUsbDelaySeconds(179_001L));
        assertEquals(0, BootReceiver.earlyUsbDelaySeconds(180_000L));
        assertEquals(0, BootReceiver.earlyUsbDelaySeconds(900_000L));
    }

    @Test
    public void storageRetriesUseLongBackoff() {
        assertEquals(60, BootReceiver.usbRetryDelaySeconds(1));
        assertEquals(180, BootReceiver.usbRetryDelaySeconds(2));
    }
}
