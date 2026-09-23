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
    private static final String DEVICE = "device_id=[A-Za-z0-9_-]{1,128}";
    private static final String TOKEN = "token=[A-Za-z0-9_-]{16,256}";
    /** 过渡期两种都收：服务端仍在下发带 token 的地址，改完验收后才会去掉。 */
    private static final String QUERY = DEVICE + "(?:&" + TOKEN + ")?";

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

    /**
     * 一次性下载令牌从查询串挪到 Authorization 头：查询串会进各种访问日志，令牌不该留在那里。
     * 服务端读到 Bearer 就以它为准、不再拿查询串兜底，所以这里摘下来之后必须把它从地址里去掉。
     * 返回「不带令牌的地址 + 令牌」；地址里本来就没有令牌时令牌为空，按无凭证请求发出去。
     */
    static String[] splitToken(String url) {
        int query = url.indexOf('?');
        if (query < 0) return new String[]{url, ""};
        StringBuilder kept = new StringBuilder();
        String token = "";
        for (String pair : url.substring(query + 1).split("&")) {
            if (pair.startsWith("token=")) { token = pair.substring(6); continue; }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        String head = url.substring(0, query);
        return new String[]{kept.length() == 0 ? head : head + "?" + kept, token};
    }

    static void fetch(String url, long size, String sha256, File dest) throws Exception {
        if (size <= 0 || !sha256.matches("[0-9a-f]{64}")) throw new IOException("PROXY_DOWNLOAD_CONTRACT");
        String[] split = splitToken(url);
        String bearer = split[1];
        HttpURLConnection c = (HttpURLConnection) new URL(split[0]).openConnection();
        File temporary = new File(dest.getPath() + ".part");
        try {
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Accept", "application/octet-stream, application/yaml, text/yaml");
            if (!bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
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
