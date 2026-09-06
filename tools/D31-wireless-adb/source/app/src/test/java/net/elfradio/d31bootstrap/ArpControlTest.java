package net.elfradio.d31bootstrap;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ArpControlTest {
    @Test
    public void writesOnlyApprovedArpPathsAndValues() {
        String[][] expected = ArpControl.expectedSettings();
        List<String> commands = ArpControl.buildApplyCommands();

        assertEquals(8, expected.length);
        assertEquals(expected.length, commands.size());

        Map<String, String> approved = new LinkedHashMap<>();
        for (String[] setting : expected) approved.put(setting[0], setting[1]);
        assertEquals(8, approved.size());

        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            assertEquals("echo " + expected[i][1] + " > " + expected[i][0], command);
            assertTrue(expected[i][0].startsWith("/proc/sys/net/ipv4/conf/"));
            assertTrue("root命令过长：" + command.length(), command.length() < 256);
        }
    }

    @Test
    public void doesNotChangeRoutesInterfacesFirewallAdbOrSip() {
        String commands = String.join("\n", ArpControl.buildApplyCommands()).toLowerCase();
        for (String forbidden : new String[]{
                "ip route", "ip link", "ifconfig", "route ", "rp_filter",
                "iptables", "ip6tables", "adb", "setprop", "settings ",
                "sip", "5060", "5065", "5066"
        }) {
            assertFalse("出现禁止操作：" + forbidden, commands.contains(forbidden));
        }
        assertTrue(commands.contains("all/arp_ignore"));
        assertTrue(commands.contains("wlan0/arp_announce"));
    }
}
