package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

final class RescueJobs {
    interface Runner { JSONObject run(File folder, String command, int timeout) throws Exception; }
    private final File root;
    private final Runner runner;
    private final AtomicBoolean busy = new AtomicBoolean();

    RescueJobs(File root, Runner runner) throws Exception {
        this.root = root;
        this.runner = runner;
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Job directory");
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) {
            File state = new File(dir, "result.json");
            if (state.isFile()) {
                try {
                JSONObject obj = new JSONObject(RescueFiles.read(state, 600000));
                if ("running".equals(obj.optString("state"))) {
                    obj.put("state", "interrupted").put("error", "救援进程已重启，任务不会自动重放");
                    RescueFiles.write(state, obj.toString());
                }
                } catch (Exception corrupt) {
                    RescueFiles.write(state, new JSONObject().put("id", dir.getName())
                            .put("state", "interrupted").put("error", "任务记录损坏，不自动重放").toString());
                }
            }
        }
    }

    static void validate(String id, String command, int timeout) {
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid id");
        if (command.trim().isEmpty() || command.indexOf('\0') >= 0
                || command.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)
            throw new IllegalArgumentException("Command must be 1..8192 bytes");
        if (timeout < 1 || timeout > 120) throw new IllegalArgumentException("Timeout must be 1..120 seconds");
    }

    synchronized JSONObject submit(String id, String command, int timeout) throws Exception {
        validate(id, command, timeout);
        File folder = new File(root, id);
        if (folder.exists()) {
            JSONObject old = new JSONObject(RescueFiles.read(new File(folder, "request.json"), 60000));
            if (!command.equals(old.getString("command")) || timeout != old.getInt("timeout"))
                throw new IllegalStateException("Request id already used with different command");
            return get(id);
        }
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("A command is already running");
        try {
            prune();
            if (!folder.mkdir()) throw new java.io.IOException("Create job failed");
            RescueFiles.write(new File(folder, "request.json"), new JSONObject()
                    .put("command", command).put("timeout", timeout).toString());
            JSONObject state = new JSONObject().put("id", id).put("state", "running")
                    .put("started", System.currentTimeMillis());
            RescueFiles.write(new File(folder, "result.json"), state.toString());
            JSONObject accepted = new JSONObject(state.toString());
            new Thread(() -> {
                try {
                    JSONObject result = runner.run(folder, command, timeout);
                    for (java.util.Iterator<String> it = result.keys(); it.hasNext();) {
                        String key = it.next(); state.put(key, result.get(key));
                    }
                } catch (Exception error) {
                    try { state.put("state", "failed").put("error", error.toString()); }
                    catch (Exception ignored) { }
                } finally {
                    try {
                        state.put("finished", System.currentTimeMillis());
                        RescueFiles.write(new File(folder, "result.json"), state.toString());
                    } catch (Exception failure) { failure.printStackTrace(); }
                    busy.set(false);
                }
            }, "d31-rescue-command").start();
            return accepted;
        } catch (Exception error) {
            busy.set(false);
            throw error;
        }
    }

    JSONObject get(String id) throws Exception {
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid id");
        File file = new File(new File(root, id), "result.json");
        return file.isFile() ? new JSONObject(RescueFiles.read(file, 600000)) : null;
    }

    boolean isBusy() { return busy.get(); }

    private void prune() {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length < 32) return;
        Arrays.sort(dirs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i <= dirs.length - 32; i++) {
            File[] files = dirs[i].listFiles();
            if (files != null) for (File file : files) if (file.isFile()) file.delete();
            dirs[i].delete();
        }
    }
}
