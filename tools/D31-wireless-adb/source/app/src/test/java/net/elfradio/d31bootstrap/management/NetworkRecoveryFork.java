package net.elfradio.d31bootstrap.management;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 真正独立JVM的离线守护：只改测试目录的布尔文件，不调用Android或网络。 */
public final class NetworkRecoveryFork {
    public static void main(String[] args) throws Exception {
        final File root = new File(args[0]);
        final String boot = args[1];
        final long began = System.nanoTime();
        NetworkChangeTransaction.Clock clock = new NetworkChangeTransaction.Clock() {
            public String bootId() { return boot; }
            public long elapsedMillis() { return 2000 + (System.nanoTime() - began) / 1000000; }
        };
        NetworkChangeTransaction.Platform platform = new NetworkChangeTransaction.Platform() {
            public AutoCloseable acquire(String task) { return () -> { }; }
            public void requireRecoveryOwner(String task, String boot, long deadline) { throw new AssertionError(); }
            public Boolean readWifiEnabled() throws Exception {
                return Boolean.valueOf(new String(Files.readAllBytes(new File(root, "device").toPath()), StandardCharsets.UTF_8));
            }
            public void setWifiEnabled(boolean value) throws Exception {
                Files.write(new File(root, "device").toPath(), Boolean.toString(value).getBytes(StandardCharsets.UTF_8));
            }
        };
        final NetworkChangeTransaction engine = new NetworkChangeTransaction(new NetworkChangeTransactionJournal(root), platform, clock);
        JSONObject result = NetworkRecoveryLoop.run(new NetworkRecoveryLoop.Host() {
            public long elapsed() { return clock.elapsedMillis(); }
            public String boot() { return boot; }
            public void heartbeat() throws Exception { new File(root, "heartbeat").createNewFile(); }
            public JSONObject query() throws Exception { return engine.query("task-1"); }
            public JSONObject recover() throws Exception { return engine.recover("task-1"); }
            public void settled(JSONObject state) throws Exception { Files.write(new File(root, "settled").toPath(), state.toString().getBytes(StandardCharsets.UTF_8)); }
            public void pause() throws Exception { Thread.sleep(20); }
        }, "test-boot", 1100);
        System.out.println(result.getString("state"));
    }
    private NetworkRecoveryFork() { }
}
