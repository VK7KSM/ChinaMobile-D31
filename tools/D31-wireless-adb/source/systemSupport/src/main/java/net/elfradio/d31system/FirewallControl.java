package net.elfradio.d31system;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class FirewallControl {
    static final boolean DEFAULT_ENABLED = false;
    private static final String PREFS = "d31_firewall";
    private static final String ENABLED = "enabled";
    private static final String CHAIN4 = "D31_INPUT";
    private static final String CHAIN6 = "D31_INPUT6";
    private static final int MEDIA_PORT_START = 50000;
    private static final int MEDIA_PORT_END = 50101;
    private static final Uri ACCOUNTS = Uri.parse(
            "content://com.starnet.videobox.provider.VsipProvider/account");
    private static final String[] ACCOUNT_COLUMNS = {
            "regserver", "regserverport", "proxy", "proxyport",
            "bakproxy", "bakproxyport", "transport", "active"
    };

    private FirewallControl() {
    }

    static SystemActions.ActionResult enable(Context context) {
        RuleInputs inputs;
        try {
            inputs = collectInputs(context);
        } catch (Throwable error) {
            return failOpenIfPreviouslyEnabled(context,
                    "无法读取安全规则输入：" + error);
        }
        if (inputs.localIpv4.isEmpty()) {
            return failOpenIfPreviouslyEnabled(context,
                    "没有发现可用于管理的直连IPv4网段");
        }
        if (inputs.sipIpv4.isEmpty() && inputs.sipIpv6.isEmpty()) {
            return failOpenIfPreviouslyEnabled(context,
                    "活动SIP账户没有解析出A或AAAA地址");
        }

        SystemActions.ActionResult root = SystemActions.executeRootSequence(
                "应用D31本机入站防火墙", buildApplyCommands(inputs));
        if (root.succeeded) {
            preferences(context).edit().putBoolean(ENABLED, true).apply();
            return new SystemActions.ActionResult(
                    describeInputs(inputs) + root.log, true);
        }

        SystemActions.ActionResult cleanup = SystemActions.executeRootSequence(
                "启用失败，清理未完成规则", clearCommands());
        preferences(context).edit().putBoolean(ENABLED, !cleanup.succeeded).apply();
        return new SystemActions.ActionResult(describeInputs(inputs)
                + root.log + cleanup.log, false);
    }

    static SystemActions.ActionResult disable(Context context) {
        SystemActions.ActionResult root = SystemActions.executeRootSequence(
                "清理D31本机入站防火墙", clearCommands());
        if (root.succeeded) preferences(context).edit().putBoolean(ENABLED, false).apply();
        return root;
    }

    static SystemActions.ActionResult refreshIfEnabled(Context context) {
        if (!isEnabled(context)) {
            return new SystemActions.ActionResult("D31本机入站防火墙未启用。\n", true);
        }
        return enable(context);
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, DEFAULT_ENABLED);
    }

    static SystemActions.ActionResult inspectInputs(Context context) {
        try {
            RuleInputs inputs = collectInputs(context);
            boolean valid = !inputs.localIpv4.isEmpty()
                    && (!inputs.sipIpv4.isEmpty() || !inputs.sipIpv6.isEmpty());
            return new SystemActions.ActionResult(describeInputs(inputs), valid);
        } catch (Throwable error) {
            return new SystemActions.ActionResult(
                    "无法读取安全规则输入：" + error + "\n", false);
        }
    }

    static String status(Context context) {
        SystemActions.ActionResult result = SystemActions.executeRoot(
                "D31本机入站防火墙状态",
                "iptables -S " + CHAIN4 + " 2>/dev/null || true; "
                        + "ip6tables -S " + CHAIN6 + " 2>/dev/null || true");
        return "配置开关=" + (isEnabled(context) ? "已启用" : "未启用") + "\n"
                + result.log;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SystemActions.ActionResult failOpenIfPreviouslyEnabled(
            Context context, String reason) {
        StringBuilder log = new StringBuilder("防火墙规则输入无效：")
                .append(reason).append("。\n");
        if (!isEnabled(context)) {
            log.append("防火墙原本未启用，设备规则保持不变。\n");
            return new SystemActions.ActionResult(log.toString(), false);
        }

        SystemActions.ActionResult clear = SystemActions.executeRootSequence(
                "规则刷新失败，清理旧防火墙以保持网络可用", clearCommands());
        log.append(clear.log);
        if (clear.succeeded) {
            preferences(context).edit().putBoolean(ENABLED, false).apply();
            log.append("旧规则已清理，防火墙开关已关闭。\n");
        } else {
            log.append("旧规则清理失败，必须通过本地界面或ADB人工回滚。\n");
        }
        return new SystemActions.ActionResult(log.toString(), false);
    }

    private static RuleInputs collectInputs(Context context) throws Exception {
        RuleInputs inputs = new RuleInputs();
        collectLocalNetworks(inputs);
        collectSipAddresses(context, inputs);
        return inputs;
    }

    private static void collectLocalNetworks(RuleInputs inputs) throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return;
        for (NetworkInterface network : Collections.list(interfaces)) {
            String name = network.getName();
            if (name == null || !(name.startsWith("eth") || name.startsWith("wlan"))) continue;
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress item : network.getInterfaceAddresses()) {
                InetAddress address = item.getAddress();
                short prefix = item.getNetworkPrefixLength();
                if (address instanceof Inet4Address && prefix >= 0 && prefix <= 32) {
                    inputs.localIpv4.add(networkCidr(address, prefix));
                } else if (address instanceof Inet6Address && prefix > 0 && prefix <= 128) {
                    inputs.localIpv6.add(networkCidr(address, prefix));
                }
            }
        }
    }

    private static void collectSipAddresses(Context context, RuleInputs inputs) throws Exception {
        Set<String> hosts = new LinkedHashSet<>();
        try (Cursor cursor = context.getContentResolver().query(
                ACCOUNTS, ACCOUNT_COLUMNS, "active=?", new String[]{"1"}, null)) {
            if (cursor == null) throw new IllegalStateException("SIP账户Provider返回空Cursor");
            int activeColumn = cursor.getColumnIndex("active");
            while (cursor.moveToNext()) {
                if (activeColumn >= 0 && cursor.getInt(activeColumn) != 1) continue;
                addHost(cursor, "regserver", hosts);
                addHost(cursor, "proxy", hosts);
                addHost(cursor, "bakproxy", hosts);
            }
        }
        for (String host : hosts) {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                String literal = address.getHostAddress();
                int scope = literal.indexOf('%');
                if (scope >= 0) literal = literal.substring(0, scope);
                if (address instanceof Inet4Address) inputs.sipIpv4.add(literal);
                else if (address instanceof Inet6Address) inputs.sipIpv6.add(literal);
            }
        }
    }

    private static void addHost(Cursor cursor, String column, Set<String> hosts) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return;
        String host = normalizeHost(cursor.getString(index));
        if (!host.isEmpty()) hosts.add(host);
    }

    static String normalizeHost(String value) {
        if (value == null) return "";
        String host = value.trim();
        String lower = host.toLowerCase(java.util.Locale.US);
        if (lower.startsWith("sips:")) host = host.substring(5);
        else if (lower.startsWith("sip:")) host = host.substring(4);
        else {
            int separator = host.indexOf("://");
            if (separator > 0) host = host.substring(separator + 3);
        }
        if (host.startsWith("//")) host = host.substring(2);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int semicolon = host.indexOf(';');
        if (semicolon >= 0) host = host.substring(0, semicolon);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close > 1 ? host.substring(1, close) : "";
        }
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        return host.trim();
    }

    static String networkCidr(InetAddress address, int prefix) {
        byte[] bytes = address.getAddress().clone();
        int remaining = prefix;
        for (int i = 0; i < bytes.length; i++) {
            int keep = Math.max(0, Math.min(8, remaining));
            int mask = keep == 0 ? 0 : (0xff << (8 - keep)) & 0xff;
            bytes[i] = (byte) ((bytes[i] & 0xff) & mask);
            remaining -= keep;
        }
        try {
            String literal = InetAddress.getByAddress(bytes).getHostAddress();
            int scope = literal.indexOf('%');
            if (scope >= 0) literal = literal.substring(0, scope);
            return literal + "/" + prefix;
        } catch (Throwable error) {
            throw new IllegalArgumentException("无法生成网段", error);
        }
    }

    static List<String> buildApplyCommands(RuleInputs inputs) {
        List<String> commands = new ArrayList<>(clearCommands());
        commands.add("iptables -N " + CHAIN4);
        commands.add("iptables -A " + CHAIN4 + " -i lo -j RETURN");
        commands.add("iptables -A " + CHAIN4
                + " -m state --state ESTABLISHED,RELATED -j RETURN");
        commands.add("iptables -A " + CHAIN4 + " -p icmp -j RETURN");
        commands.add("iptables -A " + CHAIN4
                + " -p udp --sport 67 --dport 68 -j RETURN");
        appendManagementRules(commands, "iptables", CHAIN4, inputs.localIpv4);
        appendSipAndMediaRules(commands, "iptables", CHAIN4, inputs.sipIpv4);
        commands.add("iptables -A " + CHAIN4 + " -j DROP");

        commands.add("ip6tables -N " + CHAIN6);
        commands.add("ip6tables -A " + CHAIN6 + " -i lo -j RETURN");
        commands.add("ip6tables -A " + CHAIN6
                + " -m state --state ESTABLISHED,RELATED -j RETURN");
        commands.add("ip6tables -A " + CHAIN6 + " -p icmpv6 -j RETURN");
        commands.add("ip6tables -A " + CHAIN6
                + " -p udp --sport 547 --dport 546 -j RETURN");
        commands.add("ip6tables -A " + CHAIN6
                + " -s fe80::/10 -p tcp --dport 5555 -j RETURN");
        commands.add("ip6tables -A " + CHAIN6
                + " -s fe80::/10 -p tcp --dport 8765 -j RETURN");
        appendManagementRules(commands, "ip6tables", CHAIN6, inputs.localIpv6);
        appendSipAndMediaRules(commands, "ip6tables", CHAIN6, inputs.sipIpv6);
        commands.add("ip6tables -A " + CHAIN6 + " -j DROP");

        commands.add("iptables -I INPUT 1 -j " + CHAIN4);
        commands.add("ip6tables -I INPUT 1 -j " + CHAIN6);
        return commands;
    }

    private static void appendManagementRules(
            List<String> commands, String tool, String chain, Set<String> networks) {
        for (String network : networks) {
            commands.add(tool + " -A " + chain + " -s " + network
                    + " -p tcp --dport 5555 -j RETURN");
            commands.add(tool + " -A " + chain + " -s " + network
                    + " -p tcp --dport 8765 -j RETURN");
        }
    }

    private static void appendSipAndMediaRules(
            List<String> commands, String tool, String chain, Set<String> addresses) {
        for (String address : addresses) {
            for (String protocol : new String[]{"udp", "tcp"}) {
                for (int port : new int[]{5065, 5066}) {
                    commands.add(tool + " -A " + chain + " -s " + address
                            + " -p " + protocol + " --dport " + port + " -j RETURN");
                }
            }
            commands.add(tool + " -A " + chain + " -s " + address
                    + " -p udp --dport " + MEDIA_PORT_START + ":" + MEDIA_PORT_END
                    + " -j RETURN");
        }
    }

    private static List<String> clearCommands() {
        List<String> commands = new ArrayList<>();
        commands.add("while iptables -D INPUT -j " + CHAIN4
                + " 2>/dev/null; do :; done; true");
        commands.add("iptables -F " + CHAIN4 + " 2>/dev/null || true");
        commands.add("iptables -X " + CHAIN4 + " 2>/dev/null || true");
        commands.add("while ip6tables -D INPUT -j " + CHAIN6
                + " 2>/dev/null; do :; done; true");
        commands.add("ip6tables -F " + CHAIN6 + " 2>/dev/null || true");
        commands.add("ip6tables -X " + CHAIN6 + " 2>/dev/null || true");
        return commands;
    }

    private static String describeInputs(RuleInputs inputs) {
        return "规则输入：直连IPv4网段=" + inputs.localIpv4.size()
                + "，直连IPv6网段=" + inputs.localIpv6.size()
                + "，SIP IPv4地址=" + inputs.sipIpv4.size()
                + "，SIP IPv6地址=" + inputs.sipIpv6.size() + "\n";
    }

    static final class RuleInputs {
        final Set<String> localIpv4 = new LinkedHashSet<>();
        final Set<String> localIpv6 = new LinkedHashSet<>();
        final Set<String> sipIpv4 = new LinkedHashSet<>();
        final Set<String> sipIpv6 = new LinkedHashSet<>();
    }
}
