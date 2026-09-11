package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;

final class RemoteUpdateFiles {
    static void download(JSONObject manifest, File destination) throws Exception {
        if (destination.isFile() && destination.length() == manifest.getLong("size")
                && RescueFiles.sha256(destination).equals(manifest.getString("sha256"))) return;
        File part = new File(destination.getPath() + ".part");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(manifest.getString("url")).openConnection();
        try {
            connection.setSSLSocketFactory(RemoteTls.factory());
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) throw new IOException("下载响应非200");
            long length = connection.getContentLength();
            if (length >= 0 && length != manifest.getLong("size")) throw new SecurityException("下载长度不符");
            try (InputStream in = connection.getInputStream()) { copyBounded(in, part, manifest.getLong("size")); }
            if (!manifest.getString("sha256").equals(RescueFiles.sha256(part))) throw new SecurityException("下载摘要不符");
            if (!part.renameTo(destination)) throw new IOException("下载提交失败");
        } finally { connection.disconnect(); }
    }

    static void copyBounded(InputStream in, File target, long expected) throws Exception {
        if (expected <= 0 || expected > RemoteUpdatePolicy.MAX_BYTES) throw new IOException("长度超限");
        try (FileOutputStream out = new FileOutputStream(target)) {
            byte[] bytes = new byte[32768]; long count = 0; int n;
            long until = System.nanoTime() + 300000000000L;
            while ((n = in.read(bytes)) != -1) {
                if (System.nanoTime() >= until) throw new IOException("传输总时限已到");
                count += n; if (count > expected) throw new SecurityException("实际数据超长");
                out.write(bytes, 0, n);
            }
            if (count != expected) throw new IOException("数据未完整接收");
            out.getFD().sync();
        }
    }

    static void copy(File source, File target) throws Exception {
        try (InputStream in = new FileInputStream(source)) { copyBounded(in, target, source.length()); }
        if (!RescueFiles.sha256(source).equals(RescueFiles.sha256(target))) throw new IOException("文件复制校验失败");
    }

    static JSONObject read(File file) throws Exception { return new JSONObject(RescueFiles.read(file, 64000)); }
}
