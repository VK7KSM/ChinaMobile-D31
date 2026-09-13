package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.JSONObject;

/** Windows写Recovery命令前取得同一维护锁；重启后旧会话预留自然失效。 */
public final class RemoteWindowsMaintenance {
    private static final File FILE = new File(RemoteMaintenance.ROOT, "windows.json");
    private static String boot() throws Exception {
        String value = RescueFiles.read(new File("/proc/sys/kernel/random/boot_id"), 80).trim();
        if (!value.matches("[a-f0-9-]{36}")) throw new IOException("启动会话不可确认");
        return value;
    }
    static boolean reserved() throws Exception {
        if (!FILE.exists()) return false;
        if (!FILE.getAbsoluteFile().equals(FILE.getCanonicalFile())) throw new IOException("Windows维护记录不能为链接");
        JSONObject record = new JSONObject(RescueFiles.read(FILE, 4096));
        return sameBoot(record, boot());
    }
    static boolean sameBoot(JSONObject record, String currentBoot) throws Exception {
        if (!record.getString("id").matches("[a-f0-9]{32}") || !record.getString("boot").matches("[a-f0-9-]{36}"))
            throw new IOException("Windows维护记录损坏");
        return currentBoot.equals(record.getString("boot"));
    }
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[1].matches("[a-f0-9]{32}")
                    || !(args[0].equals("reserve") || args[0].equals("release"))
                    || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IOException("Windows维护参数或机型不符");
            android.system.Os.umask(0077);
            try (RemoteMaintenance.Lease lock = RemoteMaintenance.acquire()) {
                if (lock == null) throw new IOException("另一维护命令进行中");
                if (args[0].equals("reserve")) {
                    if (reserved() && args[1].equals(new JSONObject(RescueFiles.read(FILE, 4096)).getString("id"))) {
                        System.out.println("D31_WINDOWS_RESERVED_V1"); System.exit(0); return;
                    }
                    RemoteMaintenance.requireUnreserved(); RemoteMaintenance.requireRepairReady();
                    RescueFiles.write(FILE, new JSONObject().put("boot", boot()).put("id", args[1]).toString());
                    System.out.println("D31_WINDOWS_RESERVED_V1");
                } else {
                    if (reserved()) {
                        JSONObject record = new JSONObject(RescueFiles.read(FILE, 4096));
                        if (!args[1].equals(record.getString("id")) || !FILE.delete()) throw new IOException("不能释放其它Windows会话");
                    }
                    System.out.println("D31_WINDOWS_RELEASED_V1");
                }
            }
            System.exit(0);
        } catch (Exception failure) {
            System.err.println("Windows维护未完成：" + failure.getMessage()); System.exit(1);
        }
    }
    private RemoteWindowsMaintenance() { }
}
