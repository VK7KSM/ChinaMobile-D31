package net.elfradio.d31bootstrap;

import java.io.StringReader;
import org.junit.Test;
import static org.junit.Assert.*;

public class TcpListenerStateTest {
    private boolean check(String data, int port) throws Exception {
        return TcpListenerState.containsListener(new StringReader(data), port);
    }

    @Test public void recognizesIpv4AndIpv6Listeners() throws Exception {
        assertTrue(check(" 0: 00000000:15B3 00000000:0000 0A 0\n", 5555));
        assertTrue(check(" 1: 00000000000000000000000000000000:223D 00000000000000000000000000000000:0000 0A 0\n", 8765));
    }

    @Test public void rejectsEstablishedAndCloseWaitConnections() throws Exception {
        assertFalse(check("0: 0100007F:15B3 0100007F:C123 08 0\n1: 0100007F:15B3 0100007F:C124 01 0\n", 5555));
    }

    @Test public void doesNotMatchRemotePortOrAnotherListener() throws Exception {
        assertFalse(check("0: 00000000:223D 00000000:15B3 0A 0\n", 5555));
    }

    @Test public void handlesHeadersMalformedRowsAndInvalidPorts() throws Exception {
        assertFalse(check("sl local_address rem_address st\n0: bad 0 0A\n1: 00000000:ZZZZ 0 0A\n", 5555));
        assertFalse(check("0: 00000000:0000 0 0A\n", 0));
        assertFalse(check("0: 00000000:FFFF 0 0A\n", 65536));
    }
}
