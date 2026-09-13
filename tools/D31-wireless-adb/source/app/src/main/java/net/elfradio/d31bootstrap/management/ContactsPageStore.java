package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;

/** 单份已完成LOCAL回复的进程内缓存；不持久化个人值，不把过期页重新解释为新查询。 */
final class ContactsPageStore implements AutoCloseable {
    static final long TTL_MS = 120000;
    interface Clock { long now(); }
    private final Clock clock;
    private ContactsNexui.Snapshot snapshot;
    private String hash, boot, id;
    private long created;
    ContactsPageStore(Clock clock) { this.clock = clock; }

    synchronized void requireEmpty() throws Exception {
        expire();
        if (snapshot != null) throw new IOException("CONTACTS_SNAPSHOT_BUSY");
    }

    /** 调用者仍拥有输入对象；只有成功返回后才转移所有权。 */
    synchronized JSONObject publish(ContactsNexui.Snapshot value, String digest, String bootId,
            JSONObject receipt) throws Exception {
        requireEmpty(); ContactsAppContract.digest(digest);
        if (bootId == null || !bootId.matches("[a-f0-9-]{36}")) throw new IOException("CONTACTS_BOOT_MISMATCH");
        if (value == null || !Boolean.TRUE.equals(receipt.opt("ok"))
                || !ContactsAppWatchdog.cleanupConfirmed(receipt)) throw new IOException("CONTACTS_SNAPSHOT_NOT_RELEASED");
        JSONObject meta = value.metadata();
        if (!"LOCAL".equals(meta.getString("contact_type")) || !meta.getBoolean("list_complete")
                || !meta.getBoolean("end_observed")) throw new IOException("CONTACTS_SNAPSHOT_INCOMPLETE");
        long now = clock.now();
        if (now < 0) throw new IOException("CONTACTS_CLOCK_INVALID");
        JSONObject descriptor = descriptor(meta);
        snapshot = value; hash = digest; boot = bootId; id = meta.getString("snapshot_id"); created = now;
        return descriptor;
    }

    static JSONObject descriptor(JSONObject meta) throws Exception {
        return new JSONObject(meta.toString()).put("retention_ms", TTL_MS).put("max_page_records", ContactsNexui.MAX_PAGE)
                .put("page_consistency", "IMMUTABLE_RECEIVED_REPLY").put("storage", "APP_PROCESS_MEMORY")
                .put("cross_process_restart", false).put("cross_boot", false);
    }

    synchronized JSONObject page(String digest, String bootId, String snapshotId, int offset, int limit,
            SystemManagement.Control control) throws Exception {
        require(digest, bootId, snapshotId);
        if (control == null) throw new IOException("CONTACTS_CONTROL_REQUIRED");
        ContactsPageCommand.pageArguments(snapshotId, offset, limit);
        if (offset > snapshot.metadata().getInt("record_count")) throw new IOException("CONTACTS_PAGE_ARGUMENTS_INVALID");
        try {
            control.check();
            JSONObject result = snapshot.page(offset, limit, control);
            control.check();
            // 生成期间也可能越过保留期限；不得把已过期结果发给下一页消费者。
            require(digest, bootId, snapshotId);
            return descriptor(result);
        } catch (Exception failed) { close(); throw failed; }
    }

    synchronized void release(String digest, String bootId, String snapshotId) throws Exception {
        require(digest, bootId, snapshotId); close();
    }
    private void require(String digest, String bootId, String snapshotId) throws Exception {
        expire();
        ContactsAppContract.digest(digest);
        if (snapshot == null) throw new IOException("CONTACTS_SNAPSHOT_GONE");
        if (!hash.equals(digest) || !boot.equals(bootId) || !id.equals(snapshotId))
            throw new IOException("CONTACTS_SNAPSHOT_MISMATCH");
    }
    synchronized void expire() {
        long now = clock.now();
        if (snapshot != null && (now < created || now - created >= TTL_MS)) close();
    }
    public synchronized void close() {
        if (snapshot != null) snapshot.close();
        snapshot = null; hash = null; boot = null; id = null;
    }
}
