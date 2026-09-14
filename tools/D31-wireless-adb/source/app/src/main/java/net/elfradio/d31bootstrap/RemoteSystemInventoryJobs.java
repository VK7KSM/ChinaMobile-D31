package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

/** 原远程任务驱动的分批只读采集；每批原件先落盘，再推进检查点。 */
final class RemoteSystemInventoryJobs {
    private static final int LIMIT = 4 * 1024 * 1024;

    static JSONObject execute(File root, String id, JSONObject request, String binding,
            String build, CollectionAccess access, CollectionAccess.Clock clock) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{64}") || request == null || request.toString().length() > 16000)
            throw new IOException("采集会话参数无效");
        String operation = request.getString("operation");
        Set<String> keys = new HashSet<>(Arrays.asList("operation", operation.equals("init") ? "identity" : "sequence"));
        if (operation.equals("init")) keys.add("roots");
        if (!Arrays.asList("init", "step", "query").contains(operation)) throw new IOException("未知采集操作");
        for (Iterator<String> it = request.keys(); it.hasNext();) if (!keys.contains(it.next())) throw new IOException("未知采集字段");
        if (!root.equals(root.getCanonicalFile()) || (!root.isDirectory() && !root.mkdirs())) throw new IOException("采集根目录无效");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "lock"), "rw");
             FileLock lock = RemoteFileLocks.tryExclusive(lockFile.getChannel())) {
            if (lock == null) throw new IOException("采集正在进行，请查询原任务");
            File directory = new File(root, id), initial = new File(directory, "initial.json");
            if (!directory.equals(directory.getCanonicalFile())) throw new IOException("采集目录不能为链接");
            if (operation.equals("init")) {
                JSONObject identity = request.getJSONObject("identity");
                if (!build.equals(identity.getString("build"))) throw new IOException("采集构建不符");
                List<String> scopes = new ArrayList<>();
                JSONArray roots = request.getJSONArray("roots");
                for (int i = 0; i < roots.length(); i++) scopes.add(roots.getString(i));
                CollectionPlan planned = CollectionPlan.create(identity, binding, scopes,
                        new CollectionLimits(512, 1024L * 1024 * 1024, 512L * 1024 * 1024,
                                60000, 24, 1024 * 1024, 86400000), 4096, 32L * 1024 * 1024 * 1024, 1800000);
                JSONObject checkpoint = planned.checkpoint();
                if (initial.exists()) {
                    if (!CollectionPlan.digest(read(initial)).equals(CollectionPlan.digest(checkpoint))) throw new IOException("原会话已有不同范围");
                } else {
                    File[] sessions = root.listFiles(File::isDirectory);
                    if (sessions == null || sessions.length >= 8 || root.getUsableSpace() < 256L * 1024 * 1024)
                        throw new IOException("采集存档或空间达到上限");
                    if (directory.exists() || !directory.mkdir()) throw new IOException("会话初始化中断，原件保留");
                    RescueFiles.write(initial, checkpoint.toString());
                }
            }
            if (!initial.isFile()) throw new IOException("未初始化采集会话");
            CollectionPlan plan = CollectionPlan.restore(read(initial), binding, sha -> null);
            int count = 0;
            while (event(directory, count).isFile()) {
                if (count >= 4096) throw new IOException("采集批次数量达到上限");
                plan.accept(readEvent(event(directory, count++)));
            }
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("采集原件不可枚举");
            for (File file : files) if (file.getName().matches("event-[0-9]{5}\\.json")
                    && Integer.parseInt(file.getName().substring(6, 11)) >= count) throw new IOException("原件序列存在缺口");
            File output = null;
            if (operation.equals("step")) {
                Object sequence = request.get("sequence");
                if (!(sequence instanceof Integer || sequence instanceof Long)) throw new IOException("批次必须为整数");
                long n = ((Number) sequence).longValue();
                if (n < 0 || n > count || n >= 4096) throw new IOException("批次序号不符");
                output = event(directory, (int) n);
                if (n == count) {
                    if (plan.next() == null) throw new IOException("没有可执行批次，需查看汇总");
                    File temporary = new File(output.getPath() + ".tmp");
                    if (temporary.exists()) throw new IOException("批次写入中断，保留临时原件");
                    long total = 0;
                    for (File file : files) total += file.length();
                    if (total > 256L * 1024 * 1024 || directory.getUsableSpace() < 64L * 1024 * 1024)
                        throw new IOException("本会话存档空间达到上限");
                    JSONObject observed = plan.collectNext(access, clock, binding, "inventory-" + UUID.randomUUID().toString().replace("-", ""));
                    JSONObject envelope = new JSONObject().put("event", observed).put("sha256", CollectionPlan.digest(observed));
                    byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > LIMIT) throw new IOException("批次原件超过上限");
                    plan.accept(observed);
                    // 不覆盖旧批次。若上次仅留下.tmp，保留该临时原件供审查。
                    RescueFiles.write(output, envelope.toString());
                    if (!CollectionPlan.digest(readEvent(output)).equals(CollectionPlan.digest(observed))) throw new IOException("批次落盘回读不符");
                    count++;
                }
            }
            JSONObject result = new JSONObject().put("schemaVersion", 1).put("session", id).put("state", "completed")
                    .put("nextSequence", count).put("summary", ManifestBatchAggregation.summarize(plan));
            if (operation.equals("init")) result.put("initialCheckpoint", read(initial));
            if (output != null) result.put("path", output.getAbsolutePath()).put("bytes", output.length()).put("sha256", RescueFiles.sha256(output));
            return result;
        }
    }

    private static File event(File directory, int index) { return new File(directory, String.format(Locale.US, "event-%05d.json", index)); }
    private static JSONObject readEvent(File file) throws Exception {
        JSONObject envelope = read(file), event = envelope.getJSONObject("event");
        if (envelope.length() != 2 || !CollectionPlan.digest(event).equals(envelope.getString("sha256"))) throw new IOException("批次原件摘要变化");
        return event;
    }
    private static JSONObject read(File file) throws Exception {
        if (!file.isFile() || !file.equals(file.getCanonicalFile())) throw new IOException("原件缺失或是链接");
        return new JSONObject(RescueFiles.read(file, LIMIT));
    }
    private RemoteSystemInventoryJobs() { }
}
