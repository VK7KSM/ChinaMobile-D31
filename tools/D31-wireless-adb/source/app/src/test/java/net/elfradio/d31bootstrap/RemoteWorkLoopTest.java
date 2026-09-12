package net.elfradio.d31bootstrap;

import org.junit.Test;
import java.io.IOException;
import java.util.EnumMap;
import static org.junit.Assert.*;

public class RemoteWorkLoopTest {
    private static final class Fixture implements RemoteWorkLoop.Actions {
        long now;
        boolean stopped, stopAfterReport, requestDuringReport, failJournal;
        int reportHttp, taskFailures, journals;
        long reportDuration, retryAfter;
        long syncAt = -1;
        int syncHttp;
        final EnumMap<RemoteWorkLoop.Stage, Integer> calls = new EnumMap<>(RemoteWorkLoop.Stage.class);
        final RemoteWorkLoop loop = new RemoteWorkLoop(() -> now, this);
        int count(RemoteWorkLoop.Stage stage) { return calls.containsKey(stage) ? calls.get(stage) : 0; }
        public long run(RemoteWorkLoop.Stage stage) throws Exception {
            calls.put(stage, count(stage) + 1);
            if (stage == RemoteWorkLoop.Stage.SYNC) {
                syncAt = now;
                if (syncHttp != 0) throw new RemoteHttp.Rejected(syncHttp, "sync unavailable", 0);
            }
            if (stage == RemoteWorkLoop.Stage.TASKS && taskFailures-- > 0) throw new IOException("receipt offline");
            if (stage == RemoteWorkLoop.Stage.REPORT) {
                now += reportDuration;
                if (requestDuringReport) { loop.request(stage); requestDuringReport = false; }
                if (stopAfterReport) stopped = true;
                if (reportHttp != 0) throw new RemoteHttp.Rejected(reportHttp, "test response", retryAfter);
            }
            return stage == RemoteWorkLoop.Stage.REPORT || stage == RemoteWorkLoop.Stage.SYNC ? 900000 : 5000;
        }
        public boolean stopping() { return stopped; }
        public void changed() throws Exception {
            journals++;
            if (failJournal) throw new IOException("state storage unavailable");
        }
    }

    @Test public void pushedMediaSyncRunsBeforeSlowReportWithoutDuplicateRequest() {
        Fixture f = new Fixture(); f.reportDuration = 81000;
        f.loop.request(RemoteWorkLoop.Stage.SYNC);
        f.loop.tick();
        assertEquals(0, f.syncAt);
        assertEquals(1, f.count(RemoteWorkLoop.Stage.SYNC));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
    }

    @Test public void pushedSyncStillHonorsFailureBackoff() {
        Fixture f = new Fixture(); f.syncHttp = 503;
        f.loop.request(RemoteWorkLoop.Stage.SYNC); f.loop.tick();
        f.now = 1000;
        f.loop.request(RemoteWorkLoop.Stage.SYNC); f.loop.tick();
        assertEquals(1, f.count(RemoteWorkLoop.Stage.SYNC));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
    }

    @Test public void repeatedReportFailureDoesNotStarveOtherWork() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 500;
        f.loop.tick();
        assertEquals(1, f.count(RemoteWorkLoop.Stage.PUSH));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.SYNC));
        for (int i = 1; i < 30; i++) {
            f.now = i * 1000; f.loop.request(RemoteWorkLoop.Stage.REPORT); f.loop.tick();
        }
        assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(6, f.count(RemoteWorkLoop.Stage.TASKS));
        assertEquals(6, f.count(RemoteWorkLoop.Stage.PUSH));
        assertEquals(500, f.loop.snapshot().getJSONObject("report").getInt("http_status"));
        f.now = 30000; f.loop.tick();
        assertEquals(2, f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(60000, f.loop.snapshot().getJSONObject("report").getLong("retry_in_ms"));
    }

    @Test public void serverWaitCannotBeShortenedByNotificationsOrIndependentWork() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 503; f.retryAfter = 900000; f.loop.tick();
        for (int i=1; i<20; i++) {
            f.now=i*40000; f.loop.request(RemoteWorkLoop.Stage.REPORT); f.loop.tick();
        }
        assertEquals(1,f.count(RemoteWorkLoop.Stage.REPORT));
        assertTrue(f.count(RemoteWorkLoop.Stage.PUSH)>1);
        f.now=899999; f.loop.tick(); assertEquals(1,f.count(RemoteWorkLoop.Stage.REPORT));
        f.now=900000; f.reportHttp=0; f.loop.tick(); assertEquals(2,f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(0,f.loop.snapshot().getJSONObject("report").getInt("failures"));
    }

    @Test public void receiptFailureDoesNotSkipReportOrConnection() {
        Fixture f = new Fixture(); f.taskFailures = 1; f.loop.tick();
        assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.PUSH));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.SYNC));
    }

    @Test public void recoveryResetsBackoffAndRestoresNormalCadence() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 500; f.loop.tick();
        f.now = 30000; f.reportHttp = 0; f.loop.tick();
        assertEquals(0, f.loop.snapshot().getJSONObject("report").getInt("failures"));
        f.now = 31000; f.loop.tick();
        assertEquals(2, f.count(RemoteWorkLoop.Stage.REPORT));
        f.loop.request(RemoteWorkLoop.Stage.REPORT); f.loop.tick();
        assertEquals(3, f.count(RemoteWorkLoop.Stage.REPORT));
    }

    @Test public void stopBetweenStepsSkipsRemainingWork() {
        Fixture f = new Fixture(); f.stopAfterReport = true; f.loop.tick();
        assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(0, f.count(RemoteWorkLoop.Stage.PUSH));
        assertEquals(0, f.count(RemoteWorkLoop.Stage.SYNC));
    }

    @Test public void requestReceivedDuringReportIsNotLost() {
        Fixture f = new Fixture(); f.requestDuringReport = true; f.loop.tick();
        f.now = 1000; f.loop.tick();
        assertEquals(2, f.count(RemoteWorkLoop.Stage.REPORT));
    }

    @Test public void retryWindowBeginsAfterSlowRequestFinishes() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 500; f.reportDuration = 35000; f.loop.tick();
        assertEquals(30000, f.loop.snapshot().getJSONObject("report").getLong("retry_in_ms"));
        f.now = 64000; f.loop.tick(); assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
        f.now = 65000; f.loop.tick(); assertEquals(2, f.count(RemoteWorkLoop.Stage.REPORT));
    }

    @Test public void permanentRejectionPreservesOtherWorkWithBoundedRetry() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 403; f.loop.tick();
        assertEquals(300000, f.loop.snapshot().getJSONObject("report").getLong("retry_in_ms"));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.PUSH));
        f.now = 5000; f.loop.request(RemoteWorkLoop.Stage.REPORT); f.loop.tick();
        assertEquals(1, f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(2, f.count(RemoteWorkLoop.Stage.PUSH));
    }

    @Test public void loggingFailureDoesNotSkipIndependentSteps() {
        Fixture f = new Fixture(); f.failJournal = true; f.loop.tick();
        assertEquals(RemoteWorkLoop.Stage.values().length, f.journals);
        assertEquals(1, f.count(RemoteWorkLoop.Stage.PUSH));
        assertEquals(1, f.count(RemoteWorkLoop.Stage.SYNC));
    }

    @Test public void repeatedFailuresCapRetryInterval() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 429;
        for (int i = 0; i < 20; i++) {
            f.loop.tick();
            long delay = f.loop.snapshot().getJSONObject("report").getLong("retry_in_ms");
            assertTrue(delay >= 30000 && delay <= 300000);
            f.now += delay;
        }
        assertEquals(20, f.count(RemoteWorkLoop.Stage.REPORT));
        assertEquals(16, f.loop.snapshot().getJSONObject("report").getInt("failures"));
    }

    @Test public void summaryKeepsFailureVisibleWhenOtherStagesSucceed() throws Exception {
        Fixture f = new Fixture(); f.reportHttp = 500; f.loop.tick();
        org.json.JSONObject summary = RemoteWorkLoop.summary(f.loop.snapshot(), false, true);
        assertEquals("work_retry_pending", summary.getString("phase"));
        assertEquals(500, summary.getInt("http_status"));
        assertEquals("report:Rejected", summary.getString("detail"));
        f.reportHttp = 0; f.now = 30000; f.loop.tick();
        assertEquals("work_waiting", RemoteWorkLoop.summary(f.loop.snapshot(), false, true).getString("phase"));
        assertEquals("work_ready", RemoteWorkLoop.summary(f.loop.snapshot(), true, true).getString("phase"));
    }
}
