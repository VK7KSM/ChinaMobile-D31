package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** 日志只追加完整快照，查询不依赖平台或网络。 */
final class RepairJournal {
    interface Writer { void write(File destination, byte[] bytes) throws Exception; }
    static final int MAX_RECORDS = 256;
    final File root;
    final Writer writer;

    RepairJournal(File root, Writer writer) throws IOException {
        this.root = root.getAbsoluteFile(); this.writer = writer;
        RepairFiles.mkdir(this.root);
    }

    final class Guard implements AutoCloseable {
        final RandomAccessFile owner;
        final FileLock lock;
        Guard() throws IOException {
            File file = new File(root, "transaction.lock");
            if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("事务锁不能为链接");
            owner = new RandomAccessFile(file, "rw");
            try {
                lock = owner.getChannel().tryLock();
                if (lock == null) throw new IOException("事务日志被占用");
            } catch (IOException | OverlappingFileLockException error) {
                owner.close(); throw new IOException("事务日志锁未取得", error);
            }
        }
        @Override public void close() throws IOException { try { lock.release(); } finally { owner.close(); } }
    }
    Guard lock() throws IOException { return new Guard(); }

    static final class Loaded {
        final File directory;
        final RepairPlan plan;
        final String digest;
        final JSONArray events;
        JSONObject state;
        String head;
        Loaded(File directory, RepairPlan plan, String head) throws Exception {
            this.directory = directory; this.plan = plan; this.digest = plan.sha256(); this.head = head;
            events = new JSONArray();
            state = new JSONObject().put("phase", "PENDING").put("index", 0).put("attempted", -1)
                    .put("attention", false).put("reason", "PLAN_RECORDED");
        }
    }

    Loaded load(String taskId) throws Exception {
        File directory = new File(root, RepairPlan.token(taskId));
        if (!directory.isDirectory()) throw new FileNotFoundException("任务不存在");
        if (!directory.getAbsoluteFile().equals(directory.getCanonicalFile())) throw new IOException("任务目录不能为链接");
        File planFile = new File(directory, "plan.json");
        byte[] raw = RepairFiles.read(planFile, 128 * 1024);
        RepairPlan plan = RepairPlan.fromJson(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        if (!plan.taskId.equals(taskId)) throw new IOException("任务编号与记录不符");
        Loaded result = new Loaded(directory, plan, RepairFiles.sha256(raw));
        File[] records = directory.listFiles((dir, name) -> name.matches("[0-9]{6}\\.json"));
        if (records == null || records.length > MAX_RECORDS) throw new IOException("事务日志无法枚举或已超限");
        Arrays.sort(records, (a, b) -> a.getName().compareTo(b.getName()));
        for (int index = 0; index < records.length; index++) {
            if (!records[index].getName().equals(String.format(Locale.ROOT, "%06d.json", index)))
                throw new IOException("事务日志序号缺失");
            raw = RepairFiles.read(records[index], 128 * 1024);
            JSONObject event = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (event.getInt("sequence") != index || !event.getString("previous_sha256").equals(result.head)
                    || !event.getString("plan_sha256").equals(result.digest)) throw new IOException("事务日志链不一致");
            result.state = new JSONObject(event.getJSONObject("state").toString()); result.events.put(event);
            result.head = RepairFiles.sha256(raw);
        }
        return result;
    }

    JSONObject append(Loaded job, JSONObject state, long now) throws Exception {
        int sequence = job.events.length();
        if (sequence >= MAX_RECORDS) throw new IOException("事务日志已达上限，停止执行");
        JSONObject event = new JSONObject().put("sequence", sequence).put("time_ms", now)
                .put("previous_sha256", job.head).put("plan_sha256", job.digest)
                .put("state", new JSONObject(state.toString()));
        byte[] raw = event.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > 128 * 1024) throw new IOException("事务日志记录超限");
        writer.write(new File(job.directory, String.format(Locale.ROOT, "%06d.json", sequence)), raw);
        job.events.put(event); job.state = new JSONObject(event.getJSONObject("state").toString()); job.head = RepairFiles.sha256(raw);
        return snapshot(job);
    }

    JSONObject snapshot(Loaded job) throws Exception {
        return new JSONObject().put("schema", 1).put("task_id", job.plan.taskId).put("plan_sha256", job.digest)
                .put("plan", job.plan.toJson())
                .put("state", new JSONObject(job.state.toString())).put("events", new JSONArray(job.events.toString()))
                .put("next_event", job.events.length());
    }
}
