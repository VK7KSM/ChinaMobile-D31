package net.elfradio.d31bootstrap;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RootTransportTest {
    @Test public void unavailableBeforeSubmissionFallsBack() throws Exception {
        AtomicInteger called = new AtomicInteger();
        RootTransport.Result result = RootTransport.choose("true", 1000,
                (c,t) -> { throw new RootTransport.Unavailable(new IOException("未连接")); },
                (c,t) -> { called.incrementAndGet(); return new RootTransport.Result(0, "ok"); });
        assertEquals(0, result.exit); assertEquals(1, called.get());
    }
    @Test public void uncertainSubmissionNeverFallsBack() {
        AtomicInteger called = new AtomicInteger();
        assertThrows(IOException.class, () -> RootTransport.choose("true", 1000,
                (c,t) -> { throw new IOException("发送后回执丢失"); },
                (c,t) -> { called.incrementAndGet(); return new RootTransport.Result(0, "ok"); }));
        assertEquals(0, called.get());
    }
    @Test public void commandFailureNeverFallsBack() throws Exception {
        AtomicInteger called = new AtomicInteger();
        RootTransport.Result result = RootTransport.choose("false", 1000,
                (c,t) -> new RootTransport.Result(1, "失败"),
                (c,t) -> { called.incrementAndGet(); return new RootTransport.Result(0, "ok"); });
        assertEquals(1, result.exit); assertEquals(0, called.get());
    }
}
