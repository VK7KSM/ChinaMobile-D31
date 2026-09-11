package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

/** 复用现有root_exec/8765执行按需采集，输出短回执，原报告由既有文件通道取回。 */
public final class RemoteDiagnosticCommand {
    static String command(String apk, String id, JSONObject request) throws Exception {
        if (apk == null || !apk.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk")
                && !apk.equals(RemoteUpdatePlatform.BASELINE.getPath())) throw new IOException("诊断载荷路径无效");
        validate(request);
        String command = "CLASSPATH=" + RescueFiles.quote(apk)
                + " /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand "
                + RescueFiles.quote(id) + " " + RescueFiles.quote(request.toString());
        RescueJobs.validate(id, command, 120);
        return command;
    }

    static void validate(JSONObject request) throws Exception {
        if (request == null || request.toString().length() > 16000) throw new IOException("诊断请求过大");
        String operation = request.optString("operation");
        List<String> keys;
        if (operation.equals("runtime")) keys = Arrays.asList("operation");
        else if (operation.equals("manifest")) keys = Arrays.asList("operation", "scope", "identity");
        else if (operation.equals("fault_files")) keys = Arrays.asList("operation", "eventId", "sources");
        else throw new IOException("未知诊断操作");
        Iterator<String> names = request.keys();
        while (names.hasNext()) if (!keys.contains(names.next())) throw new IOException("未知诊断字段");
        if (operation.equals("manifest")) { allowedPath(request.getString("scope"), false); request.getJSONObject("identity"); }
        if (operation.equals("fault_files")) {
            JSONArray sources = request.getJSONArray("sources");
            if (sources.length() < 1 || sources.length() > 8) throw new IOException("故障来源数量无效");
            request.getString("eventId");
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.getJSONObject(i);
                if (source.length() != 3) throw new IOException("故障来源字段无效");
                allowedPath(source.getString("path"), true);
                new FaultEvidenceCollector.Source(source.getString("id"), source.getString("category"), source.getString("path"));
            }
        }
    }

    private static void allowedPath(String path, boolean fault) throws Exception {
        if (!path.startsWith("/") || path.contains("//") || path.contains("\\") || path.endsWith("/"))
            throw new IOException("诊断路径无效");
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) throw new IOException("诊断路径越界");
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) throw new IOException("诊断路径控制字符");
        String[] roots = fault ? new String[]{"/data/anr", "/data/tombstones", "/data/local/d31-diagnostic-input"}
                : new String[]{"/system", "/vendor", "/data/local/d31-diagnostic-input",
                    "/data/local/d31-patches", "/data/local/d31-system-support", "/data/local/d31-startup-handover",
                    "/data/local/d31-recovery-entry", "/data/local/d31-rescue", "/data/local/d31-startup-curtain",
                    "/data/system/devices/keylayout"};
        for (String root : roots) if (path.equals(root) || path.startsWith(root + "/")) return;
        throw new IOException("诊断路径不在本批明确范围");
    }

    static JSONObject collect(File job, JSONObject request) throws Exception {
        validate(request);
        String operation = request.getString("operation");
        if (operation.equals("runtime")) return RemoteRuntimeInventory.collect(new RemoteRuntimeInventory.AndroidAccess(), System.currentTimeMillis());
        CollectionAccess.Clock clock = AndroidCollectionAccess.systemClock();
        AndroidCollectionAccess access = new AndroidCollectionAccess("/system/bin/busybox", clock);
        CollectionLimits limits = new CollectionLimits(512, 32L * 1024 * 1024, 8L * 1024 * 1024,
                30000, 24, 1024 * 1024, 3600000);
        if (operation.equals("manifest")) {
            if (!android.os.Build.FINGERPRINT.equals(request.getJSONObject("identity").getString("build")))
                throw new IOException("采集请求构建与实际D31不符");
            ManifestCollector.Result result = new ManifestCollector(access, clock)
                    .collect(request.getJSONObject("identity"), request.getString("scope"), limits);
            return new JSONObject().put("operation", operation).put("manifest", result.manifest().toJson()).put("index", result.index());
        }
        File artifacts = new File(job, "evidence");
        if (!artifacts.mkdir()) throw new IOException("故障证据目录无法创建");
        JSONArray input = request.getJSONArray("sources"); List<FaultEvidenceCollector.Source> sources = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) {
            JSONObject s = input.getJSONObject(i);
            sources.add(new FaultEvidenceCollector.Source(s.getString("id"), s.getString("category"), s.getString("path")));
        }
        return new FaultEvidenceCollector(access, clock).collect(request.getString("eventId"), sources, limits,
                new AndroidEvidenceStore(artifacts.getAbsolutePath()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new SecurityException("仅限授权D31维护入口");
        android.system.Os.umask(0077);
        JSONObject request = new JSONObject(args[1]); validate(request);
        JSONObject result = RemoteDiagnosticJobs.execute(new File("/data/local/d31-remote/diagnostics"), args[0], request,
                RemoteDiagnosticCommand::collect);
        System.out.println(result.toString());
        System.exit("completed".equals(result.optString("state")) ? 0 : 1);
    }
    private RemoteDiagnosticCommand() { }
}
