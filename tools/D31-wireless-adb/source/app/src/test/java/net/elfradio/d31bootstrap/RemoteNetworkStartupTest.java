package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteNetworkStartupTest {
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final class Fixture implements RemoteNetworkStartup.Access {
        long now;
        JSONObject record;
        int calls;
        boolean fail, attention, busy;
        String task, hash;
        public long elapsed() { return now; }
        public JSONObject reservation() throws Exception { if (busy) throw new IOException("busy"); return record; }
        public boolean resume(String task, String hash) throws Exception {
            calls++; this.task = task; this.hash = hash;
            if (fail) throw new IOException("fixture");
            return !attention;
        }
    }
    @Test public void missingAndOtherReservationsNeverLaunch() throws Exception {
        Fixture f = new Fixture(); RemoteNetworkStartup startup = new RemoteNetworkStartup(f);
        startup.tick(); f.record = new JSONObject().put("kind", "app_operation"); f.now = 10000; startup.tick();
        assertEquals(0, f.calls);
    }
    @Test public void dispatchUsesOriginalReservationAndBoundsRetries() throws Exception {
        Fixture f = new Fixture(); f.record = RemoteNetworkAccess.reservation("original", HASH);
        RemoteNetworkStartup startup = new RemoteNetworkStartup(f);
        startup.tick(); assertEquals("original", f.task); assertEquals(HASH, f.hash);
        for (int i = 1; i < 10; i++) { f.now = i * 1000; startup.tick(); }
        assertEquals(1, f.calls); f.now = 10000; startup.tick(); assertEquals(2, f.calls);
    }
    @Test public void failureAlsoBacksOffAndClockRollbackCannotBlockForever() throws Exception {
        Fixture f = new Fixture(); f.record = RemoteNetworkAccess.reservation("original", HASH); f.fail = true;
        RemoteNetworkStartup startup = new RemoteNetworkStartup(f);
        f.now = 10000; try { startup.tick(); fail(); } catch (IOException expected) { }
        f.now = 10001; startup.tick(); assertEquals(1, f.calls);
        f.fail = false; f.now = 5000; startup.tick(); assertEquals(2, f.calls);
    }
    @Test public void malformedNetworkOwnerCannotDispatch() throws Exception {
        Fixture f = new Fixture(); f.record = RemoteNetworkAccess.reservation("original", HASH).put("task_id", "network-other");
        try { new RemoteNetworkStartup(f).tick(); fail(); } catch (IOException expected) { }
        assertEquals(0, f.calls);
    }
    @Test public void attentionDoesNotRelaunchButNewReservationCanProceed() throws Exception {
        Fixture f = new Fixture(); f.record = RemoteNetworkAccess.reservation("original", HASH); f.attention = true;
        RemoteNetworkStartup startup = new RemoteNetworkStartup(f); startup.tick();
        f.now = 10000; startup.tick(); assertEquals(1, f.calls);
        f.record = RemoteNetworkAccess.reservation("next", HASH); f.now = 20000; startup.tick(); assertEquals(2, f.calls);
    }
    @Test public void busyObservationCannotClearAttention() throws Exception {
        Fixture f = new Fixture(); f.record = RemoteNetworkAccess.reservation("original", HASH); f.attention = true;
        RemoteNetworkStartup startup = new RemoteNetworkStartup(f); startup.tick();
        f.busy = true; f.now = 10000; try { startup.tick(); fail(); } catch (IOException expected) { }
        f.busy = false; f.now = 20000; startup.tick(); assertEquals(1, f.calls);
    }
}
