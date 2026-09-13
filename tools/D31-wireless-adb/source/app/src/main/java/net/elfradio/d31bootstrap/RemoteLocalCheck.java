package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** 只读桥接验收入口；回执仅在本地模拟，不能作为云端往返成功证据。 */
public final class RemoteLocalCheck {
    public static void main(String[] args) throws Exception {
        if (android.system.Os.getuid() != 0 || args.length != 1 || !args[0].startsWith("/data/local/d31-remote/"))
            throw new IOException("需要独立测试目录");
        File root = new File(args[0]);
        if (!root.mkdir()) throw new IOException("测试目录必须全新");
        android.system.Os.chmod(root.getPath(), 0700);
        final int[] submissions = {0};
        final boolean[] complete = {false};
        RemoteTasks.Transport transport = new RemoteTasks.Transport() {
            public JSONObject local(String path, JSONObject body) throws Exception {
                if (path.equals("/exec")) submissions[0]++;
                return RemoteHttp.local(path, body);
            }
            public JSONObject progress(JSONObject body) throws Exception {
                JSONObject result = body.optJSONObject("result");
                if (result != null) {
                    String output = result.optString("text");
                    if (!"success".equals(body.optString("state")) || !output.contains("uid=0") || !output.contains("23"))
                        throw new IOException("本地只读命令未通过");
                    complete[0] = true;
                    System.out.println(result);
                }
                return new JSONObject().put("ok", true).put("task", new JSONObject()
                        .put("id", body.getString("task_id")).put("state", body.getString("state")));
            }
        };
        JSONObject task = new JSONObject().put("id", UUID.randomUUID().toString()).put("type", "root_exec")
                .put("expires_at", System.currentTimeMillis() + 30000)
                .put("params", new JSONObject().put("command", "id; getprop ro.build.version.sdk")
                        .put("cwd", "/").put("timeout", 5));
        RemoteTasks tasks = new RemoteTasks(root, "local-check", transport);
        tasks.accept(task, System.currentTimeMillis());
        long until = android.os.SystemClock.elapsedRealtime() + 12000;
        while (!complete[0] && android.os.SystemClock.elapsedRealtime() < until) { Thread.sleep(300); tasks.resume(); }
        if (!complete[0]) throw new IOException("等待命令结果超时");
        new RemoteTasks(root, "local-check", transport).accept(task, System.currentTimeMillis());
        if (submissions[0] != 1) throw new IOException("命令重复执行");
        System.out.println("LOCAL_BRIDGE_OK submissions=1 cloud_tested=false");
        System.exit(0);
    }
}
