package net.elfradio.d31bootstrap;

import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 首轮远程核心独立运行，不唤醒旧应用接收器或改写本地救援载荷。 */
public final class RemoteDaemon {
    private final File root;
    private final String instance;
    private final RemoteState state;
    private final RollingLog controlLog;
    private final AtomicBoolean wake = new AtomicBoolean(true);
    private final AtomicBoolean syncWake = new AtomicBoolean();
    private final RemotePush push;
    private RemoteTasks tasks;
    private RemoteFileTransfers incoming;
    private final AdbSessions adb;
    private android.net.ConnectivityManager networkManager;
    private android.content.Context systemContext;
    private JSONObject connection;
    private RemoteWorkLoop work;
    private boolean bootReportAcknowledged;
    private volatile boolean reportAcknowledged;
    private final java.util.concurrent.ExecutorService sipReader=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"d31-sip-snapshot");t.setDaemon(true);return t;
    });
    private java.util.concurrent.Future<JSONObject> sipReading;
    private final RemoteSipFollowup sipFollowup=new RemoteSipFollowup();
    private final RemoteTelemetry telemetry = new RemoteTelemetry();
    private volatile RemoteMediaSessions media;
    private volatile RemoteVisualMedia visual;
    private volatile ShareLinkTasks shareLinks;
    private volatile RemoteDesktop desktop;
    private volatile net.elfradio.d31bootstrap.media.AppMediaBridge desktopBridge;
    private volatile String desktopApkHash = "";
    private long desktopInitRetry;
    private volatile ProxyRuntime proxy;
    private volatile ProxyTasks proxyTasks;
    private volatile ProxyLocal proxyLocal;
    private long proxyInitRetry;
    /** 上报因代理字段被服务端 400 拒收后，暂停声明代理能力到这一刻；合同漂移不能把整台设备的上报打死。 */
    private long proxyReportSuppressedUntil;
    private volatile AndroidShareLinkScreen shareLinkScreen;
    /** 墙上时钟用于回执里的时刻，开机时钟用于时限判定；后者不受对时跳变影响。 */
    private static final ShareLinkTasks.Clock SHARE_LINK_CLOCK = new ShareLinkTasks.Clock() {
        public long wall() { return System.currentTimeMillis(); }
        public long elapsed() { return SystemClock.elapsedRealtime(); }
    };
    private volatile RemoteAutomaticPhotos automaticPhotos;
    private long automaticPhotoInitRetry;
    private String mediaStatus = "";
    private String locationStatus = "";
    private RemoteFaultRuntime faults;
    private volatile boolean managementReady;
    private final java.util.concurrent.ExecutorService managementReader = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "d31-management-readiness"); thread.setDaemon(true); return thread;
    });
    private java.util.concurrent.Future<Boolean> managementReading;
    private long managementStarted;
    private long managementRetry;
    private long videoConfigCheckAt;

    private RemoteDaemon(File root, String instance) throws Exception {
        this.root = root; this.instance = instance;
        controlLog = new RollingLog(new File(root, "control-log"), 32768, 2);
        state = new RemoteState(root); push = new RemotePush(state, () -> { syncWake.set(true); wake.set(true); }, controlLog);
        adb = new AdbSessions(root);
    }

    public static void main(String[] args) throws Exception {
        if (Os.getuid() != 0 || (args.length != 2 && args.length != 3) || !args[0].startsWith("/data/local/d31-remote/"))
            throw new IllegalArgumentException("需要独立root目录及once或run参数");
        if (!"once".equals(args[1]) && !"run".equals(args[1])) throw new IllegalArgumentException("运行模式无效");
        if (Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(Build.DEVICE)
                || !"hct6737t_66_m0".equals(Build.MODEL))
            throw new IllegalStateException("非已验证D31构建");
        File root = new File(args[0]);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("运行目录不可用");
        Os.chmod(root.getPath(), 0700);
        try (RandomAccessFile file = new RandomAccessFile(new File(root, "remote.lock"), "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) return;
            RescueFiles.write(new File(root, "remote.pid"), String.valueOf(Os.getpid()));
            String instance = args.length == 3 ? args[2] : UUID.randomUUID().toString();
            RemoteDaemon daemon = new RemoteDaemon(root, instance);
            String apkHash = RescueFiles.sha256(new File(System.getenv("CLASSPATH")));
            java.util.concurrent.ScheduledExecutorService health = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            health.scheduleWithFixedDelay(() -> daemon.health(instance, apkHash), 0, 5, java.util.concurrent.TimeUnit.SECONDS);
            java.util.concurrent.ScheduledExecutorService business = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "d31-business-tick"); thread.setDaemon(true); return thread;
            });
            business.scheduleWithFixedDelay(daemon::tickBusiness, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
            try {
                daemon.faults = new RemoteFaultRuntime(RemoteDaemon::bootComplete);
                boolean cloudStopped = false;
                do {
                    if (cloudStopped) { Thread.sleep(1000); continue; }
                    try { daemon.tick(); }
                    catch (RemoteHttp.Rejected rejected) {
                        daemon.status("cloud_rejected", rejected.status, rejected.reason);
                        if (rejected.pairingRequired) {
                            // 后端已不认识当前设备编号（例如后端迁移）：清空编号后退出，由守护重启核心并凭原令牌重新注册。
                            daemon.state.put("device_id", "");
                            new File(root, "pending-report.json").delete();
                            daemon.status("pairing_required", rejected.status, "identity reset");
                            break;
                        }
                        // 身份拒绝停止云请求，保留本地健康和取证，修正身份后由现有入口重启核心。
                        if (rejected.status >= 400 && rejected.status < 500 && rejected.status != 429) cloudStopped = true;
                        if ("run".equals(args[1])) daemon.pauseRetry();
                    } catch (Exception error) {
                        daemon.status("retry_pending", 0, error.getClass().getSimpleName());
                        if ("run".equals(args[1])) daemon.pauseRetry();
                    }
                    if ("once".equals(args[1])) break;
                    Thread.sleep(1000);
                } while (!new File(root, "stop").exists());
            } finally {
                business.shutdownNow();
                if (daemon.faults != null) daemon.faults.close();
                daemon.telemetry.close();
                if (daemon.media != null) daemon.media.close();
                if (daemon.visual != null) daemon.visual.close();
                if (daemon.desktop != null) daemon.desktop.close();
                if (daemon.desktopBridge != null) daemon.desktopBridge.close();
                if (daemon.automaticPhotos != null) daemon.automaticPhotos.close();
                daemon.sipReader.shutdownNow();
                daemon.managementReader.shutdownNow();
                if(daemon.incoming!=null)daemon.incoming.close();
                daemon.adb.close();
                health.shutdownNow(); daemon.push.close(); new File(root, "remote.pid").delete();
            }
        }
        System.exit(0);
    }

    private JSONObject credentials() throws Exception {
        JSONObject saved = state.snapshot();
        return new JSONObject().put("token", saved.getString("token")).put("device_id", saved.getString("device_id"));
    }

    private void tick() throws Exception {
        JSONObject saved = state.snapshot();
        if (!saved.has("hardware_identity")) {
            JSONObject identity = RemoteProtocol.ethernetIdentity(
                    RescueFiles.read(new File("/sys/class/net/eth0/address"), 64),
                    RescueFiles.read(new File("/sys/class/net/eth0/addr_assign_type"), 16));
            state.put("hardware_identity", identity); saved = state.snapshot();
        }
        if (saved.optString("device_id").isEmpty()) {
            JSONObject enroll = new JSONObject().put("token", saved.getString("token"))
                    .put("token_sha256", RemoteProtocol.hash(saved.getString("token")))
                    .put("device_name", deviceName()).put("model_hint", "D31")
                    .put("app_version", BuildConfig.VERSION_NAME).put("os_version", Build.VERSION.RELEASE)
                    .put("hardware_identity", saved.getJSONObject("hardware_identity"));
            JSONObject reply = RemoteHttp.cloud("/api/devices/enroll", enroll);
            if (!reply.optString("device_id").matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("未获得设备身份");
            state.put("device_id", reply.getString("device_id"));
            state.put("enrollment", reply);
            status("enrolled", 200, "");
        }
        if (tasks == null) tasks = new RemoteTasks(root, state.snapshot().getString("device_id"), new RemoteTasks.Transport() {
            public JSONObject local(String path, JSONObject body) throws Exception { return RemoteHttp.local(path, body); }
            public JSONObject progress(JSONObject body) throws Exception {
                body.put("token", state.snapshot().getString("token"));
                JSONObject reply=RemoteHttp.cloud("/api/elfremote/task-progress", body);
                sipFollowup.completed(body,SystemClock.elapsedRealtime());
                return reply;
            }
        });
        if (work == null) work = new RemoteWorkLoop(SystemClock::elapsedRealtime, new RemoteWorkLoop.Actions() {
            public long run(RemoteWorkLoop.Stage stage) throws Exception { return runWork(stage); }
            public boolean stopping() { return RemoteDaemon.this.stopping(); }
            public void changed() throws Exception {
                JSONObject stages = work.snapshot();
                RescueFiles.write(new File(root, "work-status.json"), new JSONObject()
                        .put("time_ms", System.currentTimeMillis()).put("version", BuildConfig.VERSION_NAME)
                        .put("mqtt", push.connected()).put("stages", stages).toString());
                JSONObject summary = RemoteWorkLoop.summary(stages, reportAcknowledged, push.connected());
                status(summary.getString("phase"), summary.getInt("http_status"), summary.getString("detail"));
            }
        });
        if (bootComplete()) {
            if (SystemClock.elapsedRealtime() >= videoConfigCheckAt) {
                // 原厂视频关键帧请求开关兜底；失败5分钟后重试，成功每小时复查。
                try {
                    JSONObject video = VendorVideoConfig.ensure();
                    RescueFiles.write(new File(root, "video-config.json"), video.put("time_ms", System.currentTimeMillis()).toString());
                    controlLog.write(System.currentTimeMillis() + " VIDEO_CONFIG " + video);
                    videoConfigCheckAt = SystemClock.elapsedRealtime() + 3600000;
                } catch (Exception error) {
                    RescueFiles.write(new File(root, "video-config.json"), new JSONObject()
                            .put("error", error.getClass().getSimpleName()).put("time_ms", System.currentTimeMillis()).toString());
                    videoConfigCheckAt = SystemClock.elapsedRealtime() + 300000;
                }
            }
            telemetry.enableLocation(context(), () -> wake.set(true));
            if (media == null) media = new RemoteMediaSessions(context(), root, System.getenv("CLASSPATH"), () -> wake.set(true));
            if (visual == null) visual = new RemoteVisualMedia(context(), root, System.getenv("CLASSPATH"), () -> wake.set(true));
            if (desktop == null && DesktopAsset.packaged() && SystemClock.elapsedRealtime() >= desktopInitRetry) {
                desktopInitRetry = SystemClock.elapsedRealtime() + 60000;
                try {
                    // 资产每次都校验哈希，已就绪时 install 直接返回，不重复写盘。
                    DesktopAsset.install();
                    if (desktopApkHash.isEmpty()) desktopApkHash = RescueFiles.sha256(new File(System.getenv("CLASSPATH")));
                    desktopBridge = new net.elfradio.d31bootstrap.media.AppMediaBridge(context());
                    desktop = new RemoteDesktop(this::desktopRequest, new DesktopLauncher(root),
                            SystemClock::elapsedRealtime, desktopApkHash);
                } catch (Exception unavailable) { System.err.println("DESKTOP_INITIALIZATION_FAILED " + unavailable); }
            }
            if (shareLinks == null && ShareLinkAvailability.packaged()) {
                // 自带一条桥：RemoteVisualMedia 那条是私有的，而这条桥本身每次请求都重新握手，不持状态。
                net.elfradio.d31bootstrap.media.PhotoAlarmBridge shareLinkBridge =
                        new net.elfradio.d31bootstrap.media.PhotoAlarmBridge(context());
                shareLinkScreen = new AndroidShareLinkScreen(shareLinkBridge::request, SHARE_LINK_CLOCK);
                shareLinks = new ShareLinkTasks(root, state.snapshot().getString("device_id"), shareLinkScreen,
                        this::shareLinkProgress,
                        // 警报是找设备用的，优先级高于二维码：媒体或照片会话占着就不显示。
                        () -> (visual != null && visual.active()) || (media != null && media.active()),
                        SHARE_LINK_CLOCK);
            }
            if (proxy == null && SystemClock.elapsedRealtime() >= proxyInitRetry) {
                proxyInitRetry = SystemClock.elapsedRealtime() + 60000;
                try {
                    // 「无网络 ≠ 直连被墙」：看门狗只在活动网络存在时探测与计数，新机开箱没配网时什么都不做。
                    ProxyRuntime.Environment environment = new ProxyRuntime.Environment() {
                        public boolean online() { return !"offline".equals(network()); }
                        public boolean managementReachable() { return ProxyCore.managementReachable(); }
                        public long now() { return System.currentTimeMillis(); }
                    };
                    ProxyRuntime runtime = new ProxyRuntime(new ProxyCore(ProxyCore.HOME), ProxyDownload::fetch,
                            environment, RemoteUpdatePolicy.trustedKey());
                    proxyTasks = new ProxyTasks(root, state.snapshot().getString("device_id"), runtime,
                            this::shareLinkProgress, System::currentTimeMillis);
                    // 本机按钮取配置：与 configure_proxy 参数同形，由服务端按本设备已上传的配置铸一次性下载口。
                    proxyLocal = new ProxyLocal(root, runtime, requestId -> RemoteHttp.cloud(ProxyLocal.OFFER_PATH,
                            credentials().put("request_id", requestId)).getJSONObject("params"));
                    proxy = runtime;
                } catch (Exception unavailable) { System.err.println("PROXY_INITIALIZATION_FAILED " + unavailable); }
            }
            if (automaticPhotos == null && SystemClock.elapsedRealtime()>=automaticPhotoInitRetry) {
                automaticPhotoInitRetry=SystemClock.elapsedRealtime()+60000;
                try{automaticPhotos = new RemoteAutomaticPhotos(context(),root,System.getenv("CLASSPATH"),this::credentials,
                        () -> stopping() || (media!=null&&media.active()) || (visual!=null&&visual.active()));}
                catch(Exception unavailable){System.err.println("AUTO_PHOTO_INITIALIZATION_FAILED");}
            }
        }
        boolean signalled = wake.getAndSet(false);
        if(automaticPhotos!=null&&automaticPhotos.reportDue())work.request(RemoteWorkLoop.Stage.REPORT);
        if (syncWake.getAndSet(false)) work.request(RemoteWorkLoop.Stage.SYNC);
        if(sipFollowup.due(SystemClock.elapsedRealtime()))work.request(RemoteWorkLoop.Stage.REPORT);
        if (stopping()) return;
        boolean bootComplete = bootReportAcknowledged || bootComplete();
        if (signalled || (bootComplete && !bootReportAcknowledged)
                || new File(root, "pending-report.json").isFile()) {
            work.request(RemoteWorkLoop.Stage.REPORT);
        }
        work.tick();
    }

    /** 会话续租不等待HTTP报告、文件传输或其它管理任务。 */
    private void tickBusiness() {
        try {
            if (stopping()) return;
            telemetry.tickLocation();
            String location = telemetry.locationSnapshot().toString();
            if (!location.equals(locationStatus)) {
                RescueFiles.write(new File(root, "location-status.json"), location);
                locationStatus = location;
            }
            RemoteMediaSessions microphone = media;
            RemoteVisualMedia pictures = visual;
            if (microphone != null) microphone.tick();
            if (pictures != null) pictures.tick();
            if (microphone == null || pictures == null) return;
            String latest = new JSONObject().put("microphone", microphone.snapshot()).put("visual", pictures.snapshot()).toString();
            if (!latest.equals(mediaStatus)) {
                RescueFiles.write(new File(root, "media-status.json"), latest);
                mediaStatus = latest;
            }
        } catch (Exception unavailable) { System.err.println("业务状态读取暂时失败"); }
    }

    private long runWork(RemoteWorkLoop.Stage stage) throws Exception {
        if (stage == RemoteWorkLoop.Stage.TASKS) { tasks.resume(); driveShareLinks(); driveDesktop(); driveProxy(); return 5000; }
        if(stage==RemoteWorkLoop.Stage.FILES) {
            if(incoming==null){network();incoming=new RemoteFileTransfers(root,state.snapshot().getString("device_id"),
                    state.snapshot().getString("token"),body->{
                        body.put("token",state.snapshot().getString("token"));
                        return RemoteHttp.cloud("/api/elfremote/task-progress",body);
                    },this::network);}
            incoming.tick();return 5000;
        }
        if (stage == RemoteWorkLoop.Stage.REPORT) {
            // 旧待报告先按原身份完成，再补本次启动的名称与版本，保留去重依据。
            boolean pending = new File(root, "pending-report.json").isFile();
            boolean bootComplete = bootReportAcknowledged || bootComplete();
            try { report(); }
            catch (RemoteTelemetry.PreparationPending preparing) { return 1000; }
            if (bootComplete && !pending) bootReportAcknowledged = true;
            return 900000;
        }
        if (stage == RemoteWorkLoop.Stage.PUSH) {
            try {
                if (connection == null) connection = RemoteHttp.cloud("/api/devices/push-config", credentials()).getJSONObject("connection");
                if (push.ensure(connection, SystemClock.elapsedRealtime())) {
                    work.request(RemoteWorkLoop.Stage.SYNC); status("mqtt_connected", 200, "");
                }
            } catch (Exception error) { connection = null; throw error; }
            return 5000;
        }
        if (stage == RemoteWorkLoop.Stage.SYNC) {
            JSONObject body = credentials(); JSONObject notice = state.snapshot().optJSONObject("notice");
            if (notice != null) body.put("received_request_id", notice.getString("request_id"))
                    .put("received_version", notice.getLong("version"));
            controlLog.write(System.currentTimeMillis()+" SYNC_BEGIN");
            JSONObject reply;
            try { reply = RemoteHttp.cloud("/api/devices/push-sync", body); }
            catch(Exception error) {
                controlLog.write(System.currentTimeMillis()+" SYNC_FAILED "+error.getClass().getSimpleName());
                throw error;
            }
            controlLog.write(System.currentTimeMillis()+" SYNC_RECEIVED");
            consumeAdb(reply.optJSONObject("adb_session"));
            consumeMedia(reply.optJSONObject("media_session"));
            consumeDesktop(reply.optJSONObject("desktop_session"));
            state.notice(reply.optJSONObject("status_request"), System.currentTimeMillis());
            if (state.snapshot().has("notice")) wake.set(true);
            return push.connected() ? 900000 : 60000;
        }
        throw new IllegalArgumentException("未知远程工作项");
    }

    private void report() throws Exception {
        File file = new File(root, "pending-report.json");
        JSONObject body;
        if (file.isFile()) body = new JSONObject(RescueFiles.read(file, 64000));
        else {
            boolean ready = false;
            try { ready = RemoteHttp.local("/health", null).optInt("uid", -1) == 0; } catch (Exception ignored) { }
            body = credentials().put("report_id", UUID.randomUUID().toString())
                    .put("_core_instance", instance)
                    .put("reported_at", RemoteProtocol.utc(System.currentTimeMillis())).put("status_only", true)
                    .put("device_name", deviceName()).put("app_version", BuildConfig.VERSION_NAME)
                    .put("os_version", Build.VERSION.RELEASE).put("network", network()).put("ready", ready)
                    .put("managed_exec_tasks", ready).put("managed_file_operations", ready).put("managed_file_tasks",incoming!=null)
                    .put("managed_file_return",incoming!=null)
                    .put("managed_file_delete",ready)
                    .put("managed_adb_session",true)
                    .put("managed_adbd_tasks",ready)
                    .put("managed_system_settings", false)
                    .put("managed_contacts_page_v1", false)
                    .put("managed_network_confirmation_v1", ready)
                    .put("managed_share_link_tasks", ready && shareLinks != null)
                    .put(DesktopOffer.CAPABILITY, ready && desktop != null && RemoteDesktop.available())
                    .put("managed_proxy_tasks", ready && proxy != null && proxyTasks != null
                            && SystemClock.elapsedRealtime() >= proxyReportSuppressedUntil)
                    .put("network_write", false)
                    .put("maintenance", new JSONObject().put("ready", ready)
                            .put("state", ready ? "ready" : "unavailable"))
                    .put("hardware_identity", state.snapshot().getJSONObject("hardware_identity"));
            RemoteMediaReport.merge(body, media == null ? null : media.snapshot(), visual == null ? null : visual.snapshot());
            if (body.optBoolean("managed_proxy_tasks")) {
                // 声明了能力位就必须带运行状态；状态取不到宁可这一轮不声明，也不发半截。
                try {
                    body.put("proxy_runtime", proxy.status()).put("proxy_nodes", proxy.nodesReport())
                            .put("proxy_apps", proxy.appsReport());
                    // 已装应用清单只在变化时带：服务端「字段缺席 = 保留既有」，不是清空。
                    org.json.JSONArray apps = installedApps();
                    String digest = RemoteProtocol.hash(apps.toString());
                    if (!digest.equals(installedAppsAcknowledged)) { body.put("installed_apps", apps); body.put("_installed_apps_digest", digest); }
                } catch (Exception unavailable) {
                    body.put("managed_proxy_tasks", false); body.remove("proxy_runtime"); body.remove("proxy_nodes");
                    body.remove("proxy_apps"); body.remove("installed_apps"); body.remove("_installed_apps_digest");
                }
            }
            if (RemoteUpdates.ready()) body.put("managed_update", true).put("managed_update_v2", true);
            if(bootComplete()){
                if (managementReading != null && managementReading.isDone()) {
                    try { managementReady = managementReading.get(); }
                    catch (Exception unavailable) { managementReady = false; }
                    managementReading = null;
                    managementRetry = SystemClock.elapsedRealtime() + 60000;
                }
                if (!managementReady && managementReading == null && SystemClock.elapsedRealtime() >= managementRetry) {
                    managementStarted = SystemClock.elapsedRealtime();
                    managementReading = managementReader.submit(
                            () -> net.elfradio.d31bootstrap.management.SystemManagement.readiness(context()).optBoolean("ready"));
                }
                body = telemetry.enrich(body, () -> {
                    return new net.elfradio.d31bootstrap.telemetry.TelemetryCollector(
                        new net.elfradio.d31bootstrap.telemetry.AndroidTelemetryAccess(context()),
                        net.elfradio.d31bootstrap.telemetry.AndroidTelemetryAccess.clock())
                        .collect(new net.elfradio.d31bootstrap.telemetry.TelemetryCollector.Limits(0, 300000));
                }, 250);
                if (!managementReady && managementReading != null && !managementReading.isDone()
                        && SystemClock.elapsedRealtime() - managementStarted < 5000)
                    throw new RemoteTelemetry.PreparationPending();
                body.put("managed_system_settings", ready && managementReady);
                body.put("managed_contacts_page_v1", ready && managementReady);
                try{
                    if(sipReading==null)sipReading=sipReader.submit(()->RemoteSip.snapshot(context()));
                    JSONObject sip=sipReading.get(5,java.util.concurrent.TimeUnit.SECONDS);sipReading=null;
                    sipFollowup.observed(sip);
                    body.put("managed_sip_account",sip.getBoolean("managed_sip_account"))
                            .put("sip_targets",sip.getJSONArray("sip_targets")).put("sip_registrations",sip.getJSONArray("sip_registrations"));
                }catch(Exception unavailable){
                    if(sipReading!=null&&sipReading.isDone())sipReading=null;
                    body.put("managed_sip_account",false).put("sip_targets",new org.json.JSONArray());
                }
            }
            JSONObject notice = state.snapshot().optJSONObject("notice");
            if (notice != null && notice.optLong("expires_at_ms") > System.currentTimeMillis())
                body.put("status_request_id", notice.getString("request_id")).put("_notice_version", notice.getLong("version"));
            RescueFiles.write(file, body.toString());
        }
        File responseFile = new File(root, "report-response.json");
        JSONObject reply = responseFile.isFile() ? new JSONObject(RescueFiles.read(responseFile, 600000)) : null;
        if (reply == null || !body.getString("report_id").equals(reply.optString("report_id"))) {
            try { reply = RemoteHttp.cloud("/api/devices/report", RemoteReportReceipt.wire(body)); }
            catch (RemoteHttp.Rejected rejected) {
                // 2026-09-20 实测：服务端一处未放开的型号判定把带代理字段的上报整条 400，设备随之失联。
                // 400 且本轮带了代理字段：改写待报告去掉代理字段，一小时内不再声明，其余能力照常上报。
                if (rejected.status == 400 && body.optBoolean("managed_proxy_tasks")) {
                    body.put("managed_proxy_tasks", false); body.remove("proxy_runtime");
                    body.remove("proxy_nodes"); body.remove("proxy_apps");
                    body.remove("installed_apps"); body.remove("_installed_apps_digest");
                    RescueFiles.write(file, body.toString());
                    proxyReportSuppressedUntil = SystemClock.elapsedRealtime() + 3600000;
                    controlLog.write(System.currentTimeMillis() + " PROXY_REPORT_SUPPRESSED http=400 " + rejected.reason);
                }
                throw rejected;
            }
        }
        if (!body.getString("report_id").equals(reply.optString("report_id"))) throw new IOException("状态报告未确认");
        // 先持久化服务端回复，避免确认报告后退出而丢失任务。
        RescueFiles.write(responseFile, reply.toString());
        if(automaticPhotos!=null)automaticPhotos.acknowledged(body,reply);
        consumeReport(body, reply);
        if (!file.delete()) throw new IOException("报告确认无法落盘");
        status("report_acknowledged", 200, "");
        if (RemoteReportReceipt.current(body, reply, BuildConfig.VERSION_NAME, instance)) reportAcknowledged = true;
        else work.request(RemoteWorkLoop.Stage.REPORT);
    }

    /**
     * 先取窗口结果再扫时限：取到了就能按真实结果结清，顺序反了会把刚结束的显示误判成超时。
     * tick 还兼管未被服务端采纳的终态回执补发，所以即使没有窗口在显示也要照常调。
     */
    /**
     * 桥是回调式的，这里包成同步调用给 RemoteDesktop 用。
     * 8秒上限：桥自己是6秒时限，留一点余量，超了就当这一轮取不到，下一轮再来。
     */
    private JSONObject desktopRequest(JSONObject command) throws Exception {
        net.elfradio.d31bootstrap.media.AppMediaBridge bridge = desktopBridge;
        if (bridge == null) throw new java.io.IOException("DESKTOP_BRIDGE_UNAVAILABLE");
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<JSONObject> result = new java.util.concurrent.atomic.AtomicReference<JSONObject>();
        final java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<String>();
        bridge.desktop(command, new net.elfradio.d31bootstrap.media.AppMediaBridge.Callback() {
            public void completed(JSONObject value) { result.set(value); done.countDown(); }
            public void failed(String code) { failure.set(code); done.countDown(); }
        });
        if (!done.await(8, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("DESKTOP_BRIDGE_TIMEOUT");
        if (failure.get() != null) throw new java.io.IOException(failure.get());
        return result.get();
    }

    /** 远程桌面邀约每轮上报都会带下来；重复的同一场会话由 RemoteDesktop 自己忽略。 */
    private void consumeDesktop(JSONObject offer) {
        RemoteDesktop current = desktop;
        if (offer == null || current == null) return;
        try { current.accept(offer); }
        catch (Exception refused) { System.err.println("DESKTOP_OFFER_REFUSED " + refused.getMessage()); }
    }

    private void driveDesktop() {
        RemoteDesktop current = desktop;
        if (current != null) current.pump();
    }

    private void driveProxy() {
        ProxyTasks handler = proxyTasks;
        ProxyLocal local = proxyLocal;
        ProxyRuntime runtime = proxy;
        if (handler != null) handler.tick();
        if (local != null) local.tick();
        if (runtime != null) runtime.tick();
    }

    /** 代理模块没初始化却收到代理任务：明确拒收，不能静默丢掉让服务端一直等。 */
    private void proxyUnavailable(JSONObject task) throws Exception {
        shareLinkProgress(new JSONObject()
                .put("device_id", state.snapshot().getString("device_id"))
                .put("task_id", task.optString("id")).put("state", "rejected")
                .put("detail", "本机代理模块未初始化").put("result", JSONObject.NULL));
    }

    private void driveShareLinks() {
        AndroidShareLinkScreen screen = shareLinkScreen;
        ShareLinkTasks links = shareLinks;
        if (screen != null) screen.pump();
        if (links != null) links.tick();
    }

    /**
     * 发二维码任务回执，返回服务端回读的任务状态。
     * 不能用 ok 判定采纳：服务端对不在迁移表里的回执是静默丢弃，仍回200和ok，只是状态原地不动。
     */
    private String shareLinkProgress(JSONObject receipt) throws Exception {
        receipt.put("token", state.snapshot().getString("token"));
        JSONObject reply = RemoteHttp.cloud("/api/elfremote/task-progress", receipt);
        JSONObject task = reply.optJSONObject("task");
        if (task == null || !receipt.getString("task_id").equals(task.optString("id")))
            throw new java.io.IOException("SHARE_LINK_PROGRESS_UNCONFIRMED");
        return task.optString("state");
    }

    /** 基础版收到二维码任务：明确拒收，不能静默丢掉让服务端一直等。 */
    private void shareLinkUnavailable(JSONObject task) throws Exception {
        shareLinkProgress(new JSONObject()
                .put("device_id", state.snapshot().getString("device_id"))
                .put("task_id", task.optString("id")).put("state", "rejected")
                .put("detail", "本机固件不含二维码窗口")
                .put("result", ShareLinkTasks.result(ShareLinkOutcome.FAILED, 0,
                        System.currentTimeMillis(), "window_not_packaged", "")));
    }

    /** 可启动的应用（有桌面入口的），供面板勾选「哪些程序走代理」；管理程序自己永不出现；≤64 条。 */
    private org.json.JSONArray installedApps() throws Exception {
        android.content.pm.PackageManager pm = context().getPackageManager();
        android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.TreeMap<String, JSONObject> found = new java.util.TreeMap<>();
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            // 只排除管理程序自己；同命名空间的 Zello 守护、SIP 短信客户端都是普通应用，面板要能勾。
            if (ProxyRuntime.management(pkg) || found.containsKey(pkg) || !pkg.matches("[A-Za-z0-9_.]{1,128}")) continue;
            String label = String.valueOf(info.loadLabel(pm)).replaceAll("[\\x00-\\x1f]", "").trim();
            if (label.isEmpty()) label = pkg;
            if (label.length() > 64) label = label.substring(0, 64);
            boolean system = (info.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
            found.put(pkg, new JSONObject().put("package", pkg).put("label", label).put("system", system));
        }
        org.json.JSONArray result = new org.json.JSONArray();
        for (JSONObject entry : found.values()) { if (result.length() >= 64) break; result.put(entry); }
        return result;
    }
    private String installedAppsAcknowledged = "";

    private void consumeReport(JSONObject body, JSONObject reply) throws Exception {
        if (body.has("_installed_apps_digest")) installedAppsAcknowledged = body.getString("_installed_apps_digest");
        state.acknowledge(body);
        state.notice(reply.optJSONObject("status_request"), System.currentTimeMillis());
        JSONObject notice = state.snapshot().optJSONObject("notice");
        if (notice != null && notice.optLong("expires_at_ms") > System.currentTimeMillis()) wake.set(true);
        consumeAdb(reply.optJSONObject("adb_session"));
        JSONObject task = reply.optJSONObject("managed_task");
        if(task!=null) {
            if("restart_adbd".equals(task.optString("type")))adb.close();
            if("send_file".equals(task.optString("type"))||"get_file".equals(task.optString("type")))incoming.accept(task,System.currentTimeMillis());
            else if(ShareLinkPayload.TYPE.equals(task.optString("type"))) {
                // 完整版才有窗口；基础版能力位是false，真派下来也只能拒收，不能装作收下了。
                if(shareLinks!=null)shareLinks.accept(task);
                else shareLinkUnavailable(task);
            }
            else if(ProxyTasks.supports(task.optString("type"))) {
                ProxyTasks handler = proxyTasks;
                if(handler!=null)handler.accept(task);
                else proxyUnavailable(task);
            }
            else tasks.accept(task,System.currentTimeMillis());
        }
        JSONObject update = reply.optJSONObject("managed_update");
        if (update != null) RemoteUpdates.enqueue(update);
        consumeMedia(reply.optJSONObject("media_session"));
        consumeDesktop(reply.optJSONObject("desktop_session"));
    }

    private void consumeAdb(JSONObject session) throws Exception {
        if(session!=null) {
            controlLog.write(System.currentTimeMillis()+" ADB_OFFER_RECEIVED");
            try { adb.open(session); }
            catch(Exception rejected) {
                try { status("adb_session_rejected",0,rejected.getClass().getSimpleName()); }
                catch(Exception unavailable) {
                    controlLog.write(System.currentTimeMillis()+" ADB_REJECTION_STATUS_FAILED "+unavailable.getClass().getSimpleName());
                }
            }
        }
    }

    private void consumeMedia(JSONObject mediaOffer) throws Exception {
        if (mediaOffer != null && media != null && visual != null) {
            // 两种APP服务共享一个设备会话；迟到的异类offer不能抢占正在使用的资源。
            String mode = mediaOffer.optString("mode");
            if ("prepare".equals(mode) && !visual.active()) media.accept(mediaOffer, credentials());
            else if (("microphone".equals(mode)||"video".equals(mode)||"ptt".equals(mode)||"call".equals(mode)) && !visual.active()) media.accept(mediaOffer);
            else if (("photo".equals(mode) || "alarm".equals(mode)) && !media.active())
                visual.accept(mediaOffer, credentials());
        }
    }

    private String network() {
        try {
            if (networkManager == null) {
                networkManager = (android.net.ConnectivityManager) context().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            }
            return RemoteFileNetwork.read(networkManager);
        } catch (Exception unavailable) { }
        return "unknown";
    }

    private synchronized android.content.Context context() throws Exception {
        if (systemContext == null) {
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            systemContext = (android.content.Context) activityThread.getMethod("getSystemContext").invoke(thread);
        }
        return systemContext;
    }

    private String deviceName() {
        return RemoteDeviceName.read(RemoteDeviceName::setting, Build.MODEL);
    }

    private boolean stopping() { return new File(root, "stop").exists(); }
    private void pauseRetry() throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + 30000;
        while (!stopping() && SystemClock.elapsedRealtime() < until) Thread.sleep(200);
    }

    private void health(String instance, String apkHash) {
        try {
            JSONObject rescue = RemoteHttp.local("/health", null);
            RescueFiles.write(new File(root, "health.json"), new JSONObject().put("instance", instance)
                    .put("version_code", BuildConfig.VERSION_CODE).put("apk_sha256", apkHash)
                    .put("maintenance_protocol", RemoteMaintenance.PROTOCOL).put("pid", Os.getpid())
                    .put("uid", Os.getuid()).put("time_ms", System.currentTimeMillis())
                    .put("local_ready", rescue != null && rescue.optInt("uid", -1) == 0)
                    .put("report_acknowledged", reportAcknowledged).toString());
        } catch (Exception unavailable) { }
    }

    private static boolean bootComplete() {
        try {
            return "1".equals(Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, "sys.boot_completed"));
        } catch (Exception unavailable) { return false; }
    }

    private void status(String phase, int http, String reason) throws Exception {
        // 固定覆盖小状态文件；不记录令牌、设备地址或命令输出。
        JSONObject status = new JSONObject().put("time", RemoteProtocol.utc(System.currentTimeMillis()))
                .put("phase", phase).put("http_status", http).put("detail", reason)
                .put("version", BuildConfig.VERSION_NAME).put("uid", Os.getuid()).put("mqtt", push.connected());
        RescueFiles.write(new File(root, "status.json"), status.toString());
        System.out.println(status);
    }

}
