package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;

/** 持久会话适配边界；只传既有协议数据，不把控制器状态机带入硬件实现。 */
public final class PreparedSessionPort {
    private PreparedSessionPort() { }
    public interface Events {
        void changed();
        void failed(String code);
    }
    public interface Peer extends AutoCloseable {
        void open() throws Exception;
        JSONObject createPublish(JSONObject newResult) throws Exception;
        void applyPublish(JSONObject publishResult) throws Exception;
        JSONObject subscribe(JSONObject subscribeResult) throws Exception;
        void negotiationComplete() throws Exception;
        boolean transportReady();
        void activate(long operation, String mode, String camera) throws Exception;
        boolean operationReady(long operation);
        /** 非阻塞失活指定操作；不得取消持久连接，硬件退出由deactivate确认。 */
        default void invalidateOperation(long operation) { }
        void deactivate(long operation) throws Exception;
        void switchCamera(long operation, String camera) throws Exception;
        JSONObject snapshot() throws Exception;
        void cancel();
        void close() throws Exception;
    }
    /** 每次激活创建一个对象，编号和取消令牌不可转移给后续操作。 */
    public static final class Operation {
        public final long number;
        public final String mode, camera, reportId;
        public final Cancellation cancellation = new Cancellation();
        Operation(long number, String mode, String camera, String reportId) {
            this.number = number;this.mode = mode;this.camera = camera;this.reportId = reportId;
        }
    }
    public interface OperationEvents extends Events {
        /** 仅status/result/photo_preview，控制器核验原操作并绑定operation。 */
        void message(JSONObject value);
    }
    public interface Extra extends AutoCloseable {
        void start() throws Exception;
        boolean ready();
        void switchCamera(String camera) throws Exception;
        /** 必须非阻塞；close负责确认本操作任务和硬件已经退出。 */
        void cancel();
        void close() throws Exception;
    }
    public interface Factory {
        Peer createPeer(Events events) throws Exception;
        Extra createExtra(Operation operation, OperationEvents events) throws Exception;
    }
}
