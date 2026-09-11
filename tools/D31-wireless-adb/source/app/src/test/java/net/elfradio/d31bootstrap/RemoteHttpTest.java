package net.elfradio.d31bootstrap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteHttpTest {
    @Test public void retryAfterAcceptsSecondsAndHttpDateWithBounds() {
        assertEquals(900000, RemoteHttp.retryAfterDelay("900", 0));
        assertEquals(0, RemoteHttp.retryAfterDelay(null, 0));
        assertEquals(0, RemoteHttp.retryAfterDelay("-1", 0));
        assertEquals(0, RemoteHttp.retryAfterDelay("invalid", 0));
        assertEquals(0, RemoteHttp.retryAfterDelay("0", 0));
        assertEquals(86400000, RemoteHttp.retryAfterDelay("999999999999999999999999", 0));
        assertEquals(900000, RemoteHttp.retryAfterDelay("Thu, 01 Jan 1970 00:15:00 GMT", 0));
        assertEquals(0, RemoteHttp.retryAfterDelay("Thu, 01 Jan 1970 00:15:00 GMT", 900001));
        assertEquals(0, RemoteHttp.retryAfterDelay("Thu, 01 Jan 1970 00:15:00 GMT garbage", 0));
    }
    @Test public void actualHttpResponsePreservesRetryHeaderEvenWithInvalidErrorBody() throws Exception {
        final boolean[] disconnected = {false};
        HttpURLConnection connection = new HttpURLConnection(new URL("https://example.invalid/")) {
            public void connect() { throw new AssertionError("不得联网"); }
            public boolean usingProxy() { return false; }
            public void disconnect() { disconnected[0] = true; }
            public int getResponseCode() { return 503; }
            public String getHeaderField(String key) { return "Retry-After".equals(key) ? "900" : null; }
            public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[]{'x'}); }
        };
        try { RemoteHttp.request(connection, null, false); fail(); }
        catch (RemoteHttp.Rejected error) { assertEquals(503,error.status); assertEquals(900000,error.retryAfterMillis); }
        assertTrue(disconnected[0]);
    }
}
