package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * 有界下载：只认本管理服务器、只认带一次性凭证的下载口，先比长度、后比哈希，全对才落盘。
 * 配置与核心共用同一段，差别只在路径形状与大小上限。
 */
final class ProxyDownload {
    interface Fetch { void fetch(String url, long size, String sha256, File dest) throws Exception; }
    static final String CONFIG_PATH = "/api/elfremote/proxy-config/[A-Za-z0-9-]{1,96}";
    private static final String QUERY = "device_id=[A-Za-z0-9_-]{1,128}&token=[A-Za-z0-9_-]{16,256}";

    static String validate(String raw, String pathPattern) throws IOException { return validate(raw, pathPattern, true); }

    /**
     * 与 Pixel 网关同一套：协议、主机、端口、无用户信息、无片段、路径形状。
     * 配置下载口的查询串必须恰好是两项一次性凭证；核心下载口（与 APK 同一个）不带查询串。
     */
    static String validate(String raw, String pathPattern, boolean credentialQuery) throws IOException {
        if (raw == null || raw.length() > 4096) throw new IOException("PROXY_DOWNLOAD_URL_INVALID");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) throw new IOException("PROXY_DOWNLOAD_URL_INVALID");
        }
        URI url;
        try { url = new URI(raw); } catch (Exception malformed) { throw new IOException("PROXY_DOWNLOAD_URL_INVALID"); }
        URI base = URI.create(RemoteProtocol.BASE);
        if (!"https".equals(url.getScheme()) || url.getHost() == null || !url.getHost().equalsIgnoreCase(base.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null || url.getFragment() != null
                || url.getRawPath() == null || !url.getRawPath().matches(pathPattern)) throw new IOException("PROXY_DOWNLOAD_URL_REJECTED");
        if (credentialQuery ? (url.getRawQuery() == null || !url.getRawQuery().matches(QUERY)) : url.getRawQuery() != null)
            throw new IOException("PROXY_DOWNLOAD_URL_REJECTED");
        return raw;
    }

    static void fetch(String url, long size, String sha256, File dest) throws Exception {
        if (size <= 0 || !sha256.matches("[0-9a-f]{64}")) throw new IOException("PROXY_DOWNLOAD_CONTRACT");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        File temporary = new File(dest.getPath() + ".part");
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Accept", "application/octet-stream, application/yaml, text/yaml");
            int status = c.getResponseCode();
            if (status != 200) throw new RemoteHttp.Rejected(status, "代理制品下载响应不符",
                    RemoteHttp.retryAfterDelay(c.getHeaderField("Retry-After"), System.currentTimeMillis()));
            String declared = c.getHeaderField("Content-Length");
            if (declared != null && !declared.equals(String.valueOf(size))) throw new IOException("PROXY_DOWNLOAD_LENGTH_MISMATCH");
            long received = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) {
                    received += n;
                    if (received > size) throw new IOException("PROXY_DOWNLOAD_OVERFLOW");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            if (received != size) throw new IOException("PROXY_DOWNLOAD_INCOMPLETE");
            if (!sha256.equals(RescueFiles.sha256(temporary))) throw new IOException("PROXY_DOWNLOAD_HASH_MISMATCH");
            if (dest.exists() && !dest.delete()) throw new IOException("PROXY_DOWNLOAD_REPLACE");
            if (!temporary.renameTo(dest)) throw new IOException("PROXY_DOWNLOAD_RENAME");
        } finally {
            c.disconnect();
            if (temporary.exists()) temporary.delete();
        }
    }

    private ProxyDownload() {}
}
