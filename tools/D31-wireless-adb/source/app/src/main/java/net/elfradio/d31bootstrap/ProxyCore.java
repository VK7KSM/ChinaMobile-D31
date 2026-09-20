package net.elfradio.d31bootstrap;

import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

/**
 * 代理核心在本机上的实体：目录、二进制、进程与探测。只做操作系统层面的事，不做编排。
 *
 * 目录 /data/local/d31-proxy 是核心目录 /data/local/d31-remote 的同级而非子级（scrcpy 那次的教训），
 * 但与 scrcpy 不同，Mihomo 以 root 运行，没有别的 uid 要读它，所以整个目录 0700。
 *
 * 进程用 setsid 脱离核心：管理程序升级或重启不能顺手把正在工作的代理杀掉（方案第六节）。
 * 归属靠 pid 文件 + /proc/<pid>/cmdline 双重核对，不靠 Java 的 Process 句柄。
 */
final class ProxyCore implements ProxyRuntime.Core {
    static final String HOME_PATH = "/data/local/d31-proxy";
    static final File HOME = new File(HOME_PATH);
    /** 解压上限：v1.19.31 解压后约 64 MB；超过说明不是我们签发的东西。 */
    static final long MAX_BINARY = 128L * 1024 * 1024;
    private static final int PORT_TIMEOUT_MS = 500;

    private final File home, binary, record, run, log, pid, current;
    private String verifiedSignature = "";

    ProxyCore(File home) {
        this.home = home;
        binary = new File(home, "mihomo"); record = new File(home, "core.json");
        run = new File(home, "run"); log = new File(home, "mihomo.log"); pid = new File(home, "mihomo.pid");
        current = new File(new File(home, "config"), "current.yaml");
    }

    @Override public File home() { return home; }

    /** 已安装 = 记录在、二进制长度对、哈希对。哈希只在长度或修改时间变了才重算，63 MB 每轮算一遍不值。 */
    @Override public synchronized boolean installed() {
        try {
            if (!binary.isFile() || !record.isFile()) { verifiedSignature = ""; return false; }
            JSONObject saved = new JSONObject(RescueFiles.read(record, 4096));
            if (binary.length() != saved.optLong("size") || !saved.optString("sha256").matches("[0-9a-f]{64}")) return false;
            String signature = binary.length() + ":" + binary.lastModified() + ":" + saved.optString("sha256");
            if (signature.equals(verifiedSignature)) return true;
            boolean ok = saved.getString("sha256").equals(RescueFiles.sha256(binary));
            verifiedSignature = ok ? signature : "";
            return ok;
        } catch (Exception unavailable) { verifiedSignature = ""; return false; }
    }

    @Override public synchronized String version() {
        try { return record.isFile() ? new JSONObject(RescueFiles.read(record, 4096)).optString("version") : ""; }
        catch (Exception unavailable) { return ""; }
    }

    /** gz 已按签名清单验过；解压后再算一次二进制哈希记进 core.json，以后开机比对的是这个。 */
    @Override public synchronized void install(File gz, ProxyCoreManifest manifest) throws Exception {
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("PROXY_HOME_UNAVAILABLE");
        Os.chmod(home.getPath(), 0700);
        File next = new File(home, "mihomo.new");
        long length = 0;
        try (InputStream in = new GZIPInputStream(new FileInputStream(gz)); FileOutputStream out = new FileOutputStream(next)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) {
                length += n;
                if (length > MAX_BINARY) throw new IOException("PROXY_CORE_TOO_LARGE");
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        } catch (Exception failure) { next.delete(); throw failure; }
        if (length < 1024 * 1024) { next.delete(); throw new IOException("PROXY_CORE_TOO_SMALL"); }
        Os.chmod(next.getPath(), 0700);
        String sha = RescueFiles.sha256(next);
        if (binary.exists() && !binary.delete()) { next.delete(); throw new IOException("PROXY_CORE_REPLACE"); }
        if (!next.renameTo(binary)) { next.delete(); throw new IOException("PROXY_CORE_RENAME"); }
        RescueFiles.write(record, new JSONObject().put("version", manifest.version).put("size", length).put("sha256", sha)
                .put("gz_sha256", manifest.sha256).put("installed_at_ms", System.currentTimeMillis()).toString());
        Os.chmod(record.getPath(), 0600);
        verifiedSignature = "";
        if (!installed()) throw new IOException("PROXY_CORE_VERIFY");
    }

    /** 先停进程再删文件：反过来会留下一个还在跑、配置却没了的进程。 */
    @Override public synchronized void remove() throws Exception {
        stop();
        if (running()) throw new IOException("PROXY_PROCESS_STILL_RUNNING");
        delete(home);
        verifiedSignature = "";
        if (home.exists()) throw new IOException("PROXY_HOME_REMOVE");
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    @Override public synchronized void syntax(File candidate) throws Exception {
        if (!installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        prepareRun();
        Process process = new ProcessBuilder("/system/bin/sh", "-c",
                "exec " + RescueFiles.quote(binary.getPath()) + " -t -d " + RescueFiles.quote(run.getPath())
                        + " -f " + RescueFiles.quote(candidate.getPath()) + " </dev/null >>" + RescueFiles.quote(log.getPath()) + " 2>&1").start();
        int exit = waitFor(process, 25000);
        trimLog();
        if (exit != 0) throw new IOException(exit == Integer.MIN_VALUE ? "PROXY_SYNTAX_TIMEOUT" : "PROXY_SYNTAX_INVALID");
    }

    /** API 23 没有带时限的 waitFor；轮询 exitValue。超时返回 MIN_VALUE 并杀掉。 */
    static int waitFor(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try { return process.exitValue(); }
            catch (IllegalThreadStateException stillRunning) {
                if (System.currentTimeMillis() >= deadline) { process.destroy(); return Integer.MIN_VALUE; }
                Thread.sleep(100);
            }
        }
    }

    @Override public synchronized void start(File config) throws Exception {
        if (!installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        if (!config.isFile()) throw new IOException("PROXY_CONFIG_MISSING");
        if (running()) return;
        prepareRun();
        pid.delete();
        String script = "umask 077; echo $$ > " + RescueFiles.quote(pid.getPath() + ".new") + " && mv "
                + RescueFiles.quote(pid.getPath() + ".new") + " " + RescueFiles.quote(pid.getPath()) + " && exec "
                + RescueFiles.quote(binary.getPath()) + " -d " + RescueFiles.quote(run.getPath()) + " -f " + RescueFiles.quote(config.getPath());
        final Process child = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c",
                script + " </dev/null >>" + RescueFiles.quote(log.getPath()) + " 2>&1").start();
        // setsid 不是组长时直接 exec，这个 Process 句柄就是 mihomo 本身；不 wait 的话它退出后会留下僵尸项（2026-09-20 实测）。
        Thread reaper = new Thread(() -> { try { child.waitFor(); } catch (InterruptedException ignored) { } }, "d31-proxy-reap");
        reaper.setDaemon(true);
        reaper.start();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (httpReady() && socksReady()) { trimLog(); return; }
            int running = readPid();
            if (running > 1 && !new File("/proc/" + running).isDirectory()) break;
            Thread.sleep(200);
        }
        stop();
        throw new IOException("PROXY_LISTENER_TIMEOUT");
    }

    private void prepareRun() throws Exception {
        if (!run.isDirectory() && !run.mkdirs()) throw new IOException("PROXY_RUN_DIR");
        Os.chmod(run.getPath(), 0700);
    }

    /** SIGTERM 让它自己收拾（撤路由、关监听），3 秒不退再 SIGKILL。 */
    @Override public synchronized void stop() throws Exception {
        int id = readPid();
        if (id > 1 && identity(id)) {
            Os.kill(id, OsConstants.SIGTERM);
            for (int n = 0; n < 30 && new File("/proc/" + id).isDirectory(); n++) Thread.sleep(100);
            if (new File("/proc/" + id).isDirectory()) Os.kill(id, OsConstants.SIGKILL);
            for (int n = 0; n < 20 && new File("/proc/" + id).isDirectory(); n++) Thread.sleep(100);
        }
        pid.delete();
        trimLog();
    }

    @Override public synchronized boolean running() {
        int id = readPid();
        return id > 1 && identity(id);
    }

    private int readPid() {
        try { return Integer.parseInt(RescueFiles.read(pid, 32).trim()); }
        catch (Exception unavailable) { return -1; }
    }

    /** 进程号会被复用；命令行必须是我们的二进制加我们的配置，才认作自己的进程。 */
    private boolean identity(int id) {
        try (InputStream in = new FileInputStream(new File("/proc/" + id + "/cmdline"))) {
            byte[] buffer = new byte[4096]; int filled = 0, n;
            while (filled < buffer.length && (n = in.read(buffer, filled, buffer.length - filled)) != -1) filled += n;
            String command = new String(buffer, 0, filled, StandardCharsets.UTF_8).replace('\0', ' ');
            return command.startsWith(binary.getPath() + " ") && command.contains(" -f " + current.getPath());
        } catch (Exception unreadable) { return false; }
    }

    private static InetSocketAddress loopback(int port) { return new InetSocketAddress("127.0.0.1", port); }

    @Override public boolean httpReady() {
        try (Socket socket = new Socket()) { socket.connect(loopback(ProxyConfig.HTTP_PORT), PORT_TIMEOUT_MS); return true; }
        catch (Exception closed) { return false; }
    }

    @Override public boolean socksReady() {
        try (Socket socket = new Socket()) {
            socket.connect(loopback(ProxyConfig.SOCKS_PORT), PORT_TIMEOUT_MS); socket.setSoTimeout(1000);
            socket.getOutputStream().write(new byte[]{5, 1, 0});
            byte[] answer = new byte[2];
            return socket.getInputStream().read(answer) == 2 && answer[0] == 5 && answer[1] == 0;
        } catch (Exception closed) { return false; }
    }

    /** 经本机 HTTP 代理 CONNECT 到管理服务器 443：走的是 MATCH→节点那条路，通了就是节点通了。 */
    @Override public boolean reachable() {
        String host = java.net.URI.create(RemoteProtocol.BASE).getHost();
        try (Socket socket = new Socket()) {
            socket.connect(loopback(ProxyConfig.HTTP_PORT), 1000); socket.setSoTimeout(8000);
            socket.getOutputStream().write(("CONNECT " + host + ":443 HTTP/1.1\r\nHost: " + host + ":443\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            return line != null && line.matches("HTTP/1\\.[01] 200(?: .*)?");
        } catch (Exception closed) { return false; }
    }

    /** 直连管理服务器：拿到任何 HTTP 状态码都算通，证明 DNS、TCP、TLS 这条直连路径没被代理改坏。 */
    static boolean managementReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(RemoteProtocol.BASE + "/").openConnection();
            try {
                c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
                c.setRequestMethod("HEAD");
                return c.getResponseCode() > 0;
            } finally { c.disconnect(); }
        } catch (Exception unreachable) { return false; }
    }

    private void trimLog() {
        try {
            if (log.length() <= 256 * 1024) return;
            byte[] tail = new byte[128 * 1024];
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(log, "r")) {
                file.seek(file.length() - tail.length); file.readFully(tail);
            }
            File next = new File(log.getPath() + ".new");
            try (FileOutputStream out = new FileOutputStream(next)) { out.write(tail); out.getFD().sync(); }
            if (!next.renameTo(log)) next.delete();
        } catch (Exception ignored) { }
    }
}
