package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 现有root_exec调用本入口；仅接收一个typed JSON参数，无新增Web任务类型。 */
public final class RemoteProductRepairCommand {
    static final File ROOT = new File("/data/local/d31-remote/product-repairs");
    public static JSONObject execute(JSONObject request) throws Exception {
        if (request == null || request.toString().length() > 8192) throw new IOException("修复请求过大");
        String operation = RemoteProductRepair.string(request, "operation");
        if (operation.equals("apply")) RemoteProductRepair.keys(request, "operation", "plan");
        else if (operation.equals("inspect")) {
            if (request.has("item_id")) RemoteProductRepair.keys(request, "operation", "item_id");
            else RemoteProductRepair.keys(request, "operation");
        }
        else if (operation.equals("query") || operation.equals("recover"))
            RemoteProductRepair.keys(request, "operation", "operation_id");
        else throw new IOException("未知产品修复操作");
        RemoteProductRepair transaction = new RemoteProductRepair(new RemoteProductRepairAndroid(),
                new RemoteProductRepairStore(ROOT, RemoteMaintenance.ROOT));
        if (operation.equals("inspect")) return request.has("item_id")
                ? transaction.inspect(RemoteProductRepair.string(request, "item_id")) : transaction.inspect();
        if (operation.equals("query")) return transaction.query(RemoteProductRepair.string(request, "operation_id"));
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
            if (lease == null) throw new IOException("维护忙，可按原号查询");
            RemoteMaintenance.requireRepairReady();
            return operation.equals("apply") ? transaction.apply(request.getJSONObject("plan"))
                    : transaction.recover(RemoteProductRepair.string(request, "operation_id"));
        }
    }
    public static void main(String[] args) {
        try {
            if (args.length != 1 || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new SecurityException("仅限D31系统维护入口");
            android.system.Os.umask(0077);
            JSONObject result = execute(new JSONObject(args[0]));
            System.out.println(result);
            System.exit(0);
        } catch (Exception failure) {
            System.err.println("产品修复未完成：" + failure.getClass().getSimpleName()); System.exit(1);
        }
    }
    private RemoteProductRepairCommand() { }
}
