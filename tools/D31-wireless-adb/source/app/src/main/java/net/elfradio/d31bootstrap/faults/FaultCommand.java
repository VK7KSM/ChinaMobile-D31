package net.elfradio.d31bootstrap.faults;

import java.io.File;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.diagnostics.collection.AndroidCollectionAccess;

/** Existing root_exec can call this without a registered or network-connected daemon. */
public final class FaultCommand {
    public static void main(String[] args) {
        int exit = 1;
        try {
            if (android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IllegalArgumentException("PLATFORM_REQUIRED");
            android.system.Os.umask(0077);
            File root = new File(AndroidFaultSources.ARCHIVE_ROOT); JSONObject result;
            if (args.length == 1 && "collect".equals(args[0])) {
                try (FaultMonitor monitor = new FaultMonitor(root, new AndroidFaultSources(),
                        AndroidCollectionAccess.systemClock(), FaultPolicy.continuousDefaults())) {
                    monitor.collect(); long start = android.os.SystemClock.elapsedRealtime();
                    while (monitor.isBusy() && android.os.SystemClock.elapsedRealtime() - start < 90000) Thread.sleep(20);
                    boolean finished = !monitor.isBusy();
                    result = new JSONObject().put("schemaVersion", 1).put("state", finished ? monitor.lastOutcome() : "DEADLINE_EXCEEDED")
                            .put("reason", monitor.lastFailure()).put("indexRoot", AndroidFaultSources.ARCHIVE_ROOT)
                            .put("rawContentInSummary", false).put("collectionMayBePartial", true);
                    exit = finished && "NONE".equals(monitor.lastFailure()) ? 0 : 1;
                }
            } else if (args.length == 1 && "discover".equals(args[0])) {
                result = new AndroidFaultSources().discoveryProbe(); exit = 0;
            } else if (args.length == 2 && ("query".equals(args[0]) || "index".equals(args[0]))) {
                new AndroidFaultSources().checkPrivateRoot(root);
                result = "query".equals(args[0]) ? FaultMonitor.readQuery(root, args[1])
                        : FaultMonitor.readIndex(root, Integer.parseInt(args[1])); exit = 0;
            } else if ((args.length == 2 || args.length == 3) && "pending".equals(args[0])) {
                new AndroidFaultSources().checkPrivateRoot(root);
                result = FaultMonitor.readPending(root, Integer.parseInt(args[1]), args.length == 3 ? args[2] : ""); exit = 0;
            } else if (args.length == 2 && "export".equals(args[0])) {
                result = new FaultExports(root, new AndroidFaultSources(), FaultPolicy.defaults()).exportEvent(args[1]); exit = 0;
            } else if (args.length == 3 && "index".equals(args[0])) {
                new AndroidFaultSources().checkPrivateRoot(root);
                result = FaultMonitor.readIndex(root, Integer.parseInt(args[1]), args[2]); exit = 0;
            } else if (args.length == 5 && "archive".equals(args[0])) {
                result = new FaultExports(root, new AndroidFaultSources(), FaultPolicy.defaults())
                        .archiveEvent(args[1], args[2], Long.parseLong(args[3]), args[4]); exit = 0;
            } else throw new IllegalArgumentException("INVALID_ARGUMENTS");
            System.out.println(result.toString());
        } catch (Exception failure) {
            String reason = failure.getMessage();
            if (reason == null || !reason.matches("[A-Z_]{1,64}")) reason = "FAULT_COMMAND_FAILED";
            System.out.println("{\"schemaVersion\":1,\"state\":\"FAILED\",\"reason\":\"" + reason + "\"}");
        }
        System.exit(exit);
    }
    private FaultCommand() { }
}
