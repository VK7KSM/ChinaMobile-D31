package net.elfradio.d31bootstrap.faults;

import java.io.File;
import org.json.JSONObject;

/** Read-only local entry point transported by the existing root_exec task. */
public final class FaultQueryCommand {
    public static void main(String[] args) {
        int exit = 1;
        try {
            if (android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IllegalArgumentException("PLATFORM_REQUIRED");
            if (args.length != 2) throw new IllegalArgumentException("ARGUMENTS_REQUIRED");
            File root = new File(AndroidFaultSources.ARCHIVE_ROOT);
            new AndroidFaultSources().checkPrivateRoot(root);
            JSONObject result;
            if ("query".equals(args[0])) result = FaultMonitor.readQuery(root, args[1]);
            else if ("index".equals(args[0])) result = FaultMonitor.readIndex(root, Integer.parseInt(args[1]));
            else throw new IllegalArgumentException("UNKNOWN_OPERATION");
            System.out.println(result.toString()); exit = 0;
        } catch (Exception failure) { System.out.println("{\"schemaVersion\":1,\"state\":\"QUERY_FAILED\",\"rawContentInSummary\":false}"); }
        System.exit(exit);
    }
    private FaultQueryCommand() { }
}
