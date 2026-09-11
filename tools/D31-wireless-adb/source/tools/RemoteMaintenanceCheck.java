package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** 独立目录中调用真实8765，回执传输使用本地模拟器，不接触生产任务和凭据。 */
public final class RemoteMaintenanceCheck {
    private static final class Transport implements RemoteTasks.Transport {
        boolean offline = true;
        int submissions, progressAttempts;
        public JSONObject local(String path, JSONObject body) throws Exception {
            if ("/exec".equals(path)) submissions++;
            return RemoteHttp.local(path, body);
        }
        public JSONObject progress(JSONObject body) throws Exception {
            progressAttempts++;
            if (offline && "success".equals(body.optString("state"))) throw new IOException("本地模拟回执离线");
            return new JSONObject().put("ok", true).put("task", new JSONObject()
                    .put("id", body.getString("task_id")).put("state", body.getString("state")));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || android.system.Os.getuid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)
                || android.os.Build.VERSION.SDK_INT != 23) throw new IllegalArgumentException("仅D31本地验证");
        File root = new File(args[0]);
        if (!root.getCanonicalPath().matches("/data/local/d31-remote/maintenance-check-[a-z0-9-]+")
                || !root.mkdir()) throw new IllegalArgumentException("需要新的独立目录");
        String instance = UUID.randomUUID().toString();
        JSONObject report = new JSONObject().put("report_id", "local-report").put("app_version", BuildConfig.VERSION_NAME)
                .put("_core_instance", instance).put("_notice_version", 1);
        JSONObject reply = new JSONObject().put("report_id", "local-report");
        if (!RemoteReportReceipt.current(report, reply, BuildConfig.VERSION_NAME, instance)
                || RemoteReportReceipt.current(report, reply, BuildConfig.VERSION_NAME, "other-instance")
                || RemoteReportReceipt.wire(report).has("_core_instance")) throw new IllegalStateException("报告合同失败");
        JSONObject legacy = new JSONObject(report.toString()); legacy.remove("_core_instance");
        if (RemoteReportReceipt.current(legacy, reply, BuildConfig.VERSION_NAME, instance)) throw new IllegalStateException("旧报告误确认");
        String taskId = "cmd-local-" + UUID.randomUUID();
        JSONObject task = new JSONObject().put("id", taskId).put("type", "root_exec")
                .put("expires_at", System.currentTimeMillis() + 60000).put("params", new JSONObject()
                        .put("command", "printf D31_LOCAL_TASK_CHECK").put("cwd", "/").put("timeout", 5));
        Transport transport = new Transport();
        RemoteTasks tasks = new RemoteTasks(root, "local-check", transport);
        try { tasks.accept(task, System.currentTimeMillis()); } catch (IOException expectedOffline) { }
        File receipt = new File(new File(root, "receipts"), RemoteProtocol.localJobId("local-check", taskId) + ".json");
        // 固定排在前面的已完成回执制造阻塞条件，不修改生产记录。
        RescueFiles.write(new File(new File(root, "receipts"), "000-old.json"), new JSONObject()
                .put("task", new JSONObject().put("id", "cmd-old-local"))
                .put("receipt", new JSONObject().put("task_id", "cmd-old-local").put("state", "success")).toString());
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        JSONObject record;
        do {
            try { tasks.resume(); } catch (IOException expectedOffline) { }
            record = RemoteUpdateFiles.read(receipt);
            if (record.has("receipt")) break;
            Thread.sleep(200);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        if (!record.has("receipt") || record.optBoolean("acknowledged") || transport.submissions != 1
                || !"D31_LOCAL_TASK_CHECK".equals(record.getJSONObject("receipt").getJSONObject("result").getString("text")))
            throw new IllegalStateException("真实命令结果未在离线时保存");
        RescueFiles.write(new File(root, "offline-receipt.json"), record.toString());
        transport.offline = false;
        new RemoteTasks(root, "local-check", transport).resume();
        new RemoteTasks(root, "local-check", transport).resume();
        if (!RemoteUpdateFiles.read(receipt).getBoolean("acknowledged") || transport.submissions != 1)
            throw new IllegalStateException("补传或重启去重失败");
        RescueFiles.write(new File(root, "result.json"), new JSONObject().put("version_code", BuildConfig.VERSION_CODE)
                .put("current_report_only", true).put("real_local_command", true).put("submissions", transport.submissions)
                .put("offline_result_saved", true).put("replayed_command", false).put("cloud_transport_simulated", true).toString());
        System.out.println("CURRENT_REPORT_OK LOCAL_COMMAND_ONCE OFFLINE_SAVED RESTART_RECEIPT_OK");
        System.exit(0);
    }
}
