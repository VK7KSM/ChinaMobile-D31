package net.elfradio.d31bootstrap.repair;

import java.io.File;
import java.util.Set;

/** 仅执行合同，没有默认系统执行器、网络获取或自动调度。 */
public interface RepairPlatform {
    interface Lease extends AutoCloseable { @Override void close() throws Exception; }

    final class FileState {
        public enum Kind { REGULAR, MISSING, OTHER }
        public final Kind kind;
        public final String sha256;
        public final long bytes;
        private FileState(Kind kind, String sha256, long bytes) { this.kind = kind; this.sha256 = sha256; this.bytes = bytes; }
        public static FileState regular(String hash, long bytes) { return new FileState(Kind.REGULAR, RepairPlan.hash(hash), RepairPlan.size(bytes)); }
        public static FileState missing() { return new FileState(Kind.MISSING, "", 0); }
        public static FileState other() { return new FileState(Kind.OTHER, "", 0); }
        public boolean matches(String hash, long size) { return kind == Kind.REGULAR && bytes == size && sha256.equals(hash); }
    }

    /** 父任务桥接现有全局维护锁；占用返回null，异常不得降级为无锁执行。 */
    Lease acquire(String taskId) throws Exception;
    boolean approved(String planSha256) throws Exception;
    String deviceClass() throws Exception;
    String buildFingerprint() throws Exception;
    /** 可信逻辑路径清单，只映射普通受控文件；不得映射分区、账号、凭据或校准数据。 */
    Set<String> allowedPaths() throws Exception;
    long availableBytes() throws Exception;
    /** 只读；必须拒绝链接、目录和非常规文件，采集期间变化不可伪装成稳定摘要。 */
    FileState inspect(String path) throws Exception;
    /** destination必须是新文件；不得覆盖已有备份或暂存件。 */
    void backup(RepairPlan.Change change, File destination) throws Exception;
    void stage(RepairPlan.Change change, File destination) throws Exception;
    /**
     * 校验实际目标仍等于expected后原子切换；保持内容文件原件，不接受任意shell。
     * 适配器必须阻止检查与切换之间的并发改写，拒绝链接及非常规文件。
     * 内容和目标在维护锁下保持稳定；普通复制后删除原文件不满足此合同。
     */
    void replace(String path, File content, FileState expected) throws Exception;
    /** 可重复的只读生效检查；不得发命令、重载服务或把差异报告直接当根因或成功。 */
    boolean verify(RepairPlan plan) throws Exception;
    /** 原像内容之外的恢复核验；有元数据的平台必须确认元数据同样恢复。 */
    default boolean verifyRestored(RepairPlan.Change change) throws Exception { return true; }
}
