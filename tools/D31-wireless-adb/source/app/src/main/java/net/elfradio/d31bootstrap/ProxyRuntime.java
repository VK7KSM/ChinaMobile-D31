package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import org.json.JSONObject;

/**
 * 代理模块的编排：安装、配置、起停、移除、上报，以及看门狗。
 *
 * 三态：未安装（目录不存在）→ 已安装未启用 → 已启用。配置是事务：候选文件先过语法检查，
 * 再起一次核心做连通测试，不通就换回上一份，绝不留下「配置写了但起不来」的中间态。
 *
 * 看门狗是设备侧的底线：启用后一段时间内管理服务器必须仍可直连，否则停核心、标坏、记账；
 * 开机只从确认过的配置起，开机窗口内连不上就停。它只兜错误，不当触发条件——
 * 没有网络时什么都不做（所有者硬约束：新机开箱没配网不是「直连被墙」）。
 *
 * 第 2 步的核心只开本机回环监听、不开 TUN，看门狗在这一步没有机会触发，但逻辑与测试先到位。
 */
final class ProxyRuntime implements ProxyTasks.Runtime {
    /** 操作系统层面的实体，测试里换成假的。 */
    interface Core {
        File home();
        boolean installed();
        String version();
        void install(File gz, ProxyCoreManifest manifest) throws Exception;
        void remove() throws Exception;
        void syntax(File candidate) throws Exception;
        void start(File config) throws Exception;
        void stop() throws Exception;
        boolean running();
        boolean httpReady();
        boolean socksReady();
        boolean reachable();
    }
    /** 网络与管理服务器的事实，由核心进程提供。 */
    interface Environment {
        boolean online();
        boolean managementReachable();
        long now();
    }
    static final long PROBE_INTERVAL_MS = 30000;
    static final long CONFIRM_WINDOW_MS = 90000;
    static final long BOOT_WINDOW_MS = 180000;
    static final long RESTART_INTERVAL_MS = 60000;
    static final int RESTART_LIMIT = 3;
    static final long MAX_CONFIG_BYTES = 2L * 1024 * 1024;
    static final String DIRECT = "direct";

    private final Core core;
    private final ProxyDownload.Fetch fetch;
    private final Environment env;
    private final PublicKey key;
    private final File configDir, current, previous, raw, stateFile;
    private JSONObject state;
    private long probeDue, restartDue;
    private int probeFailures, probeBudget, restartFailures;

    ProxyRuntime(Core core, ProxyDownload.Fetch fetch, Environment env, PublicKey key) throws Exception {
        this.core = core; this.fetch = fetch; this.env = env; this.key = key;
        configDir = new File(core.home(), "config");
        current = new File(configDir, "current.yaml"); previous = new File(configDir, "previous.yaml");
        raw = new File(configDir, "proxies.yaml"); stateFile = new File(core.home(), "state.json");
        state = load();
        // 开机恢复：只起确认过的配置；起来后按开机窗口再验一次直连。核心未跑而记录说启用，就是重启后的恢复。
        if (state.optBoolean("enabled")) {
            if (!state.optBoolean("confirmed")) { state.put("enabled", false).put("last_error", "unconfirmed_at_boot"); save(); }
            else arm(env.now(), BOOT_WINDOW_MS);
        }
    }

    private JSONObject load() {
        try { return stateFile.isFile() ? new JSONObject(RescueFiles.read(stateFile, 8192)) : new JSONObject(); }
        catch (Exception unavailable) { return new JSONObject(); }
    }

    private void save() throws Exception {
        if (!core.home().isDirectory() && !core.home().mkdirs()) throw new IOException("PROXY_HOME_UNAVAILABLE");
        RescueFiles.write(stateFile, state.toString());
    }

    synchronized boolean installed() { return core.installed(); }

    /** 当前配置身份，供上报与回退时说明「退到了哪一版」。 */
    synchronized String configVersion() { return state.optBoolean("configured") ? state.optString("config_version") : ""; }
    synchronized String lastError() { return state.optString("last_error"); }

    public synchronized JSONObject status() throws Exception {
        boolean installed = core.installed();
        boolean configured = installed && state.optBoolean("configured") && current.isFile();
        boolean running = installed && core.running();
        boolean http = running && core.httpReady(), socks = running && core.socksReady();
        boolean reachable = running && http && socks && state.optBoolean("proxy_reachable");
        return ProxyStatus.build(installed ? core.version() : "", installed, installed, configured,
                running, http, socks, reachable, DIRECT, env.now(),
                state.optString("config_version"), state.optString("config_sha256"),
                ProxyStatus.category(state.optString("last_error"), installed, configured));
    }

    /**
     * 配置事务。核心不在就先按签名清单拉核心；配置只取节点段；候选先过语法，再起一次做连通测试，
     * 不通就整体回退到上一份。返回配置后的状态。
     */
    public synchronized JSONObject configure(JSONObject params) throws Exception {
        if (params == null) throw new IOException("PROXY_PARAMS_MISSING");
        String url = ProxyDownload.validate(params.optString("url"), ProxyDownload.CONFIG_PATH);
        long size = params.optLong("size"); String sha = params.optString("sha256"), version = params.optString("version");
        if (size < 2 || size > MAX_CONFIG_BYTES || !sha.matches("[0-9a-f]{64}")) throw new IOException("PROXY_CONFIG_CONTRACT");
        if (version.length() > 64) throw new IOException("PROXY_CONFIG_VERSION_INVALID");
        if (!core.installed()) installCore(params.optJSONObject("core"));
        if (!configDir.isDirectory() && !configDir.mkdirs()) throw new IOException("PROXY_CONFIG_DIR");
        File download = new File(configDir, "download.yaml"), candidate = new File(configDir, "candidate.yaml");
        try {
            fetch.fetch(url, size, sha, download);
            ProxyConfig.Proxies proxies = ProxyConfig.extract(RescueFiles.read(download, (int) MAX_CONFIG_BYTES));
            RescueFiles.write(candidate, ProxyConfig.render(proxies));
            core.syntax(candidate);
            boolean wasRunning = core.running();
            JSONObject before = new JSONObject(state.toString());
            if (wasRunning) core.stop();
            if (current.isFile()) { previous.delete(); if (!current.renameTo(previous)) throw new IOException("PROXY_CONFIG_ROTATE"); }
            if (!candidate.renameTo(current)) { restore(before, wasRunning); throw new IOException("PROXY_CONFIG_COMMIT"); }
            raw.delete(); download.renameTo(raw);
            state.put("configured", true).put("config_version", version).put("config_sha256", sha).put("config_size", size)
                    .put("bad_config", false).put("last_error", "").put("proxy_reachable", false);
            save();
            try {
                core.start(current);
                if (!probe()) throw new IOException("PROXY_UNREACHABLE");
                if (!wasRunning) core.stop();
            } catch (Exception failure) {
                try { core.stop(); } catch (Exception ignored) { }
                rollback(before, wasRunning);
                throw failure;
            }
            return status();
        } finally { download.delete(); candidate.delete(); }
    }

    private void installCore(JSONObject core) throws Exception {
        ProxyCoreManifest manifest = ProxyCoreManifest.parse(core, key, env.now());
        if (!this.core.home().isDirectory() && !this.core.home().mkdirs()) throw new IOException("PROXY_HOME_UNAVAILABLE");
        File gz = new File(this.core.home(), "mihomo.gz");
        try {
            fetch.fetch(manifest.url, manifest.size, manifest.sha256, gz);
            this.core.install(gz, manifest);
        } finally { gz.delete(); }
    }

    /** 候选没换上去：状态没动，只需把进程恢复。 */
    private void restore(JSONObject before, boolean wasRunning) {
        state = before;
        try { save(); if (wasRunning && current.isFile()) core.start(current); } catch (Exception ignored) { }
    }

    /** 候选换上去了但起不来或不通：换回上一份，状态退回，原来在跑就再起。 */
    private void rollback(JSONObject before, boolean wasRunning) {
        current.delete();
        if (previous.isFile()) previous.renameTo(current);
        state = before;
        try {
            if (!current.isFile()) state.put("configured", false);
            state.put("last_error", "rollback");
            save();
        } catch (Exception ignored) { }
        if (wasRunning && current.isFile()) try { core.start(current); } catch (Exception ignored) { }
    }

    /** 一次连通测试，结果落盘：状态里的 `proxy_reachable` 只反映最近一次真实测试。 */
    private boolean probe() throws Exception {
        boolean ok = core.running() && core.httpReady() && core.socksReady() && core.reachable();
        state.put("proxy_reachable", ok).put("tested_at_ms", env.now()); save();
        return ok;
    }

    public synchronized JSONObject start() throws Exception {
        if (!core.installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        if (!state.optBoolean("configured") || !current.isFile()) throw new IOException("PROXY_NOT_CONFIGURED");
        core.start(current);
        boolean ok = probe();
        state.put("enabled", true).put("confirmed", false).put("last_error", ok ? "" : "proxy_unreachable");
        restartFailures = 0;
        arm(env.now(), CONFIRM_WINDOW_MS);
        save();
        return status();
    }

    public synchronized JSONObject stop() throws Exception {
        core.stop();
        disarm();
        state.put("enabled", false).put("proxy_reachable", false); save();
        return status();
    }

    public synchronized JSONObject test() throws Exception {
        if (!core.installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        probe();
        return status();
    }

    /** 顺序不能反：先停进程，再删目录；目录没了状态也没了，就是「未安装」。 */
    public synchronized JSONObject remove() throws Exception {
        disarm();
        core.remove();
        state = new JSONObject();
        return status();
    }

    private void arm(long now, long windowMs) {
        probeDue = now + PROBE_INTERVAL_MS; probeFailures = 0;
        probeBudget = (int) Math.max(1, windowMs / PROBE_INTERVAL_MS);
    }

    private void disarm() { probeDue = 0; probeFailures = 0; probeBudget = 0; }

    /**
     * 看门狗与自愈，由核心每轮调用。没有网络时整段跳过：既不探测也不计数，等网络回来。
     * 直连探测连续失败用完预算才回退；一次成功即确认。核心自己退出了（崩溃）在确认过的前提下限次拉起。
     */
    synchronized void tick() {
        if (!env.online() || !state.optBoolean("enabled")) return;
        long now = env.now();
        try {
            if (probeDue > 0 && now >= probeDue) {
                if (env.managementReachable()) { state.put("confirmed", true).put("last_error", ""); disarm(); save(); }
                else if (++probeFailures >= probeBudget) {
                    core.stop();
                    state.put("enabled", false).put("confirmed", false).put("bad_config", true)
                            .put("last_error", "management_unreachable").put("proxy_reachable", false);
                    disarm(); save();
                    return;
                } else probeDue = now + PROBE_INTERVAL_MS;
            }
            if (!core.running() && state.optBoolean("confirmed") && now >= restartDue) {
                restartDue = now + RESTART_INTERVAL_MS;
                if (restartFailures >= RESTART_LIMIT) {
                    state.put("enabled", false).put("last_error", "process_start_failed"); save();
                    return;
                }
                try { core.start(current); probe(); restartFailures = 0; }
                catch (Exception failure) { restartFailures++; }
            }
        } catch (Exception unavailable) { System.err.println("PROXY_WATCHDOG_TICK_FAILED " + unavailable); }
    }

    /** 测试用：当前看门狗是否仍在等直连确认。 */
    synchronized boolean armed() { return probeDue > 0; }
    synchronized boolean enabled() { return state.optBoolean("enabled"); }
    synchronized boolean confirmed() { return state.optBoolean("confirmed"); }
}
