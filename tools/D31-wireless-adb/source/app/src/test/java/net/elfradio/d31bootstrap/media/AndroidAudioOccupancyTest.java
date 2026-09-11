package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 合成状态验证否决和缓存生命周期，不宣称原厂通话或录音所有者已实测。 */
public class AndroidAudioOccupancyTest {
    static JSONObject idle() throws Exception {
        JSONArray streams = new JSONArray(), sources = new JSONArray();
        for (int i = 0; i < 10; i++) streams.put(new JSONObject().put("stream", i).put("active", false).put("remote_active", false));
        for (int i = 0; i < 9; i++) sources.put(new JSONObject().put("source", i).put("active", false));
        return new JSONObject().put("cellular", new JSONObject().put("call_state", 0).put("phone_count", 1))
                .put("nexui", new JSONObject().put("resolved", true).put("statuses", new JSONArray().put("IDLE")))
                .put("audio", new JSONObject().put("mode", 0).put("focus_gain", 0).put("streams", streams).put("sources", sources));
    }
    static AndroidAudioOccupancy.Observation observe(JSONObject value) { return AndroidAudioOccupancy.evaluate(value, 100, 110); }
    static void unknown(JSONObject value) { assertEquals(AndroidAudioOccupancy.State.UNKNOWN, observe(value).overall()); }
    static void busy(JSONObject value) { assertEquals(AndroidAudioOccupancy.State.BUSY, observe(value).overall()); }

    @Test public void allThreeIndependentSourcesRequiredForIdle() throws Exception {
        assertEquals(AndroidAudioOccupancy.State.IDLE, observe(idle()).overall());
        for (String source : new String[]{"cellular", "nexui", "audio"}) { JSONObject value = idle(); value.remove(source); unknown(value); }
    }
    @Test public void normalAudioModeAndRegisteredSipNeverSuffice() throws Exception {
        JSONObject value = idle();
        value.getJSONObject("nexui").remove("statuses"); value.getJSONObject("nexui").put("registered", true);
        unknown(value);
        value = idle(); value.getJSONObject("audio").remove("sources"); unknown(value);
    }
    @Test public void incomingOffhookAndHeldSipAreBusy() throws Exception {
        for (int state : new int[]{1, 2}) {
            JSONObject value = idle(); value.getJSONObject("cellular").put("call_state", state); busy(value);
        }
        for (String state : new String[]{"INCOMING", "OUTGOING", "CONNECTED", "HOLDING", "DISCONNECTING"}) {
            JSONObject value = idle(); value.getJSONObject("nexui").put("statuses", new JSONArray().put("IDLE").put(state)); busy(value);
        }
    }
    @Test public void unknownSipRowsAndEmptyCursorDoNotMeanIdle() throws Exception {
        for (JSONArray states : new JSONArray[]{new JSONArray(), new JSONArray().put("UNKNOWN"), new JSONArray().put("DISCONNECTED"),
                new JSONArray().put("IDLE").put(JSONObject.NULL)}) {
            JSONObject value = idle(); value.getJSONObject("nexui").put("statuses", states); unknown(value);
        }
    }
    @Test public void busySourceWinsEvenWhenAnotherSourceIsUnreadable() throws Exception {
        JSONObject value = idle(); value.remove("nexui"); value.getJSONObject("cellular").put("call_state", 2); busy(value);
    }
    @Test public void multiSimAndPermissionFailureAreNotSingleSimIdle() throws Exception {
        JSONObject value = idle(); value.getJSONObject("cellular").put("phone_count", 2); unknown(value);
        value = idle(); value.getJSONObject("cellular").put("error_type", "SecurityException"); unknown(value);
        value = idle(); value.getJSONObject("nexui").put("error_type", "DeadObjectException"); unknown(value);
    }
    @Test public void activeRecordingHasNoUnsafeSelfExemption() throws Exception {
        JSONObject value = idle(); value.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active", true);
        value.put("own_media_running", true); busy(value);
        assertEquals("GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED", observe(value).reason);
    }
    @Test public void streamFocusAndModeEachVetoIndependently() throws Exception {
        JSONObject value = idle(); value.getJSONObject("audio").getJSONArray("streams").getJSONObject(4).put("active", true); busy(value);
        value = idle(); value.getJSONObject("audio").getJSONArray("streams").getJSONObject(3).put("remote_active", true); busy(value);
        value = idle(); value.getJSONObject("audio").put("focus_gain", 1); busy(value);
        value = idle(); value.getJSONObject("audio").put("mode", 3); busy(value);
    }
    @Test public void malformedOrPartialAudioCoverageFailsClosed() throws Exception {
        JSONObject value = idle(); value.getJSONObject("audio").getJSONArray("sources").remove(8); unknown(value);
        value = idle(); value.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active", "false"); unknown(value);
        value = idle(); value.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("source", 0); unknown(value);
        value = idle(); value.getJSONObject("audio").put("system_error_type", "NoSuchMethodException"); unknown(value);
        value = idle(); value.getJSONObject("audio").put("mode", "0"); unknown(value);
    }
    @Test public void slowAndReversedClockSamplesAreRejected() throws Exception {
        assertEquals(AndroidAudioOccupancy.State.UNKNOWN, AndroidAudioOccupancy.evaluate(idle(), 100, 1601).overall());
        assertEquals(AndroidAudioOccupancy.State.UNKNOWN, AndroidAudioOccupancy.evaluate(idle(), 100, 99).overall());
    }
    @Test public void constructionDoesNotReadAndClosedGuardCannotRestart() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { reads.incrementAndGet(); return idle(); }, () -> 100);
        assertEquals(0, reads.get()); assertEquals("NOT_STARTED", guard.snapshot().getString("reason"));
        denied(guard); guard.close(); assertEquals("CLOSED", guard.snapshot().getString("reason"));
        try { guard.start(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(0, reads.get());
    }
    @Test public void highFrequencyReadsUseCacheAndMonotonicAge() throws Exception {
        final AtomicLong now = new AtomicLong(100);
        final AtomicInteger reads = new AtomicInteger();
        AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { reads.incrementAndGet(); return idle(); }, now::get);
        try {
            guard.start(); awaitSample(guard);
            for (int i = 0; i < 200; i++) { guard.start(); guard.requireIdle(); guard.snapshot(); }
            assertEquals(1, reads.get());
            now.set(4101); denied(guard); assertEquals("STALE_SAMPLE", guard.snapshot().getString("reason"));
            assertEquals(1, reads.get());
        } finally { guard.close(); }
    }
    @Test public void failedRefreshReplacesOldIdleAndSnapshotCannotMutateCache() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> {
            if (reads.incrementAndGet() > 1) throw new IOException("合成读取拒绝"); return idle();
        }, () -> 100);
        try {
            guard.start(); awaitSample(guard);
            guard.snapshot().put("state", "BUSY"); guard.requireIdle();
            guard.refresh(); denied(guard); assertEquals("SOURCE_READ_FAILED", guard.snapshot().getString("reason"));
        } finally { guard.close(); }
    }
    @Test public void blockedBinderDoesNotCreateReplacementWorkersOrBlockClose() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> {
            reads.incrementAndGet(); entered.countDown();
            try {
                while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { } }
                return idle();
            } finally { finished.countDown(); }
        }, () -> 100);
        try {
            guard.start(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) { guard.start(); denied(guard); }
            long before = System.nanoTime(); guard.close();
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1));
            assertEquals(1, reads.get()); denied(guard);
        } finally { guard.close(); release.countDown(); assertTrue(finished.await(1, TimeUnit.SECONDS)); }
    }
    @Test public void firstWaitIsBoundedAndDoesNotStartSampling() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { reads.incrementAndGet(); return idle(); }, () -> 100)) {
            for (long timeout : new long[]{0, -1, 1501, Long.MAX_VALUE}) {
                try { guard.awaitFirstSample(timeout); fail(); } catch (IllegalArgumentException expected) { }
            }
            assertFalse(guard.awaitFirstSample(1)); assertEquals(0, reads.get());
            assertEquals("NOT_STARTED", guard.snapshot().getString("reason"));
        }
    }
    @Test public void firstWaitRejectsMainThreadWithoutReading() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { reads.incrementAndGet(); return idle(); }, () -> 100, Thread.currentThread())) {
            try { guard.awaitFirstSample(1500); fail(); } catch (IllegalStateException expected) { }
            assertEquals(0, reads.get());
        }
    }
    @Test public void firstWaitTimesOutThenObservesSameRefresh() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> {
            reads.incrementAndGet(); entered.countDown(); release.await(); return idle();
        }, () -> 100)) {
            guard.start(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(guard.awaitFirstSample(20)); denied(guard);
            assertEquals("NO_SAMPLE", guard.snapshot().getString("reason"));
            release.countDown(); assertTrue(guard.awaitFirstSample(1000)); guard.requireIdle();
            assertTrue(guard.awaitFirstSample(1)); assertEquals(1, reads.get());
        } finally { release.countDown(); }
    }
    @Test public void firstFailureReleasesWaitButNeverGrantsIdle() throws Exception {
        try (AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { throw new IOException("合成首次失败"); }, () -> 100)) {
            guard.start(); assertTrue(guard.awaitFirstSample(1000));
            assertEquals("SOURCE_READ_FAILED", guard.snapshot().getString("reason")); denied(guard);
        }
        try (AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> {
            JSONObject value = idle(); value.getJSONObject("cellular").put("call_state", 2); return value;
        }, () -> 100)) {
            guard.start(); assertTrue(guard.awaitFirstSample(1000));
            assertEquals("BUSY", guard.snapshot().getString("state")); denied(guard);
        }
    }
    @Test public void closeReleasesWaitingCallerWithoutFirstSample() throws Exception {
        AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { throw new AssertionError("等待不得启动采样"); }, () -> 100);
        CountDownLatch waiting = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<Object>();
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try { result.set(guard.awaitFirstSample(1500)); } catch (Throwable failure) { result.set(failure); }
            finally { done.countDown(); }
        });
        try {
            waiter.start(); assertTrue(waiting.await(1, TimeUnit.SECONDS)); guard.close();
            assertTrue(done.await(1, TimeUnit.SECONDS)); assertEquals(Boolean.TRUE, result.get());
            assertEquals("CLOSED", guard.snapshot().getString("reason")); denied(guard);
        } finally { guard.close(); waiter.interrupt(); waiter.join(1000); }
    }
    @Test public void interruptionPropagatesWithoutStartingSampling() throws Exception {
        try (AndroidAudioOccupancy guard = new AndroidAudioOccupancy(() -> { throw new AssertionError(); }, () -> 100)) {
            Thread.currentThread().interrupt();
            try { guard.awaitFirstSample(1500); fail(); } catch (InterruptedException expected) { }
            finally { Thread.interrupted(); }
            assertEquals("NOT_STARTED", guard.snapshot().getString("reason"));
        }
    }
    private static void awaitSample(AndroidAudioOccupancy guard) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while ("NO_SAMPLE".equals(guard.snapshot().getString("reason")) && System.nanoTime() < deadline) Thread.sleep(2);
        guard.requireIdle();
    }
    private static void denied(AndroidAudioOccupancy guard) throws Exception {
        try { guard.requireIdle(); fail("未知或占用必须拒绝"); } catch (IOException expected) { }
    }
}
