package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

final class RemoteTasks {
    interface Transport {
        JSONObject local(String path, JSONObject body) throws Exception;
        JSONObject progress(JSONObject body) throws Exception;
    }
    interface NetworkQuery { JSONObject query(String id, JSONObject intent) throws Exception; }
    private final File root;
    private final String deviceId;
    private final Transport transport;
    private final NetworkQuery networkQuery;

    RemoteTasks(File directory, String deviceId, Transport transport) throws Exception {
        this(directory, deviceId, transport, RemoteNetworkTask::query);
    }

    RemoteTasks(File directory, String deviceId, Transport transport, NetworkQuery networkQuery) throws Exception {
        root = new File(directory, "receipts");
        if (!root.isDirectory() && !root.mkdir()) throw new IOException("任务目录不可用");
        this.deviceId = deviceId; this.transport = transport;
        this.networkQuery = networkQuery;
    }

    void accept(JSONObject task, long now) throws Exception {
        String cloudId = task.getString("id");
        String id = RemoteProtocol.localJobId(deviceId, cloudId);
        File file = new File(root, id + ".json");
        if (file.exists()) {
            JSONObject saved = read(file);
            if (!saved.getJSONObject("task").getString("type").equals(task.getString("type"))
                    || !RemoteProtocol.sameJson(saved.getJSONObject("task").getJSONObject("params"),
                    task.getJSONObject("params"))) throw new IOException("同号任务内容冲突");
            if (RemoteNetworkTask.matches(task) && !task.getString("request_digest").equals(
                    saved.getJSONObject("task").optString("request_digest"))) throw new IOException("原网络任务摘要改变");
            if ("system_config".equals(task.optString("type")) && task.optBoolean("cancel_requested") && !saved.has("receipt")) {
                if (RemoteNetworkTask.matches(task)) { if (saved.optBoolean("dispatch_intent")) RemoteNetworkTask.cancel(id); }
                else RemoteBusinessCommand.cancel(RemoteBusinessCommand.ROOT, id);
            }
            if (task.optBoolean("cancel_requested") && !saved.optBoolean("dispatch_intent") && !saved.has("receipt")) {
                saved.put("receipt", receipt(cloudId, "rejected", "任务已取消，未执行",
                        RemoteNetworkTask.matches(task) ? RemoteNetworkTask.notStarted(0) : null));
                RescueFiles.write(file, saved.toString());
            }
            advance(file, saved); return;
        }
        File[] records = root.listFiles((dir, name) -> name.endsWith(".json"));
        if (records == null || records.length >= 1024) throw new IOException("任务记录已满，需归档，不删除去重依据");
        for (File record : records) if (!read(record).has("receipt")) return;
        JSONObject saved = new JSONObject().put("task", task).put("execution_apk", System.getenv("CLASSPATH"));
        try { saved.put("request", RemoteProtocol.commandRequest(deviceId, task, now)); }
        catch (Exception invalid) {
            JSONObject dest=null;
            if (RemoteNetworkTask.matches(task)) dest = RemoteNetworkTask.notStarted(0);
            if (RemoteContactsTask.TYPE.equals(task.optString("type")))
                dest = RemoteContactsTask.failureResult(task.optJSONObject("params"), "CONTACTS_TASK_PARAMS_INVALID");
            if("configure_sip".equals(task.optString("type"))){JSONObject p=task.optJSONObject("params");if(p!=null)dest=new JSONObject().put("target",p.optString("target")).put("account_id",p.optString("account_id"));}
            saved.put("receipt", receipt(cloudId, "rejected", "任务参数、期限或取消状态不满足执行条件", dest));
        }
        RescueFiles.write(file, saved.toString());
        advance(file, saved);
    }

    void resume() throws Exception {
        File[] files = root.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) throw new IOException("任务目录不可读");
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        Exception first = null;
        int cloudBudget = 4;
        for (File file : files) {
            try {
                JSONObject saved = read(file);
                if (saved.optBoolean("acknowledged")) continue;
                boolean cloud = cloudBudget > 0;
                if (cloud) cloudBudget--;
                // 已完成记录的补传有界；云失败后仍读取已下发命令的本地结果。
                if (cloud || !saved.has("receipt")) advance(file, saved, cloud);
            } catch (Exception unavailable) {
                if (first == null) first = unavailable;
                cloudBudget = 0;
            }
        }
        if (first != null) throw first;
    }

    private JSONObject read(File file) throws Exception { return new JSONObject(RescueFiles.read(file, 128000)); }

    private void advance(File file, JSONObject saved) throws Exception {
        advance(file, saved, true);
    }

    private void advance(File file, JSONObject saved, boolean cloud) throws Exception {
        JSONObject task = saved.getJSONObject("task");
        String cloudId = task.getString("id");
        if (RemoteNetworkTask.matches(task) && saved.optBoolean("dispatch_intent") && !saved.has("receipt")) {
            advanceNetwork(file, saved, cloud);
            return;
        }
        if (!saved.has("receipt")) {
            JSONObject request = saved.getJSONObject("request");
            String path = "/jobs/" + request.getString("id");
            JSONObject outcome = transport.local(path, null);
            if (outcome == null && saved.optBoolean("dispatch_intent")) {
                saved.put("receipt", receipt(cloudId, "failed", "执行回执缺失，不自动重放命令", null));
            } else if (outcome == null) {
                if (task.optLong("expires_at") <= System.currentTimeMillis()) {
                    saved.put("receipt", receipt(cloudId, "rejected", "任务已过期，未执行",
                            RemoteNetworkTask.matches(task) ? RemoteNetworkTask.notStarted(0) : null));
                } else {
                    if (!cloud) return;
                    // 云端确认领取后才进入本地执行；不确定是否写出时只查询，不重放。
                    acknowledge(receipt(cloudId, "claimed", "设备已接收命令", null));
                    acknowledge(receipt(cloudId, "running", "设备准备执行命令", null));
                    saved.put("dispatch_intent", true);
                    RescueFiles.write(file, saved.toString());
                    try { outcome = transport.local("/exec", request); }
                    catch (RemoteHttp.Rejected rejected) {
                        if (!RemoteNetworkTask.matches(task) || rejected.status != 409) throw rejected;
                        outcome = transport.local(path, null);
                        if (outcome == null) {
                            // 本地执行器明确在调度前拒绝且原号不存在；不是超时后推断未写出。
                            saved.put("receipt", receipt(cloudId, "rejected", "本地执行器占用，网络命令未开始",
                                    RemoteNetworkTask.notStarted(0)));
                            RescueFiles.write(file, saved.toString());
                        }
                    }
                }
            }
            if (RemoteNetworkTask.matches(task) && saved.optBoolean("dispatch_intent") && !saved.has("receipt")) {
                advanceNetwork(file, saved, cloud);
                return;
            }
            if (outcome != null && !"running".equals(outcome.optString("state"))) {
                String state = outcome.optString("state");
                boolean ok = "completed".equals(state) && outcome.optInt("exit_code", -1) == 0;
                String text = outcome.optString("output", outcome.optString("error"));
                JSONObject result = new JSONObject().put("text", text.substring(0, Math.min(16000, text.length())))
                        .put("truncated", outcome.optBoolean("truncated") || text.length() > 16000)
                        .put("exit_code", outcome.opt("exit_code")).put("elapsed_ms", outcome.optLong("elapsed_ms"))
                        .put("stage", "command").put("action", state);
                if (RemoteContactsTask.TYPE.equals(task.optString("type"))) {
                    try {
                        result = RemoteContactsTask.result(task.getJSONObject("params"), request.getString("id"),
                                saved.optString("execution_apk", System.getenv("CLASSPATH")), outcome);
                        ok = result.getJSONObject("contacts_page").getBoolean("ok");
                    } catch (Exception invalid) {
                        ok = false;
                        result = RemoteContactsTask.failureResult(task.optJSONObject("params"), "CONTACTS_TASK_OUTCOME_INCOMPLETE");
                    }
                }
                if("configure_sip".equals(task.optString("type"))){
                    JSONObject p=task.getJSONObject("params");
                    boolean applied=false;
                    if(ok)try{
                        JSONObject r=new JSONObject(RescueFiles.read(new File(RemoteSip.ROOT,request.getString("id")+".result.json"),16000));
                        applied=r.optBoolean("applied")&&p.getString("target").equals(r.optString("target"))&&p.getString("account_id").equals(r.optString("account_id"));
                    }catch(Exception invalid){}
                    ok=ok&&applied;
                    result=new JSONObject().put("target",p.getString("target")).put("account_id",p.getString("account_id"))
                            .put("applied",ok).put("exit_code",ok?0:1).put("action",ok?"completed":"failed")
                            .put("text",ok?"配置已写入":"配置未完成，保留原配置备份");
                }
                if ("system_config".equals(task.optString("type"))) {
                    JSONObject snapshot = null;
                    try {
                        JSONObject verified = RemoteBusinessCommand.readResult(RemoteBusinessCommand.ROOT,
                                request.getString("id"), task.getString("type"), task.getJSONObject("params"));
                        ok = ok && verified.getBoolean("ok");
                        snapshot = verified.getJSONObject("snapshot");
                    } catch (Exception invalid) { ok = false; }
                    String output = snapshot == null ? "管理任务未取得完整回执，请查询原任务" : snapshot.toString();
                    boolean truncated = output.length() > 16000;
                    ok = ok && !truncated;
                    result = new JSONObject().put("text", output.substring(0, Math.min(16000, output.length())))
                            .put("truncated", truncated).put("exit_code", ok ? 0 : 1)
                            .put("action", ok ? "completed" : "failed").put("stage", "system_config");
                }
                saved.put("receipt", receipt(cloudId, ok ? "success" : "failed",
                        ok ? "命令执行成功" : "命令未成功完成", result));
            }
            RescueFiles.write(file, saved.toString());
        }
        if (cloud && saved.has("receipt") && !saved.optBoolean("acknowledged")) {
            acknowledge(saved.getJSONObject("receipt"));
            saved.put("acknowledged", true); RescueFiles.write(file, saved.toString());
        }
    }

    private void advanceNetwork(File file, JSONObject saved, boolean cloud) throws Exception {
        JSONObject task = saved.getJSONObject("task");
        String id = saved.getJSONObject("request").getString("id");
        JSONObject queried = networkQuery.query(id, RemoteNetworkTask.validate(deviceId, task));
        JSONObject network = queried.optJSONObject("network_transaction");
        if (queried.getBoolean("complete")) {
            boolean ok = queried.getBoolean("success");
            String finalState = network != null && "NOT_STARTED".equals(network.optString("status")) ? "rejected" : ok ? "success" : "failed";
            saved.put("receipt", receipt(task.getString("id"), finalState,
                    ok ? "网络原任务已完成核查" : "网络原任务未提交目标配置",
                    new JSONObject().put("network_transaction", network)));
            RescueFiles.write(file, saved.toString());
            if (cloud) {
                acknowledge(saved.getJSONObject("receipt"));
                saved.put("acknowledged", true); RescueFiles.write(file, saved.toString());
            }
        } else if (cloud) {
            // 仅阶段变化上报；未知读取不捏造前像、绑定或终态。
            String phase = network == null ? queried.optString("local_state", "UNKNOWN") : network.toString();
            if (!phase.equals(saved.optString("network_progress"))) {
                acknowledge(receipt(task.getString("id"), "running", "网络变更等待原号确认或恢复核查",
                        network == null ? null : new JSONObject().put("network_transaction", network)));
                saved.put("network_progress", phase); RescueFiles.write(file, saved.toString());
            }
        }
    }

    private JSONObject receipt(String id, String state, String detail, JSONObject result) throws Exception {
        // 校验失败也必须带回目标，服务端才能将失败归属于原线路。
        if(result==null && ("rejected".equals(state)||"failed".equals(state))){
            String localId=RemoteProtocol.localJobId(deviceId,id);File file=new File(root,localId+".json");
            if(file.exists()){
                JSONObject task=read(file).getJSONObject("task");
                if (RemoteContactsTask.TYPE.equals(task.optString("type")))
                    result = RemoteContactsTask.failureResult(task.optJSONObject("params"), "CONTACTS_TASK_NOT_COMPLETED");
                if("configure_sip".equals(task.optString("type"))){JSONObject p=task.getJSONObject("params");
                    result=new JSONObject().put("target",p.optString("target")).put("account_id",p.optString("account_id"));}
            }
        }
        return new JSONObject().put("device_id", deviceId).put("task_id", id)
                .put("state", state).put("detail", detail).put("result", result);
    }

    private void acknowledge(JSONObject body) throws Exception {
        JSONObject response = transport.progress(new JSONObject(body.toString()));
        JSONObject task = response.optJSONObject("task");
        if (!response.optBoolean("ok") || task == null || !body.getString("task_id").equals(task.optString("id"))
                || !(body.getString("state").equals(task.optString("state"))
                || ("claimed".equals(body.optString("state")) && "running".equals(task.optString("state")))))
            throw new IOException("云端未确认任务回执");
    }
}
