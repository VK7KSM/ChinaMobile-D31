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
    private final AtomicBoolean wake = new AtomicBoolean(true);
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

    private RemoteDaemon(File root, String instance) throws Exception {
        this.root = root; this.instance = instance;
        state = new RemoteState(root); push = new RemotePush(state, () -> wake.set(true));
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
            try {
                do {
                    try { daemon.tick(); }
                    catch (RemoteHttp.Rejected rejected) {
                        daemon.status("cloud_rejected", rejected.status, rejected.reason);
                        // 配置或身份拒绝立即结束，不反复创建注册请求。
                        if (rejected.status >= 400 && rejected.status < 500 && rejected.status != 429) break;
                        if ("run".equals(args[1])) daemon.pauseRetry();
                    } catch (Exception error) {
                        daemon.status("retry_pending", 0, error.getClass().getSimpleName());
                        if ("run".equals(args[1])) daemon.pauseRetry();
                    }
                    if ("once".equals(args[1])) break;
                    Thread.sleep(1000);
                } while (!new File(root, "stop").exists());
            } finally {
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
        boolean signalled = wake.getAndSet(false);
        if(sipFollowup.due(SystemClock.elapsedRealtime()))work.request(RemoteWorkLoop.Stage.REPORT);
        if (stopping()) return;
        boolean bootComplete = bootReportAcknowledged || bootComplete();
        if (signalled || (bootComplete && !bootReportAcknowledged)
                || new File(root, "pending-report.json").isFile()) {
            work.request(RemoteWorkLoop.Stage.REPORT);
        }
        work.tick();
    }

    private long runWork(RemoteWorkLoop.Stage stage) throws Exception {
        if (stage == RemoteWorkLoop.Stage.TASKS) { tasks.resume(); return 5000; }
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
            report();
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
            JSONObject reply = RemoteHttp.cloud("/api/devices/push-sync", body);
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
                    .put("maintenance", new JSONObject().put("ready", ready)
                            .put("state", ready ? "ready" : "unavailable"))
                    .put("hardware_identity", state.snapshot().getJSONObject("hardware_identity"));
            if (RemoteUpdates.ready()) body.put("managed_update", true).put("managed_update_v2", true);
            if(bootComplete()){
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
            reply = RemoteHttp.cloud("/api/devices/report", RemoteReportReceipt.wire(body));
        }
        if (!body.getString("report_id").equals(reply.optString("report_id"))) throw new IOException("状态报告未确认");
        // 先持久化服务端回复，避免确认报告后退出而丢失任务。
        RescueFiles.write(responseFile, reply.toString());
        consumeReport(body, reply);
        if (!file.delete()) throw new IOException("报告确认无法落盘");
        status("report_acknowledged", 200, "");
        if (RemoteReportReceipt.current(body, reply, BuildConfig.VERSION_NAME, instance)) reportAcknowledged = true;
        else work.request(RemoteWorkLoop.Stage.REPORT);
    }

    private void consumeReport(JSONObject body, JSONObject reply) throws Exception {
        state.acknowledge(body);
        state.notice(reply.optJSONObject("status_request"), System.currentTimeMillis());
        JSONObject notice = state.snapshot().optJSONObject("notice");
        if (notice != null && notice.optLong("expires_at_ms") > System.currentTimeMillis()) wake.set(true);
        JSONObject task = reply.optJSONObject("managed_task");
        if(task!=null) {
            if("restart_adbd".equals(task.optString("type")))adb.close();
            if("send_file".equals(task.optString("type"))||"get_file".equals(task.optString("type")))incoming.accept(task,System.currentTimeMillis());
            else tasks.accept(task,System.currentTimeMillis());
        }
        JSONObject update = reply.optJSONObject("managed_update");
        if (update != null) RemoteUpdates.enqueue(update);
        JSONObject session = reply.optJSONObject("adb_session");
        if(session!=null) {
            try { adb.open(session); }
            catch(Exception rejected) { status("adb_session_rejected",0,rejected.getClass().getSimpleName()); }
        }
    }

    private String network() {
        try {
            if (networkManager == null) {
                networkManager = (android.net.ConnectivityManager) context().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            }
            android.net.NetworkInfo info = networkManager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return "offline";
            if (info.getType() == android.net.ConnectivityManager.TYPE_ETHERNET) return "ethernet";
            if (info.getType() == android.net.ConnectivityManager.TYPE_WIFI) return "wifi";
            if (info.getType() == android.net.ConnectivityManager.TYPE_MOBILE) return "cellular";
            return "other";
        } catch (Exception unavailable) { }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/route"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length > 3 && "00000000".equals(fields[1])) {
                    if ("eth0".equals(fields[0])) return "ethernet";
                    if ("wlan0".equals(fields[0])) return "wifi";
                    if (fields[0].startsWith("ccmni")) return "cellular";
                }
            }
        } catch (Exception ignored) { }
        return "unknown";
    }

    private android.content.Context context() throws Exception {
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
