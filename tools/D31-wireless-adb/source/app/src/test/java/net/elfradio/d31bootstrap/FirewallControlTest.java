package net.elfradio.d31bootstrap;

import org.junit.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FirewallControlTest {
    @Test public void managementRulesFollowCurrentAdbPortOnBothFamilies() {
        FirewallControl.RuleInputs inputs = new FirewallControl.RuleInputs();
        inputs.adbPort = 5654;
        inputs.localIpv4.add("192.0.2.0/24");
        inputs.localIpv6.add("2001:db8::/64");
        String command = String.join("\n", FirewallControl.buildApplyCommands(inputs));
        assertTrue(command.contains("-s 192.0.2.0/24 -p tcp --dport 5654 -j RETURN"));
        assertTrue(command.contains("-s 2001:db8::/64 -p tcp --dport 5654 -j RETURN"));
        assertTrue(command.contains("-s fe80::/10 -p tcp --dport 5654 -j RETURN"));
        assertFalse(command.contains("--dport 5555"));
    }
    @Test
    public void freshInstallDefaultsFirewallToEnabled() {
        assertTrue(FirewallControl.DEFAULT_ENABLED);
    }

    @Test
    public void normalizesSipServerFormsWithoutKeepingCredentialsOrPorts() {
        assertEquals("pbx.example.net",
                FirewallControl.normalizeHost("sip:user@pbx.example.net:5061;transport=tls"));
        assertEquals("2001:db8::12",
                FirewallControl.normalizeHost("sip:[2001:db8::12]:5061"));
        assertEquals("pbx.example.net",
                FirewallControl.normalizeHost("pbx.example.net:5060"));
        assertEquals("pbx.example.net",
                FirewallControl.normalizeHost("sips://user@pbx.example.net:5061"));
    }

    @Test
    public void calculatesIpv4ConnectedNetwork() throws Exception {
        assertEquals("192.168.2.0/24",
                FirewallControl.networkCidr(InetAddress.getByName("192.168.2.62"), 24));
    }

    @Test
    public void calculatesIpv6ConnectedNetwork() throws Exception {
        assertEquals("2001:db8:12:34:0:0:0:0/64",
                FirewallControl.networkCidr(
                        InetAddress.getByName("2001:db8:12:34:5678::9"), 64));
    }

    @Test
    public void generatedRulesLimitInputWithoutRestrictingOutput() {
        FirewallControl.RuleInputs inputs = new FirewallControl.RuleInputs();
        inputs.localIpv4.add("192.168.2.0/24");
        inputs.localIpv6.add("2001:db8:12:34:0:0:0:0/64");
        inputs.sipIpv4.add("203.0.113.15");
        inputs.sipIpv6.add("2001:db8::15");

        List<String> commands = FirewallControl.buildApplyCommands(inputs);
        String command = String.join("\n", commands);

        assertTrue(command.contains(
                "-s 192.168.2.0/24 -p tcp --dport 5555 -j RETURN"));
        assertTrue(command.contains(
                "-s 192.168.2.0/24 -p tcp --dport 8765 -j RETURN"));
        assertTrue(command.contains(
                "-s 203.0.113.15 -p udp --dport 5065 -j RETURN"));
        assertTrue(command.contains(
                "-s 2001:db8::15 -p tcp --dport 5066 -j RETURN"));
        assertTrue(command.contains(
                "iptables -A D31_INPUT -s 203.0.113.15 -p udp "
                        + "--dport 50000:50101 -j RETURN"));
        assertTrue(command.contains(
                "ip6tables -A D31_INPUT6 -s 2001:db8::15 -p udp "
                        + "--dport 50000:50101 -j RETURN"));
        assertFalse(command.contains(
                "-s 192.168.2.0/24 -p udp --dport 50000:50101"));
        assertTrue(command.contains("iptables -A D31_INPUT -j DROP"));
        assertTrue(command.contains("ip6tables -A D31_INPUT6 -j DROP"));
        assertTrue(command.contains("iptables -I INPUT 1 -j D31_INPUT"));
        assertTrue(command.contains("ip6tables -I INPUT 1 -j D31_INPUT6"));
        assertFalse(command.contains("-I OUTPUT"));
        assertFalse(command.contains("-A OUTPUT"));
        for (String step : commands) {
            assertTrue("root命令过长：" + step.length(), step.length() < 256);
        }
        assertTrue(command.indexOf("iptables -A D31_INPUT -j DROP")
                < command.indexOf("iptables -I INPUT 1 -j D31_INPUT"));
        assertTrue(command.indexOf("ip6tables -A D31_INPUT6 -j DROP")
                < command.indexOf("ip6tables -I INPUT 1 -j D31_INPUT6"));
    }
}
