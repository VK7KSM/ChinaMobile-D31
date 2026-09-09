package net.elfradio.d31bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import android.content.Intent;

public final class BootReceiverTest {
    @Test
    public void onlyStorageEventsGoToStorageWorker() {
        assertTrue(BootReceiver.isStorageAction(Intent.ACTION_MEDIA_MOUNTED));
        assertTrue(BootReceiver.isStorageAction(Intent.ACTION_MEDIA_REMOVED));
        assertTrue(BootReceiver.isStorageAction("net.elfradio.d31bootstrap.READY_USB"));
        assertFalse(BootReceiver.isStorageAction("android.net.conn.CONNECTIVITY_CHANGE"));
        assertFalse(BootReceiver.isStorageAction(null));
    }
    @Test
    public void storageRetriesAreShortAndBounded() {
        assertEquals(3, BootReceiver.usbRetryDelaySeconds(1));
        assertEquals(10, BootReceiver.usbRetryDelaySeconds(2));
    }
}
