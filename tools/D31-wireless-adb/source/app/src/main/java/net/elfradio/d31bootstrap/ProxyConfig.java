package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代理核心配置的生成：服务端下发的文件只取顶层 `proxies:` 一段，其余骨架由本机固定。
 *
 * 这是设备侧的防线，不是格式偏好：下发文件里的 `allow-lan`、`external-controller`、`tun`、`MATCH` 之类
 * 在这里根本不会被读到，配置被换也放不开监听、开不了 TUN、改不了管理连接的路由。
 * 第 2 步只开本机回环两个监听，不开 TUN；分流名单到第 3 步再进骨架。
 */
final class ProxyConfig {
    static final int HTTP_PORT = 17890, SOCKS_PORT = 17891;
    static final int MAX_PROXIES = 64, MAX_NAME = 64;
    static final String GROUP = "PROXY";
    /** 解析出来的节点段与节点名；`block` 是原文行（含缩进），原样拼进生成的配置。 */
    static final class Proxies {
        final String block; final List<String> names;
        Proxies(String block, List<String> names) { this.block = block; this.names = names; }
    }
    private static final Pattern NAME = Pattern.compile("^\\s*-\\s*name\\s*:\\s*(.*?)\\s*$");
    /** 节点条目里这几个键会把出口绑到别的接口或再套一层代理，改变本机路由语义，不接受。 */
    private static final Pattern FORBIDDEN = Pattern.compile("^\\s*(interface-name|routing-mark|dialer-proxy)\\s*:");

    /** 取顶层 `proxies:` 块：从该行起，到下一行「非空、非注释、无缩进」为止。 */
    static Proxies extract(String text) throws IOException {
        if (text == null || text.indexOf('\0') >= 0 || text.indexOf('\t') >= 0) throw new IOException("PROXY_CONFIG_TEXT_INVALID");
        String[] lines = text.split("\\r?\\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("proxies\\s*:.*")) {
                if (start >= 0) throw new IOException("PROXY_CONFIG_PROXIES_DUPLICATE");
                start = i;
            }
        }
        if (start < 0) throw new IOException("PROXY_CONFIG_PROXIES_MISSING");
        StringBuilder block = new StringBuilder();
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !Character.isWhitespace(line.charAt(0))) break;
            if (FORBIDDEN.matcher(line).find()) throw new IOException("PROXY_CONFIG_KEY_FORBIDDEN");
            Matcher m = NAME.matcher(line);
            if (m.matches()) {
                String name = unquote(m.group(1));
                if (name.isEmpty() || name.length() > MAX_NAME || !seen.add(name)) throw new IOException("PROXY_CONFIG_NAME_INVALID");
                names.add(name);
            }
            block.append(line).append('\n');
        }
        if (names.isEmpty()) throw new IOException("PROXY_CONFIG_PROXIES_EMPTY");
        if (names.size() > MAX_PROXIES) throw new IOException("PROXY_CONFIG_PROXIES_TOO_MANY");
        return new Proxies(block.toString(), names);
    }

    /** 固定骨架 + 节点段。测速间隔 10 分钟：订阅里 60 秒全节点测速在座机上是无谓的后台流量。 */
    static String render(Proxies proxies) {
        StringBuilder out = new StringBuilder();
        out.append("# 由 elfRemote 核心生成；手工修改会在下一次配置任务时被覆盖\n")
           .append("port: ").append(HTTP_PORT).append('\n')
           .append("socks-port: ").append(SOCKS_PORT).append('\n')
           .append("mixed-port: 0\nredir-port: 0\ntproxy-port: 0\n")
           .append("bind-address: 127.0.0.1\nallow-lan: false\n")
           .append("mode: rule\nlog-level: warning\nipv6: false\n")
           .append("proxies:\n").append(proxies.block);
        if (!proxies.block.endsWith("\n")) out.append('\n');
        out.append("proxy-groups:\n  - name: ").append(GROUP).append("\n    type: url-test\n")
           .append("    url: http://cp.cloudflare.com/generate_204\n    interval: 600\n    tolerance: 50\n    proxies:\n");
        for (String name : proxies.names) out.append("      - ").append(quote(name)).append('\n');
        out.append("rules:\n")
           .append("  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n")
           .append("  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n")
           .append("  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n")
           .append("  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n")
           .append("  - MATCH,").append(GROUP).append('\n');
        return out.toString();
    }

    static String quote(String name) {
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 取出名字：带引号的取到配对的闭引号为止（闭引号之后的注释丢掉），
     * 双引号内处理 `\"` 与 `\\`，单引号内只把 `''` 还原成 `'`；不带引号的去掉行尾注释。
     */
    static String unquote(String value) {
        String v = value == null ? "" : value.trim();
        if (v.startsWith("'")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\'') {
                    if (i + 1 < v.length() && v.charAt(i + 1) == '\'') { out.append('\''); i++; }
                    else return out.toString();
                } else out.append(c);
            }
            return v;
        }
        if (v.startsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length()) { out.append(v.charAt(++i)); }
                else if (c == '"') return out.toString();
                else out.append(c);
            }
            return v;
        }
        int hash = v.indexOf(" #");
        return hash > 0 ? v.substring(0, hash).trim() : v;
    }

    private ProxyConfig() {}
}
