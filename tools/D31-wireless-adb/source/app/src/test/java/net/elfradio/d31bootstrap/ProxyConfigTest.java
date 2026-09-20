package net.elfradio.d31bootstrap;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 配置生成是设备侧的防线：下发文件里除节点段之外的一切都不能进到核心眼前。
 * 用 s.elfradio.net 真实订阅的形状做样本——它开着 allow-lan、全局 TUN 和 MATCH→PROXY。
 */
public class ProxyConfigTest {
    private static final String SUBSCRIPTION = ""
            + "# D31 FreePBX 代理订阅\n"
            + "mixed-port: 7890\n"
            + "allow-lan: true\n"
            + "mode: rule\n"
            + "tun:\n"
            + "  enable: true\n"
            + "  stack: gvisor\n"
            + "  auto-route: true\n"
            + "\n"
            + "proxies:\n"
            + "  - name: \"Oracle1\"\n"
            + "    type: vless\n"
            + "    server: 172.64.32.1\n"
            + "    port: 443\n"
            + "    uuid: 00000000-0000-0000-0000-000000000000\n"
            + "    network: ws\n"
            + "    tls: true\n"
            + "    ws-opts:\n"
            + "      path: \"/stream-proxy\"\n"
            + "      headers:\n"
            + "        Host: \"o1.example\"\n"
            + "\n"
            + "  - name: 'Oracle 3'   # 带空格与注释\n"
            + "    type: vless\n"
            + "    server: 172.64.32.1\n"
            + "    port: 443\n"
            + "\n"
            + "proxy-groups:\n"
            + "  - name: \"PROXY-MODE\"\n"
            + "    type: select\n"
            + "rules:\n"
            + "  - MATCH,PROXY-MODE\n";

    @Test public void onlyTheProxiesBlockSurvives() throws Exception {
        ProxyConfig.Proxies proxies = ProxyConfig.extract(SUBSCRIPTION);
        assertEquals(java.util.Arrays.asList("Oracle1", "Oracle 3"), proxies.names);
        assertTrue(proxies.block.contains("    type: vless"));
        assertFalse("节点段之外的键不得进入", proxies.block.contains("allow-lan"));
        assertFalse(proxies.block.contains("MATCH"));
        assertFalse(proxies.block.contains("proxy-groups"));

        String rendered = ProxyConfig.render(proxies);
        assertTrue(rendered.contains("\nport: 17890\n"));
        assertTrue(rendered.contains("\nsocks-port: 17891\n"));
        assertTrue(rendered.contains("\nbind-address: 127.0.0.1\n"));
        assertTrue(rendered.contains("\nallow-lan: false\n"));
        assertFalse("第 2 步不开 TUN", rendered.contains("\ntun:"));
        assertFalse(rendered.contains("external-controller"));
        assertTrue(rendered.contains("      - \"Oracle1\"\n      - \"Oracle 3\"\n"));
        assertTrue(rendered.contains("interval: 600"));
        assertTrue(rendered.endsWith("  - MATCH,PROXY\n"));
        // 节点段原样保留，包括 ws-opts 这类嵌套。
        assertTrue(rendered.contains("        Host: \"o1.example\"\n"));
    }

    @Test public void filesWithoutUsableProxiesAreRefused() {
        rejects("PROXY_CONFIG_PROXIES_MISSING", "mixed-port: 7890\nrules:\n  - MATCH,DIRECT\n");
        rejects("PROXY_CONFIG_PROXIES_EMPTY", "proxies:\nrules:\n  - MATCH,DIRECT\n");
        rejects("PROXY_CONFIG_PROXIES_EMPTY", "proxies: []\n");
        rejects("PROXY_CONFIG_PROXIES_DUPLICATE", "proxies:\n  - name: a\nproxies:\n  - name: b\n");
        rejects("PROXY_CONFIG_NAME_INVALID", "proxies:\n  - name: a\n  - name: a\n");
        rejects("PROXY_CONFIG_NAME_INVALID", "proxies:\n  - name: \"\"\n");
        rejects("PROXY_CONFIG_TEXT_INVALID", "proxies:\n\t- name: a\n");
        rejects("PROXY_CONFIG_TEXT_INVALID", "proxies:\n  - name: a\0\n");
    }

    @Test public void keysThatRebindTheOutboundAreRefusedInsideProxyEntries() {
        // 这几个键会把出口绑到别的接口或再套一层，改变本机路由语义。
        rejects("PROXY_CONFIG_KEY_FORBIDDEN", "proxies:\n  - name: a\n    interface-name: wlan0\n");
        rejects("PROXY_CONFIG_KEY_FORBIDDEN", "proxies:\n  - name: a\n    routing-mark: 100\n");
        rejects("PROXY_CONFIG_KEY_FORBIDDEN", "proxies:\n  - name: a\n    dialer-proxy: b\n");
    }

    @Test public void tooManyProxiesAreRefused() {
        StringBuilder text = new StringBuilder("proxies:\n");
        for (int i = 0; i <= ProxyConfig.MAX_PROXIES; i++) text.append("  - name: n").append(i).append('\n');
        rejects("PROXY_CONFIG_PROXIES_TOO_MANY", text.toString());
    }

    @Test public void namesAreUnquotedForTheGroupAndRequotedSafely() {
        assertEquals("plain", ProxyConfig.unquote("plain"));
        assertEquals("plain", ProxyConfig.unquote("plain # 注释"));
        assertEquals("a # b", ProxyConfig.unquote("\"a # b\""));
        assertEquals("say \"hi\"", ProxyConfig.unquote("\"say \\\"hi\\\"\""));
        assertEquals("it's", ProxyConfig.unquote("'it''s'"));
        assertEquals("Oracle 3", ProxyConfig.unquote("'Oracle 3'   # 带空格与注释"));
        assertEquals("a", ProxyConfig.unquote("\"a\" # 注释里有 \"引号\""));
        assertEquals("\"say \\\"hi\\\"\"", ProxyConfig.quote("say \"hi\""));
        assertEquals("\"back\\\\slash\"", ProxyConfig.quote("back\\slash"));
    }

    private static void rejects(String code, String text) {
        try { ProxyConfig.extract(text); fail("应当拒绝：" + code); }
        catch (java.io.IOException expected) { assertEquals(code, expected.getMessage()); }
    }
}
