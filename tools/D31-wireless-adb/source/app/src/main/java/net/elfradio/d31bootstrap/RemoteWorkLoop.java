package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.util.EnumMap;

/** 单线程分项退避；一个接口失败不跳过其它业务，也不增加MQTT实例。 */
final class RemoteWorkLoop {
    enum Stage { TASKS, FILES, REPORT, PUSH, SYNC }
    interface Clock { long now(); }
    interface Actions {
        long run(Stage stage) throws Exception;
        boolean stopping();
        void changed() throws Exception;
    }
    private static final class Slot {
        long next, retry;
        int failures, http;
        boolean requested;
        String error = "";
    }
    private final EnumMap<Stage, Slot> slots = new EnumMap<>(Stage.class);
    private final Clock clock;
    private final Actions actions;

    RemoteWorkLoop(Clock clock, Actions actions) {
        this.clock = clock; this.actions = actions;
        for (Stage stage : Stage.values()) slots.put(stage, new Slot());
    }

    void request(Stage stage) { slots.get(stage).requested = true; }

    void tick() {
        // MQTT触发的同步先取得短期媒体邀请，不能排在完整报告或维护任务后。
        boolean prioritySync = slots.get(Stage.SYNC).requested;
        if (prioritySync && !actions.stopping()) run(Stage.SYNC);
        for (Stage stage : Stage.values()) {
            if (actions.stopping()) return;
            if (prioritySync && stage == Stage.SYNC) continue;
            run(stage);
        }
    }

    private void run(Stage stage) {
            Slot slot = slots.get(stage);
            long now = clock.now();
            if (now < slot.retry || (!slot.requested && now < slot.next)) return;
            slot.requested = false;
            try {
                long delay = actions.run(stage);
                slot.next = clock.now() + Math.max(1000, delay);
                slot.retry = 0; slot.failures = 0; slot.http = 0; slot.error = "";
            } catch (Exception error) {
                slot.failures = Math.min(16, slot.failures + 1);
                slot.http = error instanceof RemoteHttp.Rejected ? ((RemoteHttp.Rejected) error).status : 0;
                slot.error = error.getClass().getSimpleName();
                long delay = Math.min(300000L, 30000L << Math.min(4, slot.failures - 1));
                // 已有身份的永久性接口拒绝不清凭据、不退出重注册，也不高频重试。
                if (slot.http >= 400 && slot.http < 500 && slot.http != 408 && slot.http != 429) delay = 300000;
                if (error instanceof RemoteHttp.Rejected)
                    delay = Math.max(delay, ((RemoteHttp.Rejected) error).retryAfterMillis);
                slot.retry = clock.now() + delay;
                slot.next = slot.retry; slot.requested = true;
            }
            try { actions.changed(); }
            catch (Exception unavailable) { System.err.println("远程分项状态暂时不可写"); }
    }

    JSONObject snapshot() throws Exception {
        JSONObject result = new JSONObject();
        long now = clock.now();
        for (Stage stage : Stage.values()) {
            Slot slot = slots.get(stage);
            result.put(stage.name().toLowerCase(java.util.Locale.US), new JSONObject()
                    .put("failures", slot.failures).put("http_status", slot.http).put("error", slot.error)
                    .put("retry_in_ms", Math.max(0, slot.retry - now)).put("requested", slot.requested));
        }
        return result;
    }

    static JSONObject summary(JSONObject stages, boolean reportAcknowledged, boolean mqtt) throws Exception {
        for (Stage stage : Stage.values()) {
            String name = stage.name().toLowerCase(java.util.Locale.US);
            JSONObject slot = stages.getJSONObject(name);
            if (slot.getInt("failures") > 0) return new JSONObject().put("phase", "work_retry_pending")
                    .put("http_status", slot.getInt("http_status")).put("detail", name + ":" + slot.getString("error"));
        }
        return new JSONObject().put("phase", reportAcknowledged && mqtt ? "work_ready" : "work_waiting")
                .put("http_status", 0).put("detail", "");
    }
}
