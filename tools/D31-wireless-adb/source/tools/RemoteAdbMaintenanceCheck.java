package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;

/** 独立真机验证入口，通过8765宿主执行，避免adbd停止中断证据保存。 */
public final class RemoteAdbMaintenanceCheck {
    private static final String[] PROPERTIES = {"service.adb.tcp.port", "persist.adb.tcp.port",
            "sys.usb.config", "persist.sys.usb.config", "sys.usb.state"};
    private static final String[] SERVICES = {"com.starnet.nexui", "d31-rescue", "d31-elfremote",
            "d31-remote-supervisor", "guard"};

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || android.system.Os.getuid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)
                || !RemoteDeployment.systemManaged()
                || !("inspect".equals(args[1]) || "restart".equals(args[1])))
            throw new IllegalArgumentException("需要D31系统组件与明确模式");
        File directory = new File(args[0]);
        if (!directory.getCanonicalPath().matches("/data/local/d31-remote/adb-check-[a-z0-9-]+")
                || !directory.mkdir()) throw new IllegalArgumentException("需要新的独立目录");
        JSONObject before = snapshot();
        RescueFiles.write(new File(directory, "before.json"), before.toString());
        int port = AdbControl.currentPort();
        if (port != 5654 || !AdbControl.isHealthy(null))
            throw new IllegalStateException("当前端口或健康不符合本轮基线");
        RescueFiles.write(new File(directory, "status-before.txt"), AdbControl.status(null));
        if ("restart".equals(args[1])) {
            RescueFiles.write(new File(directory, "intent.txt"), AdbControl.restartCommand(true, port));
            AdbControl.ActionResult result = AdbControl.restartEnabled(null);
            RescueFiles.write(new File(directory, "action.txt"), result.log);
            if (!result.succeeded) throw new IllegalStateException("adbd重启未通过");
        }
        JSONObject after = snapshot();
        RescueFiles.write(new File(directory, "after.json"), after.toString());
        for (String property : PROPERTIES) same(before, after, property);
        for (String service : SERVICES) same(before, after, service);
        same(before, after, "iptables");
        same(before, after, "ip6tables");
        if ("restart".equals(args[1]) && before.getString("adbd").equals(after.getString("adbd")))
            throw new IllegalStateException("adbd进程没有更换，不能算实际重启");
        if (!AdbControl.isHealthy(null)) throw new IllegalStateException("最终监听不正常");
        String result = "restart".equals(args[1]) ? "ADB_RESTART_OK CONFIG_AND_SERVICES_UNCHANGED"
                : "PASSIVE_STATUS_OK PORT_5654";
        RescueFiles.write(new File(directory, "result.txt"), result);
        System.out.println(result);
        System.exit(0);
    }

    private static void same(JSONObject before, JSONObject after, String key) throws Exception {
        if (!before.getString(key).equals(after.getString(key)))
            throw new IllegalStateException("非目标状态变化：" + key);
    }

    private static JSONObject snapshot() throws Exception {
        JSONObject out = new JSONObject().put("time_ms", System.currentTimeMillis())
                .put("build", android.os.Build.FINGERPRINT).put("version", BuildConfig.VERSION_CODE);
        for (String property : PROPERTIES) out.put(property, shell("getprop " + property));
        for (String service : SERVICES) out.put(service, shell("/system/bin/busybox pidof " + service));
        out.put("adbd", shell("/system/bin/busybox pidof adbd"));
        out.put("iptables", shell("iptables -S"));
        out.put("ip6tables", shell("ip6tables -S"));
        return out;
    }

    private static String shell(String command) throws Exception {
        Process p = new ProcessBuilder("/system/bin/busybox", "timeout", "-t", "5",
                "/system/bin/sh", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048]; int count;
        while ((count = p.getInputStream().read(buffer)) >= 0) bytes.write(buffer, 0, count);
        if (p.waitFor() != 0) throw new IllegalStateException("基线读取失败：" + command);
        return bytes.toString("UTF-8").trim();
    }
}
