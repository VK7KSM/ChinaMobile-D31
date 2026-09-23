package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 代理模块的编排：安装、配置、起停、移除、上报、节点选择与测速，以及看门狗。
 *
 * 三态：未安装（目录不存在）→ 已安装未启用 → 已启用。配置是事务：候选文件先过语法检查，
 * 再起一次核心做连通测试，不通就换回上一份，绝不留下「配置写了但起不来」的中间态。
 * 服务端只给节点段（`proxies.yaml`）；每次启动都从它加本机状态（口令、分流名单）重新生成 `current.yaml`。
 *
 * 启用 = 起核心 + 建 TUN + 给分流名单里的应用下按 uid 的策略路由（这颗内核不认 sing-tun 自己的 uid 规则）。
 * 管理程序自己永远不在名单里；uid 0 的核心也不在，所以管理连接天然直连。
 *
 * 看门狗是设备侧的底线：启用后一段时间内管理服务器必须仍可直连，否则停核心、标坏、记账；
 * 开机只从确认过的配置起，开机窗口内连不上就停。它只兜错误，不当触发条件——
 * 没有网络时什么都不做（所有者硬约束：新机开箱没配网不是「直连被墙」）。
 */
final class ProxyRuntime implements ProxyLocal.Client {
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
        /** 本机控制口：当前选中的节点名，取不到返回空串。 */
        String selected(String secret);
        /** 本机控制口：切换手选组到该节点。 */
        void select(String secret, String node) throws Exception;
        /** 本机控制口：对单个节点做一次真实测速，毫秒；不通返回 -1。 */
        int delay(String secret, String node);
        /** 本机控制口：累计上传/下载字节与活动连接数；取不到返回空对象。 */
        JSONObject traffic(String secret);
        /** 把这些 uid 的流量导进 TUN；空列表即撤掉全部规则。TUN 接口不存在时抛出。 */
        void routes(List<Integer> uids) throws Exception;
        /** 按包名解析 uid；装了的才有条目。 */
        Map<String, Integer> uids(List<String> packages);
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
    /** 默认分流名单（所有者定）：Telegram、Zello 及其守护。管理程序永不纳入。 */
    static final String[] DEFAULT_APPS = {"org.telegram.messenger.web", "com.loudtalks", "net.elfradio.d31zelloguard"};
    static final String SELF_PACKAGE = "net.elfradio.d31bootstrap";

    /**
     * 管理程序自己（含 `.debug`/`.preview` 等子变体）永不走代理：管理连接进隧道是本项目唯一不可恢复的故障。
     * 判据与服务端 `MANAGEMENT_PACKAGES` 一致——**只是这一个包**，不是整个 `net.elfradio.` 命名空间：
     * 同命名空间里的 Zello 守护、SIP 短信客户端都是普通应用，把边界画大了，代价会落在正常功能上。
     */
    static boolean management(String pkg) {
        return pkg != null && (pkg.equals(SELF_PACKAGE) || pkg.startsWith(SELF_PACKAGE + "."));
    }

    private final Core core;
    private final ProxyDownload.Fetch fetch;
    private final Environment env;
    private final PublicKey key;
    private final File configDir, current, raw, previousRaw, stateFile;
    private JSONObject state;
    private long probeDue, restartDue;
    private int probeFailures, probeBudget, restartFailures;

    ProxyRuntime(Core core, ProxyDownload.Fetch fetch, Environment env, PublicKey key) throws Exception {
        this.core = core; this.fetch = fetch; this.env = env; this.key = key;
        configDir = new File(core.home(), "config");
        current = new File(configDir, "current.yaml");
        raw = new File(configDir, "proxies.yaml"); previousRaw = new File(configDir, "proxies.previous.yaml");
        stateFile = new File(core.home(), "state.json");
        state = load();
        // 开机恢复：只起确认过的配置；起来后按开机窗口再验一次直连。核心未跑而记录说启用，就是重启后的恢复。
        if (state.optBoolean("enabled")) {
            if (!state.optBoolean("confirmed")) { state.put("enabled", false).put("last_error", "unconfirmed_at_boot"); save(); }
            else arm(env.now(), BOOT_WINDOW_MS);
        }
    }

    private JSONObject load() {
        try { return stateFile.isFile() ? new JSONObject(RescueFiles.read(stateFile, 16384)) : new JSONObject(); }
        catch (Exception unavailable) { return new JSONObject(); }
    }

    private void save() throws Exception {
        if (!core.home().isDirectory() && !core.home().mkdirs()) throw new IOException("PROXY_HOME_UNAVAILABLE");
        RescueFiles.write(stateFile, state.toString());
    }

    /** 控制口口令：首次需要时生成，随状态文件存核心目录（0700）。 */
    private String secret() throws Exception {
        String secret = state.optString("secret");
        if (!secret.matches("[A-Za-z0-9]{32}")) {
            byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
            secret = hex.toString();
            state.put("secret", secret); save();
        }
        return secret;
    }

    private List<String> apps() {
        JSONArray saved = state.optJSONArray("apps");
        List<String> result = new ArrayList<>();
        if (saved == null) { for (String app : DEFAULT_APPS) result.add(app); return result; }
        for (int i = 0; i < saved.length(); i++) {
            String app = saved.optString(i);
            if (!app.isEmpty() && !management(app)) result.add(app);
        }
        return result;
    }

    /** 上报用：当前生效的分流名单，管理程序已剔除。服务端「缺席保留既有、`[]` 才是清空」。 */
    public synchronized JSONArray appsReport() { return new JSONArray(apps()); }

    synchronized boolean installed() { return core.installed(); }
    synchronized String configVersion() { return state.optBoolean("configured") ? state.optString("config_version") : ""; }
    synchronized String lastError() { return state.optString("last_error"); }

    public synchronized JSONObject status() throws Exception {
        boolean installed = core.installed();
        boolean configured = installed && state.optBoolean("configured") && raw.isFile();
        boolean running = installed && core.running();
        boolean http = running && core.httpReady(), socks = running && core.socksReady();
        boolean reachable = running && http && socks && state.optBoolean("proxy_reachable");
        return ProxyStatus.build(installed ? core.version() : "", installed, installed, configured,
                running, http, socks, reachable, DIRECT, env.now(),
                state.optString("config_version"), state.optString("config_sha256"),
                ProxyStatus.category(state.optString("last_error"), installed, configured));
    }

    /** 给本机界面的完整视图：状态 + 节点 + 选择 + 分流名单 + 流量 + 配置身份。 */
    public synchronized JSONObject overview() throws Exception {
        JSONObject view = new JSONObject().put("status", status()).put("nodes", nodes())
                .put("selected", state.optString("selected_node", ProxyConfig.AUTO))
                .put("apps", new JSONArray(apps()))
                .put("config_version", state.optString("config_version")).put("config_sha256", state.optString("config_sha256"))
                .put("configured_at_ms", state.optLong("configured_at_ms")).put("started_at_ms", state.optLong("started_at_ms"))
                .put("enabled", state.optBoolean("enabled")).put("confirmed", state.optBoolean("confirmed"))
                .put("last_error", state.optString("last_error"));
        if (core.installed() && core.running()) view.put("traffic", core.traffic(secret()));
        return view;
    }

    /** 节点名来自当前节点段；延迟是最近一次真实测速的缓存。 */
    public synchronized JSONArray nodes() throws Exception {
        JSONArray result = new JSONArray();
        if (!raw.isFile()) return result;
        JSONObject cache = state.optJSONObject("delays");
        String selected = state.optString("selected_node", ProxyConfig.AUTO);
        for (String name : names()) {
            JSONObject entry = cache == null ? null : cache.optJSONObject(name);
            result.put(new JSONObject().put("name", name)
                    .put("delay_ms", entry == null ? JSONObject.NULL : entry.opt("delay_ms"))
                    .put("tested_at_ms", entry == null ? 0 : entry.optLong("tested_at_ms"))
                    .put("selected", name.equals(selected)));
        }
        return result;
    }

    /** 上报用的节点列表：`AUTO` 一条 + 节点；≤64 条；有延迟才带测速时刻（服务端硬约束）。 */
    public synchronized JSONArray nodesReport() throws Exception {
        JSONArray result = new JSONArray();
        if (!core.installed() || !raw.isFile()) return result;
        String selected = state.optString("selected_node", ProxyConfig.AUTO);
        result.put(new JSONObject().put("name", ProxyConfig.AUTO).put("delay_ms", JSONObject.NULL).put("tested_at_ms", JSONObject.NULL)
                .put("selected", ProxyConfig.AUTO.equals(selected)).put("kind", "auto"));
        JSONArray nodes = nodes();
        for (int i = 0; i < nodes.length() && result.length() < 64; i++) {
            JSONObject node = nodes.getJSONObject(i);
            boolean tested = !node.isNull("delay_ms");
            result.put(new JSONObject().put("name", node.getString("name")).put("delay_ms", tested ? node.get("delay_ms") : JSONObject.NULL)
                    .put("tested_at_ms", tested ? node.get("tested_at_ms") : JSONObject.NULL)
                    .put("selected", node.optBoolean("selected")).put("kind", "node"));
        }
        return result;
    }

    private List<String> names() throws Exception {
        return ProxyConfig.extract(RescueFiles.read(raw, (int) MAX_CONFIG_BYTES)).names;
    }

    /** 从节点段 + 本机状态生成 `current.yaml`（每次启动都重新生成，口令与分流名单变了也跟着变）。 */
    private void render(boolean tun) throws Exception {
        ProxyConfig.Proxies proxies = ProxyConfig.extract(RescueFiles.read(raw, (int) MAX_CONFIG_BYTES));
        File candidate = new File(configDir, "candidate.yaml");
        try {
            RescueFiles.write(candidate, ProxyConfig.render(proxies, new ProxyConfig.Options(secret(), tun)));
            core.syntax(candidate);
            current.delete();
            if (!candidate.renameTo(current)) throw new IOException("PROXY_CONFIG_COMMIT");
        } finally { candidate.delete(); }
    }

    /**
     * 配置事务。核心不在就先按签名清单拉核心；只取节点段；语法过了再起一次做连通测试，
     * 不通就整体回退到上一份节点段。返回配置后的状态。
     */
    public synchronized JSONObject configure(JSONObject params) throws Exception {
        if (params == null) throw new IOException("PROXY_PARAMS_MISSING");
        String url = ProxyDownload.validate(params.optString("url"), ProxyDownload.CONFIG_PATH);
        long size = params.optLong("size"); String sha = params.optString("sha256"), version = params.optString("version");
        if (size < 2 || size > MAX_CONFIG_BYTES || !sha.matches("[a-f0-9]{64}")) throw new IOException("PROXY_CONFIG_CONTRACT");
        if (version.length() > 64) throw new IOException("PROXY_CONFIG_VERSION_INVALID");
        if (!core.installed()) installCore(params.optJSONObject("core"));
        if (!configDir.isDirectory() && !configDir.mkdirs()) throw new IOException("PROXY_CONFIG_DIR");
        File download = new File(configDir, "download.yaml");
        try {
            fetch.fetch(url, size, sha, download);
            ProxyConfig.Proxies proxies = ProxyConfig.extract(RescueFiles.read(download, (int) MAX_CONFIG_BYTES));
            boolean wasRunning = core.running();
            JSONObject before = new JSONObject(state.toString());
            if (wasRunning) stopCore();
            if (raw.isFile()) { previousRaw.delete(); if (!raw.renameTo(previousRaw)) throw new IOException("PROXY_CONFIG_ROTATE"); }
            if (!download.renameTo(raw)) { restore(before, wasRunning); throw new IOException("PROXY_CONFIG_COMMIT"); }
            String selected = state.optString("selected_node", ProxyConfig.AUTO);
            if (!ProxyConfig.AUTO.equals(selected) && !proxies.names.contains(selected)) state.put("selected_node", ProxyConfig.AUTO);
            state.put("configured", true).put("config_version", version).put("config_sha256", sha).put("config_size", size)
                    .put("configured_at_ms", env.now()).put("bad_config", false).put("last_error", "").put("proxy_reachable", false)
                    .remove("delays");
            save();
            try {
                render(false);
                core.start(current);
                if (!probe()) throw new IOException("PROXY_UNREACHABLE");
                stopCore();
                if (wasRunning) startCore();
            } catch (Exception failure) {
                try { stopCore(); } catch (Exception ignored) { }
                rollback(before, wasRunning);
                throw failure;
            }
            return status();
        } finally { download.delete(); }
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
        try { save(); if (wasRunning && raw.isFile()) startCore(); } catch (Exception ignored) { }
    }

    /** 新节点段换上去了但起不来或不通：换回上一份，状态退回，原来在跑就再起。 */
    private void rollback(JSONObject before, boolean wasRunning) {
        raw.delete();
        if (previousRaw.isFile()) previousRaw.renameTo(raw);
        state = before;
        try {
            if (!raw.isFile()) state.put("configured", false);
            state.put("last_error", "rollback");
            save();
        } catch (Exception ignored) { }
        if (wasRunning && raw.isFile()) try { startCore(); } catch (Exception ignored) { }
    }

    /** 起核心 + TUN + 分流路由 + 应用节点选择。任何一步失败都把进程和规则收干净再抛。 */
    private void startCore() throws Exception {
        render(true);
        try {
            core.start(current);
            Map<String, Integer> resolved = core.uids(apps());
            core.routes(new ArrayList<>(resolved.values()));
            String selected = state.optString("selected_node", ProxyConfig.AUTO);
            if (!ProxyConfig.AUTO.equals(selected)) core.select(secret(), selected);
        } catch (Exception failure) {
            try { stopCore(); } catch (Exception ignored) { }
            throw failure;
        }
    }

    /** 先撤路由再停进程：反过来会有一瞬间规则还在、接口没了。 */
    private void stopCore() throws Exception {
        try { core.routes(new ArrayList<Integer>()); } catch (Exception ignored) { /* 没有 TUN 时撤规则本就无事可做 */ }
        core.stop();
    }

    /** 一次连通测试，结果落盘：状态里的 `proxy_reachable` 只反映最近一次真实测试。 */
    private boolean probe() throws Exception {
        boolean ok = core.running() && core.httpReady() && core.socksReady() && core.reachable();
        state.put("proxy_reachable", ok).put("tested_at_ms", env.now()); save();
        return ok;
    }

    public synchronized JSONObject start() throws Exception {
        if (!core.installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        if (!state.optBoolean("configured") || !raw.isFile()) throw new IOException("PROXY_NOT_CONFIGURED");
        if (!core.running()) startCore();
        boolean ok = probe();
        state.put("enabled", true).put("confirmed", false).put("started_at_ms", env.now()).put("last_error", ok ? "" : "proxy_unreachable");
        restartFailures = 0;
        arm(env.now(), CONFIRM_WINDOW_MS);
        save();
        return status();
    }

    public synchronized JSONObject stop() throws Exception {
        stopCore();
        disarm();
        state.put("enabled", false).put("proxy_reachable", false); save();
        return status();
    }

    public synchronized JSONObject test() throws Exception {
        if (!core.installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        probe();
        return status();
    }

    /** 顺序不能反：先撤路由、停进程，再删目录；目录没了状态也没了，就是「未安装」。 */
    public synchronized JSONObject remove() throws Exception {
        disarm();
        try { core.routes(new ArrayList<Integer>()); } catch (Exception ignored) { }
        core.remove();
        state = new JSONObject();
        return status();
    }

    /** 逐个节点真实测速。核心没在跑就起一个不建 TUN 的临时实例测完再收掉。 */
    public synchronized JSONArray testNodes() throws Exception {
        if (!core.installed()) throw new IOException("PROXY_CORE_NOT_INSTALLED");
        if (!raw.isFile()) throw new IOException("PROXY_NOT_CONFIGURED");
        boolean standby = !core.running();
        if (standby) { render(false); core.start(current); }
        try {
            JSONObject delays = new JSONObject();
            for (String name : names()) {
                int delay = core.delay(secret(), name);
                delays.put(name, new JSONObject().put("delay_ms", delay > 0 ? delay : JSONObject.NULL).put("tested_at_ms", env.now()));
            }
            state.put("delays", delays); save();
        } finally {
            if (standby) { try { stopCore(); } catch (Exception ignored) { } }
        }
        return nodes();
    }

    /** 选节点：`AUTO` 或节点段里的名字；核心在跑就立刻切，否则下次启动生效。返回控制口确认后的当前选择。 */
    public synchronized String selectNode(String name) throws Exception {
        if (!raw.isFile()) throw new IOException("PROXY_NOT_CONFIGURED");
        if (!ProxyConfig.AUTO.equals(name) && !names().contains(name)) throw new IOException("PROXY_NODE_UNKNOWN");
        state.put("selected_node", name); save();
        if (core.installed() && core.running()) {
            core.select(secret(), name);
            String now = core.selected(secret());
            if (!name.equals(now)) throw new IOException("PROXY_SELECT_UNCONFIRMED");
        }
        return name;
    }

    /** 分流名单：服务端或界面给包名列表；管理程序自己永远剔除。核心在跑就立刻重下路由。 */
    public synchronized JSONArray setApps(List<String> packages) throws Exception {
        JSONArray saved = new JSONArray();
        for (String app : packages) if (app.matches("[A-Za-z0-9_.]{1,128}") && !management(app)) saved.put(app);
        state.put("apps", saved); save();
        if (core.installed() && core.running()) core.routes(new ArrayList<>(core.uids(apps()).values()));
        return new JSONArray(apps());
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
                    stopCore();
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
                try { startCore(); probe(); restartFailures = 0; }
                catch (Exception failure) { restartFailures++; }
            }
        } catch (Exception unavailable) { System.err.println("PROXY_WATCHDOG_TICK_FAILED " + unavailable); }
    }

    /** 测试用：当前看门狗是否仍在等直连确认。 */
    synchronized boolean armed() { return probeDue > 0; }
    synchronized boolean enabled() { return state.optBoolean("enabled"); }
    synchronized boolean confirmed() { return state.optBoolean("confirmed"); }
}
