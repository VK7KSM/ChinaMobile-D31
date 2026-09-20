package net.elfradio.d31bootstrap;

import org.json.JSONObject;

/**
 * `proxy_runtime` 与代理任务回执里的状态，v2 形状。
 * 16 个基础键必须齐全；可选键（配置身份、错误类别、四条经代理路径）按语义给：未配置时不得带配置身份。
 * 固定值（`abi`、两个端口、`write_locked`、`schema_version`）与服务端硬校验一致。
 * `bundled` 为 false：核心不随 APK 分发，按需下载；未安装时 `version` 为 null。
 */
final class ProxyStatus {
    static final String ABI = "arm64-v8a";

    static JSONObject build(String version, boolean assetVerified, boolean coreVerified, boolean configured,
                            boolean running, boolean httpReady, boolean socksReady, boolean reachable,
                            String managementVia, long checkedAtMs,
                            String configVersion, String configSha256, String errorCategory) throws Exception {
        JSONObject status = new JSONObject().put("schema_version", 2).put("bundled", false)
                .put("version", version == null || version.isEmpty() ? JSONObject.NULL : version).put("abi", ABI)
                .put("asset_verified", assetVerified).put("core_verified", coreVerified).put("configured", configured)
                .put("running", running).put("http_ready", httpReady).put("socks_ready", socksReady)
                .put("proxy_reachable", reachable).put("management_via", managementVia).put("write_locked", true)
                .put("http_port", ProxyConfig.HTTP_PORT).put("socks_port", ProxyConfig.SOCKS_PORT)
                .put("checked_at_ms", checkedAtMs)
                .put("management_https_via_proxy", false).put("management_mqtt_via_proxy", false)
                .put("adb_wss_via_proxy_ready", false).put("file_download_via_proxy_ready", false)
                .put("error_category", errorCategory == null || errorCategory.isEmpty() ? "none" : errorCategory);
        if (configured) status.put("config_version", configVersion == null || configVersion.isEmpty() ? JSONObject.NULL : configVersion)
                .put("config_sha256", configSha256);
        return status;
    }

    /** 本机内部错误码 → 服务端错误类别表（`PROXY_ERROR_CATEGORIES`）。 */
    static String category(String lastError, boolean installed, boolean configured) {
        if (!installed) return "core_missing";
        if (lastError == null || lastError.isEmpty()) return configured ? "none" : "not_configured";
        if (lastError.equals("management_unreachable")) return "https_test_failed";
        if (lastError.equals("rollback") || lastError.equals("proxy_unreachable")) return "proxy_unreachable";
        if (lastError.equals("process_start_failed")) return "process_start_failed";
        if (lastError.equals("unconfirmed_at_boot")) return "process_not_running";
        return "unknown";
    }

    private ProxyStatus() {}
}
