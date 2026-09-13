package net.elfradio.d31bootstrap.management;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** 原厂all_contacts分片只读快照；不是Android号码行，也不是新的Web任务。 */
public final class ContactsNexui {
    static final int MAX_RECORDS = 4096, MAX_FRAMES = 128, MAX_FRAME_CHARS = 131072;
    static final int MAX_TOTAL_CHARS = 1048576, MAX_RECORD_CHARS = 8192;
    static final int PAGE_CHARS = 12000, MAX_PAGE = 32;
    static final long WAIT_MS = 8000;

    interface Receiver {
        void frame(String source, String action, int state, String json);
        void failed(String code);
    }
    interface Transport extends AutoCloseable {
        void start(String source, Receiver receiver) throws Exception;
        void close() throws Exception;
    }

    /** 必须在工作线程调用；Context的主Looper必须实际运行以派发ServiceConnection。 */
    public static Snapshot read(Context context, String source, SystemManagement.Control control) throws Exception {
        validateSource(source);
        requireControl(control);
        control.check();
        return collect(new ContactsNexuiAndroid(context), source, control, WAIT_MS);
    }

    public static String validateSource(String source) throws IOException {
        if (!"LOCAL".equals(source) && !"BLUETOOTH".equals(source) && !"EAB".equals(source)
                && !"CONFER".equals(source) && !"FAVORITE".equals(source))
            throw new IOException("原厂通讯录来源无效");
        return source;
    }

    static Snapshot collect(Transport transport, String source, SystemManagement.Control control, long waitMs)
            throws Exception {
        validateSource(source);
        requireControl(control);
        if (waitMs < 1 || waitMs > WAIT_MS) throw new IOException("读取预算无效");
        long started = System.nanoTime();
        Accumulator accumulator = new Accumulator(source, started, waitMs);
        try (Transport owned = transport) {
            control.check();
            owned.start(source, accumulator);
            synchronized (accumulator) {
                while (!accumulator.done) {
                    control.check();
                    long remaining = waitMs - (System.nanoTime() - started) / 1000000;
                    if (remaining <= 0) { accumulator.stop("TIMEOUT"); break; }
                    accumulator.wait(Math.min(50, remaining));
                }
                control.check();
                return accumulator.snapshot((System.nanoTime() - started) / 1000000);
            }
        } finally {
            accumulator.discard();
        }
    }

    static final class Accumulator implements Receiver {
        final String source;
        final long started, waitMs;
        final List<String> records = new ArrayList<>();
        int frames, chars;
        boolean done, startObserved, endObserved;
        String status = "WAITING";
        Accumulator(String source) { this(source, System.nanoTime(), WAIT_MS); }
        Accumulator(String source, long started, long waitMs) {
            this.source = source; this.started = started; this.waitMs = waitMs;
        }

        public synchronized void frame(String type, String action, int state, String json) {
            if (done) return;
            if ((System.nanoTime() - started) / 1000000 >= waitMs) { stop("TIMEOUT"); return; }
            if (!source.equals(type) || !"all_contacts".equals(action)) { failed("PROTOCOL_ERROR"); return; }
            if (state < 0 || state > 2 || (state == 0 && frames != 0) || (state == 1 && frames == 0)) {
                failed("PROTOCOL_ERROR"); return;
            }
            if (frames == MAX_FRAMES) { stop("FRAME_LIMIT"); return; }
            frames++;
            if (state == 0) startObserved = true;
            if (json == null) { failed("PROTOCOL_ERROR"); return; }
            if (json.length() > MAX_FRAME_CHARS || chars + json.length() > MAX_TOTAL_CHARS) {
                stop("TEXT_LIMIT"); return;
            }
            chars += json.length();
            try {
                checkDepth(json);
                JSONTokener parser = new JSONTokener(json);
                Object value = parser.nextValue();
                if (!(value instanceof JSONArray) || parser.nextClean() != 0) throw new IOException();
                JSONArray items = (JSONArray) value;
                for (int i = 0; i < items.length(); i++) {
                    if (!(items.get(i) instanceof JSONObject)) throw new IOException();
                    String record = items.getJSONObject(i).toString();
                    if (record.length() > MAX_RECORD_CHARS) { stop("RECORD_SIZE_LIMIT"); return; }
                    if (records.size() == MAX_RECORDS) { stop("RECORD_LIMIT"); return; }
                    records.add(record);
                }
                if ((System.nanoTime() - started) / 1000000 >= waitMs) { stop("TIMEOUT"); return; }
                if (state == 2) { endObserved = true; stop("COMPLETED"); }
            } catch (Exception invalid) {
                failed("PROTOCOL_ERROR");
            }
        }

        public synchronized void failed(String code) {
            if (done) return;
            records.clear();
            stop("SERVICE_UNAVAILABLE".equals(code) || "SERVICE_DISCONNECTED".equals(code)
                    ? code : "PROTOCOL_ERROR");
        }
        synchronized void stop(String code) { status = code; done = true; notifyAll(); }
        synchronized Snapshot snapshot(long elapsed) {
            return new Snapshot(source, records, status, frames, chars, startObserved, endObserved, elapsed);
        }
        synchronized void discard() { done = true; records.clear(); }
    }

    /** 不可变记录，仅在进程内保留；调用者必须关闭，页游标只适用于本对象的snapshot_id。 */
    public static final class Snapshot implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final String source, status;
        private final List<String> records;
        private final int frames, chars;
        private final boolean start, end;
        private final long sampledAt = System.currentTimeMillis(), elapsed;
        private boolean closed;
        Snapshot(String source, List<String> records, String status, int frames, int chars,
                boolean start, boolean end, long elapsed) {
            this.source = source; this.records = new ArrayList<>(records); this.status = status;
            this.frames = frames; this.chars = chars; this.start = start; this.end = end; this.elapsed = elapsed;
        }
        public synchronized JSONObject metadata() throws Exception {
            ensureOpen();
            return new JSONObject().put("ok", "COMPLETED".equals(status)).put("read_only", true)
                    .put("source", "nexui_messenger").put("owner_package", ContactsNexuiAndroid.PACKAGE)
                    .put("contact_type", source).put("snapshot_id", id).put("status", status)
                    .put("sampled_at_ms", sampledAt).put("elapsed_ms", elapsed).put("record_count", records.size())
                    .put("frames_received", frames).put("received_chars", chars)
                    .put("start_observed", start).put("end_observed", end)
                    .put("list_complete", "COMPLETED".equals(status)).put("contact_values_emitted", false)
                    .put("completion_scope", "SELECTED_SOURCE_ALL_CONTACTS_REPLY")
                    .put("all_sources_complete", false).put("snapshot_consistency", "NOT_PROVIDED_BY_VENDOR")
                    .put("android_equivalence", "NOT_VERIFIED").put("service_implementation", "NOT_DECRYPTED")
                    .put("max_records", MAX_RECORDS).put("max_frames", MAX_FRAMES)
                    .put("max_received_chars", MAX_TOTAL_CHARS).put("wait_budget_ms", WAIT_MS);
        }
        public synchronized JSONObject page(int offset, int limit, SystemManagement.Control control) throws Exception {
            ensureOpen(); requireControl(control); control.check();
            if (offset < 0 || offset > records.size() || limit < 1 || limit > MAX_PAGE)
                throw new IOException("通讯录分页参数无效");
            JSONArray items = new JSONArray();
            int next = offset, used = 0;
            while (next < records.size() && items.length() < limit) {
                control.check();
                String record = records.get(next);
                if (used + record.length() + 1 > PAGE_CHARS) break;
                items.put(new JSONObject(record)); used += record.length() + 1; next++;
            }
            control.check();
            return metadata().put("contact_values_emitted", items.length() != 0).put("items", items)
                    .put("offset", offset).put("next_offset", next).put("has_more", next < records.size())
                    .put("page_complete", next == records.size()).put("cursor_scope", "SAME_SNAPSHOT_ONLY");
        }
        private void ensureOpen() throws IOException { if (closed) throw new IOException("通讯录快照已关闭"); }
        public synchronized void close() { closed = true; records.clear(); }
    }

    private static void requireControl(SystemManagement.Control control) throws IOException {
        if (control == null) throw new IOException("必须提供取消控制器");
    }
    private static void checkDepth(String json) throws IOException {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') { if (++depth > 16) throw new IOException(); }
            else if (c == ']' || c == '}') { if (--depth < 0) throw new IOException(); }
        }
        if (quoted || depth != 0) throw new IOException();
    }
    private ContactsNexui() { }
}
