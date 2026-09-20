package net.elfradio.d31bootstrap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 服务端对 v2 状态按精确键集校验；这里钉住键集与几个硬值，改错一个字段服务端就整条拒收。 */
public class ProxyStatusTest {
    private static final Set<String> REQUIRED = new HashSet<>(Arrays.asList("schema_version", "bundled", "version", "abi",
            "asset_verified", "core_verified", "configured", "running", "http_ready", "socks_ready", "proxy_reachable",
            "management_via", "write_locked", "http_port", "socks_port", "checked_at_ms"));
    private static final Set<String> OPTIONAL = new HashSet<>(Arrays.asList("config_version", "config_sha256", "error_category",
            "management_https_via_proxy", "management_mqtt_via_proxy", "adb_wss_via_proxy_ready", "file_download_via_proxy_ready"));

    private static Set<String> keys(JSONObject value) {
        Set<String> result = new HashSet<>();
        for (java.util.Iterator<String> it = value.keys(); it.hasNext();) result.add(it.next());
        return result;
    }

    @Test public void notInstalledCarriesNoVersionAndNoConfigIdentity() throws Exception {
        JSONObject status = ProxyStatus.build("", false, false, false, false, false, false, false, "direct", 1000L, "", "", "core_missing");
        assertTrue(REQUIRED.stream().allMatch(status::has));
        assertTrue(status.isNull("version"));
        assertFalse("未配置时不得携带配置身份", status.has("config_version"));
        assertFalse(status.has("config_sha256"));
        assertEquals("core_missing", status.getString("error_category"));
        assertEquals(2, status.getInt("schema_version"));
        assertFalse(status.getBoolean("bundled"));
        assertEquals("arm64-v8a", status.getString("abi"));
        assertEquals(17890, status.getInt("http_port"));
        assertEquals(17891, status.getInt("socks_port"));
        assertTrue(status.getBoolean("write_locked"));
        Set<String> all = new HashSet<>(REQUIRED); all.addAll(OPTIONAL);
        assertTrue("不得出现合同之外的键：" + keys(status), all.containsAll(keys(status)));
    }

    @Test public void configuredCarriesItsIdentity() throws Exception {
        JSONObject status = ProxyStatus.build("1.19.31", true, true, true, true, true, true, true, "direct", 1000L, "v7", "b".repeat(64), "");
        assertEquals("1.19.31", status.getString("version"));
        assertEquals("v7", status.getString("config_version"));
        assertEquals("b".repeat(64), status.getString("config_sha256"));
        assertEquals("none", status.getString("error_category"));
        // 第 2 步这四条路径都没做，必须是 false，不能是 true。
        for (String key : new String[]{"management_https_via_proxy", "management_mqtt_via_proxy", "adb_wss_via_proxy_ready", "file_download_via_proxy_ready"})
            assertFalse(key, status.getBoolean(key));
    }

    @Test public void internalErrorsMapOntoTheServersCategoryTable() {
        assertEquals("core_missing", ProxyStatus.category("", false, false));
        assertEquals("not_configured", ProxyStatus.category("", true, false));
        assertEquals("none", ProxyStatus.category("", true, true));
        assertEquals("https_test_failed", ProxyStatus.category("management_unreachable", true, true));
        assertEquals("proxy_unreachable", ProxyStatus.category("rollback", true, true));
        assertEquals("process_start_failed", ProxyStatus.category("process_start_failed", true, true));
        assertEquals("unknown", ProxyStatus.category("something_new", true, true));
    }
}
