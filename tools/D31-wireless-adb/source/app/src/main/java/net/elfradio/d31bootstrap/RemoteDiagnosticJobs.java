package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 独立诊断任务存档；已有任务只查询原件，绝不通过重扫掩盖中断或坏报告。 */
final class RemoteDiagnosticJobs {
    interface Work { JSONObject run(File directory, JSONObject request) throws Exception; }
    static final int REPORT_LIMIT = 4 * 1024 * 1024;

    static JSONObject execute(File root, String id, JSONObject request, Work work) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{64}") || request == null || work == null)
            throw new IOException("诊断任务参数无效");
        String normalized = canonical(request, 0);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > 16000) throw new IOException("诊断请求过大");
        String requestHash = RemoteProtocol.hash(normalized);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("诊断根目录无法创建");
        try (RandomAccessFile file = new RandomAccessFile(new File(root, "lock"), "rw");
             FileLock lock = RemoteFileLocks.tryExclusive(file.getChannel())) {
            if (lock == null) throw new IOException("已有诊断任务正在执行");
            File job = new File(root, id);
            if (job.exists()) {
                File savedRequest = new File(job, "request.json");
                if (!savedRequest.isFile() || !requestHash.equals(RemoteProtocol.hash(RescueFiles.read(savedRequest, 16000))))
                    throw new IOException("任务编号已有不同请求或原件缺失");
                File receipt = new File(job, "result.json");
                if (!receipt.isFile()) return new JSONObject().put("id", id).put("state", "interrupted")
                        .put("reason", "原任务未完成，保留现场且不重新采集");
                JSONObject result = new JSONObject(RescueFiles.read(receipt, 16000));
                if (!id.equals(result.optString("id")) || !("completed".equals(result.optString("state"))
                        || "failed".equals(result.optString("state")))) throw new IOException("诊断回执身份或状态不符");
                if ("completed".equals(result.optString("state"))) {
                    File report = new File(job, "report.json");
                    if (!report.getAbsolutePath().equals(result.optString("path"))
                            || !report.isFile() || report.length() > REPORT_LIMIT || report.length() != result.getLong("bytes")
                            || !RescueFiles.sha256(report).equals(result.getString("sha256")))
                        throw new IOException("已存诊断报告与回执不符");
                }
                return result;
            }
            File[] existing = root.listFiles(File::isDirectory);
            if (existing == null || existing.length >= 32 || root.getUsableSpace() < 64L * 1024 * 1024)
                throw new IOException("诊断存档数量或可用空间达到限制");
            if (!job.mkdir()) throw new IOException("诊断目录创建冲突");
            RescueFiles.write(new File(job, "request.json"), normalized);
            try {
                JSONObject report = work.run(job, new JSONObject(normalized));
                String text = report.toString();
                if (text.getBytes(StandardCharsets.UTF_8).length > REPORT_LIMIT) throw new IOException("诊断报告超过保存上限");
                File output = new File(job, "report.json");
                RescueFiles.write(output, text);
                JSONObject receipt = new JSONObject().put("id", id).put("state", "completed")
                        .put("path", output.getAbsolutePath()).put("bytes", output.length())
                        .put("sha256", RescueFiles.sha256(output)).put("systemConsistency", "NOT_ASSESSED");
                RescueFiles.write(new File(job, "result.json"), receipt.toString());
                return receipt;
            } catch (Exception failure) {
                JSONObject receipt = new JSONObject().put("id", id).put("state", "failed")
                        .put("reason", failure.getClass().getSimpleName()).put("systemConsistency", "NOT_ASSESSED");
                RescueFiles.write(new File(job, "result.json"), receipt.toString());
                return receipt;
            }
        }
    }

    private static String canonical(Object value, int depth) throws Exception {
        if (depth > 8) throw new IOException("诊断请求过深");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.length() > 64) throw new IOException("诊断对象过大");
            List<String> keys = new ArrayList<>();
            Iterator<String> names = object.keys(); while (names.hasNext()) keys.add(names.next());
            Collections.sort(keys); StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(canonical(key, depth + 1)).append(':').append(canonical(object.get(key), depth + 1));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; if (array.length() > 64) throw new IOException("诊断数组过大");
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(','); result.append(canonical(array.get(i), depth + 1));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) {
            if (((String) value).length() > 4096) throw new IOException("诊断字段过长");
            return JSONObject.quote((String) value);
        }
        if (value == JSONObject.NULL) return "null";
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        throw new IOException("不支持的诊断字段类型");
    }
    private RemoteDiagnosticJobs() { }
}
