package net.elfradio.d31bootstrap.management;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkProcessTest {
    static class Blocking extends Process {
        final AtomicBoolean closed = new AtomicBoolean(); boolean destroyed;
        InputStream input = new InputStream() {
            public int read() throws IOException { while (!closed.get()) { try { Thread.sleep(5); } catch(InterruptedException e) { throw new IOException(e); } } return -1; }
            public void close() { closed.set(true); }
        };
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return 0; }
        public int exitValue() { if(!destroyed)throw new IllegalThreadStateException(); return 1; }
        public void destroy() { destroyed=true; }
    }
    @Test(timeout=3000) public void hungCallTimesOutAndClosesInsteadOfBlockingJournalForever() throws Exception {
        Blocking process=new Blocking();
        try { NetworkProcess.collect(process,100); fail(); } catch(IOException expected) { }
        assertTrue(process.destroyed); assertTrue(process.closed.get());
    }
    @Test(timeout=3000) public void interruptionClosesAndRestoresInterruptFlag() throws Exception {
        Blocking process=new Blocking(); Thread.currentThread().interrupt();
        try { NetworkProcess.collect(process,1000); fail(); } catch(InterruptedException expected) {
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(process.destroyed); assertTrue(process.closed.get());
        } finally { Thread.interrupted(); }
    }
}
