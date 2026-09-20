package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * 代理编排与看门狗。操作系统层换成假的，专心钉住三件事：
 * 配置是事务（不通就回退到上一份）、看门狗兜住「代理把直连弄坏」、没有网络时看门狗一动不动。
 */
public class ProxyRuntimeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** 假核心：只记账，不碰进程；`remove` 真删目录，因为「未安装 = 目录不存在」是编排依赖的事实。 */
    static final class Core implements ProxyRuntime.Core {
        final File home;
        boolean installed, running, http = true, socks = true, reachable = true, syntaxFails, startFails;
        String version = "";
        int starts, stops;
        Core(File home) { this.home = home; }
        @Override public File home() { return home; }
        @Override public boolean installed() { return installed; }
        @Override public String version() { return version; }
        @Override public void install(File gz, ProxyCoreManifest manifest) { installed = true; version = manifest.version; }
        @Override public void remove() { running = false; installed = false; delete(home); }
        @Override public void syntax(File candidate) throws Exception { if (syntaxFails) throw new IOException("PROXY_SYNTAX_INVALID"); }
        @Override public void start(File config) throws Exception {
            if (startFails) throw new IOException("PROXY_LISTENER_TIMEOUT");
            if (!config.isFile()) throw new IOException("PROXY_CONFIG_MISSING");
            running = true; starts++;
        }
        @Override public void stop() { if (running) stops++; running = false; }
        @Override public boolean running() { return running; }
        @Override public boolean httpReady() { return running && http; }
        @Override public boolean socksReady() { return running && socks; }
        @Override public boolean reachable() { return running && reachable; }
        private static void delete(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
            file.delete();
        }
    }

    static final class Env implements ProxyRuntime.Environment {
        boolean online = true, reachable = true;
        long now = 1789800000000L;
        int probes;
        @Override public boolean online() { return online; }
        @Override public boolean managementReachable() { probes++; return reachable; }
        @Override public long now() { return now; }
    }

    /** 假下载：核心地址写点字节；配置地址写测试给的节点段。 */
    static final class Fetch implements ProxyDownload.Fetch {
        final List<String> urls = new ArrayList<>();
        String proxies = "proxies:\n  - name: a\n    type: vless\n    server: 1.2.3.4\n";
        @Override public void fetch(String url, long size, String sha256, File dest) throws Exception {
            urls.add(url);
            RescueFiles.write(dest, url.contains("/apk/") ? "gz-bytes" : proxies);
        }
    }

    private static final String CONFIG_URL = "https://v.elfradio.net/api/elfremote/proxy-config/task-1?device_id=dev_abc&token=" + "f".repeat(64);

    private Core core;
    private final Env env = new Env();
    private final Fetch fetch = new Fetch();

    private ProxyRuntime runtime() throws Exception {
        if (core == null) core = new Core(new File(temp.getRoot(), "d31-proxy"));
        return new ProxyRuntime(core, fetch, env, ProxyCoreManifestTest.KEYS.getPublic());
    }

    private static JSONObject params(String version, boolean withCore) throws Exception {
        JSONObject p = new JSONObject().put("url", CONFIG_URL).put("size", 100).put("sha256", "c".repeat(64)).put("version", version);
        if (withCore) p.put("core", ProxyCoreManifestTest.signed(ProxyCoreManifestTest.manifest(), ProxyCoreManifestTest.KEYS));
        return p;
    }

    private void tickAfter(ProxyRuntime runtime, long ms) { env.now += ms; runtime.tick(); }

    @Test public void freshRuntimeReportsNotInstalled() throws Exception {
        JSONObject status = runtime().status();
        assertFalse(status.getBoolean("asset_verified"));
        assertTrue(status.isNull("version"));
        assertFalse(status.getBoolean("configured"));
        assertEquals("core_missing", status.getString("error_category"));
        assertEquals("direct", status.getString("management_via"));
    }

    @Test public void configureNeedsASignedCoreWhenNothingIsInstalled() throws Exception {
        ProxyRuntime runtime = runtime();
        try { runtime.configure(params("v1", false)); fail(); }
        catch (IOException expected) { assertEquals("PROXY_CORE_MISSING", expected.getMessage()); }
        assertFalse(core.installed);
        assertTrue("核心没装成不得去拉配置", fetch.urls.isEmpty());
    }

    @Test public void configureInstallsTheCoreThenAppliesAndProvesTheConfig() throws Exception {
        ProxyRuntime runtime = runtime();
        JSONObject status = runtime.configure(params("v1", true));
        assertTrue(core.installed);
        assertEquals("1.19.31", status.getString("version"));
        assertEquals(java.util.Arrays.asList("https://v.elfradio.net/api/elfremote/apk/d31-proxy-core-1-19-31", CONFIG_URL), fetch.urls);
        assertTrue(status.getBoolean("configured"));
        assertEquals("v1", status.getString("config_version"));
        assertEquals("c".repeat(64), status.getString("config_sha256"));
        // 配置任务本身不等于启用：验证用的那次拉起在事务结束时收掉。
        assertFalse(status.getBoolean("running"));
        assertEquals(1, core.starts); assertEquals(1, core.stops);
        String rendered = RescueFiles.read(new File(new File(core.home, "config"), "current.yaml"), 65536);
        assertTrue(rendered.contains("  - name: a\n"));
        assertTrue(rendered.contains("bind-address: 127.0.0.1"));
        assertFalse("下载的临时文件必须清掉", new File(new File(core.home, "config"), "download.yaml").exists());
        assertFalse(new File(core.home, "mihomo.gz").exists());
    }

    @Test public void unreachableNewConfigRollsBackToThePreviousOneAndRestoresTheRunningState() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        runtime.start();
        assertTrue(core.running);
        fetch.proxies = "proxies:\n  - name: b\n    type: vless\n";
        core.reachable = false;
        try { runtime.configure(params("v2", false)); fail(); }
        catch (IOException expected) { assertEquals("PROXY_UNREACHABLE", expected.getMessage()); }
        JSONObject status = runtime.status();
        assertEquals("v1", status.getString("config_version"));
        assertTrue("原来在跑，回退后要重新跑起来", core.running);
        assertEquals("proxy_unreachable", status.getString("error_category"));
        String rendered = RescueFiles.read(new File(new File(core.home, "config"), "current.yaml"), 65536);
        assertTrue(rendered.contains("  - name: a\n"));
        assertFalse(rendered.contains("  - name: b\n"));
    }

    @Test public void syntaxFailureLeavesTheCurrentConfigUntouched() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        int stops = core.stops;
        core.syntaxFails = true;
        fetch.proxies = "proxies:\n  - name: b\n";
        try { runtime.configure(params("v2", false)); fail(); }
        catch (IOException expected) { assertEquals("PROXY_SYNTAX_INVALID", expected.getMessage()); }
        assertEquals("v1", runtime.status().getString("config_version"));
        assertEquals("语法没过就不该动进程", stops, core.stops);
        assertFalse(new File(new File(core.home, "config"), "candidate.yaml").exists());
    }

    @Test public void startArmsTheWatchdogAndOneReachableProbeConfirms() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        JSONObject status = runtime.start();
        assertTrue(status.getBoolean("running"));
        assertTrue(status.getBoolean("proxy_reachable"));
        assertTrue(runtime.enabled()); assertTrue(runtime.armed()); assertFalse(runtime.confirmed());
        tickAfter(runtime, 10000);
        assertEquals("还没到探测时刻", 0, env.probes);
        tickAfter(runtime, ProxyRuntime.PROBE_INTERVAL_MS);
        assertEquals(1, env.probes);
        assertTrue(runtime.confirmed()); assertFalse(runtime.armed()); assertTrue(core.running);
    }

    @Test public void watchdogStopsTheCoreWhenDirectManagementStaysUnreachable() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        runtime.start();
        env.reachable = false;
        tickAfter(runtime, ProxyRuntime.PROBE_INTERVAL_MS);
        tickAfter(runtime, ProxyRuntime.PROBE_INTERVAL_MS);
        assertTrue("预算未用完不得停", core.running);
        tickAfter(runtime, ProxyRuntime.PROBE_INTERVAL_MS);
        assertFalse("90 秒内直连不通：停核心", core.running);
        assertFalse(runtime.enabled());
        JSONObject status = runtime.status();
        assertEquals("https_test_failed", status.getString("error_category"));
        assertTrue("配置身份仍在，面板能看到退的是哪一版", status.has("config_version"));
        // 之后不再自己拉起：要服务端再下令。
        tickAfter(runtime, ProxyRuntime.RESTART_INTERVAL_MS * 3);
        assertFalse(core.running);
    }

    @Test public void noNetworkFreezesTheWatchdogCompletely() throws Exception {
        // 所有者硬约束：新机开箱没配网，不能把「没网络」当「直连被墙」。
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        runtime.start();
        env.online = false; env.reachable = false;
        for (int i = 0; i < 20; i++) tickAfter(runtime, ProxyRuntime.PROBE_INTERVAL_MS);
        assertEquals("没有网络不得探测", 0, env.probes);
        assertTrue(core.running); assertTrue(runtime.enabled()); assertTrue(runtime.armed());
        env.online = true; env.reachable = true;
        tickAfter(runtime, 1);
        assertTrue(runtime.confirmed());
    }

    @Test public void bootRestoresOnlyAConfirmedConfigAndReverifiesIt() throws Exception {
        ProxyRuntime first = runtime();
        first.configure(params("v1", true));
        first.start();
        tickAfter(first, ProxyRuntime.PROBE_INTERVAL_MS);
        assertTrue(first.confirmed());
        // 重启：进程没了，记录还在。
        core.running = false; core.starts = 0;
        ProxyRuntime second = runtime();
        assertTrue(second.enabled()); assertTrue(second.armed());
        tickAfter(second, 1);
        assertEquals("确认过的配置开机要拉起", 1, core.starts);
        assertTrue(core.running);
        env.probes = 0;
        tickAfter(second, ProxyRuntime.PROBE_INTERVAL_MS);
        assertTrue(second.confirmed());
    }

    @Test public void bootDoesNotRestoreAnUnconfirmedConfig() throws Exception {
        ProxyRuntime first = runtime();
        first.configure(params("v1", true));
        first.start();
        core.running = false; core.starts = 0;
        ProxyRuntime second = runtime();
        assertFalse("没确认过的配置开机不得起", second.enabled());
        tickAfter(second, ProxyRuntime.RESTART_INTERVAL_MS);
        assertEquals(0, core.starts);
        assertEquals("process_not_running", second.status().getString("error_category"));
    }

    @Test public void bootWindowStopsARestoreThatCannotReachManagement() throws Exception {
        ProxyRuntime first = runtime();
        first.configure(params("v1", true));
        first.start();
        tickAfter(first, ProxyRuntime.PROBE_INTERVAL_MS);
        core.running = false;
        ProxyRuntime second = runtime();
        env.reachable = false;
        int probes = (int) (ProxyRuntime.BOOT_WINDOW_MS / ProxyRuntime.PROBE_INTERVAL_MS);
        for (int i = 0; i < probes - 1; i++) tickAfter(second, ProxyRuntime.PROBE_INTERVAL_MS);
        assertTrue("开机窗口没用完不得停", core.running);
        tickAfter(second, ProxyRuntime.PROBE_INTERVAL_MS);
        assertFalse(core.running); assertFalse(second.enabled());
    }

    @Test public void crashRestartIsBounded() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        runtime.start();
        tickAfter(runtime, ProxyRuntime.PROBE_INTERVAL_MS);
        core.running = false; core.startFails = true; core.starts = 0;
        for (int i = 0; i < ProxyRuntime.RESTART_LIMIT + 2; i++) tickAfter(runtime, ProxyRuntime.RESTART_INTERVAL_MS);
        assertFalse(runtime.enabled());
        assertEquals("process_start_failed", runtime.status().getString("error_category"));
    }

    @Test public void stopThenRemoveLeavesNothingBehind() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        runtime.start();
        JSONObject stopped = runtime.stop();
        assertFalse(stopped.getBoolean("running")); assertFalse(runtime.enabled());
        assertTrue(stopped.getBoolean("configured"));
        JSONObject removed = runtime.remove();
        assertFalse(removed.getBoolean("asset_verified")); assertFalse(removed.getBoolean("configured"));
        assertFalse(removed.getBoolean("running")); assertTrue(removed.isNull("version"));
        assertFalse("目录必须整个删掉", core.home.exists());
        // 之后再配置要重新拉核心。
        try { runtime.configure(params("v1", false)); fail(); }
        catch (IOException expected) { assertEquals("PROXY_CORE_MISSING", expected.getMessage()); }
    }

    @Test public void testReflectsTheLatestRealProbe() throws Exception {
        ProxyRuntime runtime = runtime();
        runtime.configure(params("v1", true));
        runtime.start();
        core.reachable = false;
        assertFalse(runtime.test().getBoolean("proxy_reachable"));
        core.reachable = true;
        assertTrue(runtime.test().getBoolean("proxy_reachable"));
        assertTrue("测试不改变启用状态", runtime.enabled());
    }
}
