package net.elfradio.d31bootstrap;
import org.junit.Test;
import org.json.JSONObject;
import java.io.StringReader;
import static org.junit.Assert.*;

public class RemoteAdbMaintenanceTest {
    @Test public void existingClientOnlyMatchesLocalEstablishedPort()throws Exception {
        assertTrue(TcpListenerState.containsState(new StringReader("0: 0100007F:1616 0100007F:A123 01 0\n"),5654,"01"));
        for(String row:new String[]{"0: 0100007F:A123 0100007F:1616 01 0\n","0: 00000000:1616 0 0A 0\n","0: 0100007F:1616 0 08 0\n"})
            assertFalse(TcpListenerState.containsState(new StringReader(row),5654,"01"));
    }
    @Test public void restartDoesNotChangePortUsbOrFirewall()throws Exception {
        JSONObject task=new JSONObject().put("id","restart-fixture").put("type","restart_adbd").put("expires_at",999999).put("params",new JSONObject());
        String command=RemoteProtocol.commandRequest("fixture",task,1,"unused").getString("command");
        assertTrue(command.startsWith("stop adbd && start adbd"));
        for(String forbidden:new String[]{"setprop","iptables","usb","reboot","killall"})assertFalse(command.contains(forbidden));
        assertFalse(RemoteAdbMaintenance.START_IF_STOPPED.contains("stop adbd"));
        assertTrue(RemoteAdbMaintenance.START_IF_STOPPED.contains(" = stopped ]"));
    }
}
