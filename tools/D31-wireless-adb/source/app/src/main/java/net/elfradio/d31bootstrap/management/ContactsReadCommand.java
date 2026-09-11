package net.elfradio.d31bootstrap.management;

import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;

/** 只读取证入口；inventory不取联系人内容，其它子命令结果须保存在私有目录。 */
public final class ContactsReadCommand {
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE) || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
                throw new IOException("仅限已授权D31的API23独立root读取");
            String action = validateArgs(args);
            SystemManagement.Control control = new SystemManagement.Control() {
                public void check() throws Exception {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("读取已取消");
                }
                public void before(JSONObject unused) throws Exception { throw new IOException("只读入口禁止修改"); }
            };
            JSONObject result;
            if ("inventory".equals(action)) result = ContactsRead.inventory(context());
            else if ("android".equals(action)) result = ContactsRead.readAndroid(control);
            else result = ContactsRead.nexuiLookup(args[1], control);
            System.out.println(result.toString());
            System.exit(0);
        } catch (Exception error) {
            System.err.println("通讯录只读取证失败：" + SystemManagement.root(error).getClass().getSimpleName());
            System.exit(1);
        }
    }

    static String validateArgs(String[] args) throws IOException {
        if (args == null || args.length == 0) throw new IOException("必须指定inventory、android或nexui-lookup");
        if (args.length == 1 && ("inventory".equals(args[0]) || "android".equals(args[0]))) return args[0];
        if (args.length == 2 && "nexui-lookup".equals(args[0])) { ContactsRead.validateLookup(args[1]); return args[0]; }
        throw new IOException("只读取证参数无效");
    }

    private static Context context() throws Exception {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        return (Context) type.getMethod("getSystemContext").invoke(thread);
    }
    private ContactsReadCommand() { }
}
