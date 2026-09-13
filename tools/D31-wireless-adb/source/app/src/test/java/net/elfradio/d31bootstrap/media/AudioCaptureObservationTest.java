package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.media.AudioCaptureLifecycleTest.*;

/** 两源归属、真实全局布尔源及其它占用必须同轮相符；不能从BUSY中盲目扣除自己。 */
public final class AudioCaptureObservationTest {
    static JSONObject ownRaw() throws Exception {
        JSONObject raw = AndroidAudioOccupancyTest.idle();
        raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active", true); return raw;
    }
    static AudioCaptureObservation.Sample sample(String f, String p, JSONObject raw, long from) {
        return new AudioCaptureObservation.Sample(new AudioInputOwnership.Dump(f, true, from, from + 10),
                new AudioInputOwnership.Dump(p, true, from + 20, from + 30), raw, from + 30, from + 40);
    }
    static AudioCaptureLifecycle.External evaluate(JSONObject raw) {
        return AudioCaptureObservation.external(sample(F, P, raw, 1000), 555, 42, 1040);
    }
    @Test public void onlyMatchedMicCanBeDiscountedAndOriginalGlobalGuardStaysBusy() throws Exception {
        JSONObject raw = ownRaw(); String original = raw.toString();
        assertEquals(AudioCaptureLifecycle.External.IDLE, evaluate(raw)); assertEquals(original, raw.toString());
        assertEquals(AndroidAudioOccupancy.State.BUSY, AndroidAudioOccupancy.evaluate(raw, 1000, 1040).overall());
    }
    @Test public void ownInputButMicInactiveIsCrossSourceUnknown() throws Exception {
        assertEquals(AudioCaptureLifecycle.External.UNKNOWN, evaluate(AndroidAudioOccupancyTest.idle()));
    }
    @Test public void globalMicWithoutOwnerIsBusyNotOwn() throws Exception {
        assertEquals(AudioCaptureLifecycle.External.BUSY, AudioCaptureObservation.external(sample(
                AudioInputOwnershipTest.FLINGER, AudioInputOwnershipTest.POLICY, ownRaw(), 1000), 555, 42, 1040));
    }
    @Test public void otherPidOrDifferentSessionNeverDiscountsActiveMic() throws Exception {
        AudioCaptureObservation.Sample sample = sample(F, P, ownRaw(), 1000);
        assertEquals(AudioCaptureLifecycle.External.BUSY, AudioCaptureObservation.external(sample, 556, 42, 1040));
        assertEquals(AudioCaptureLifecycle.External.BUSY, AudioCaptureObservation.external(sample, 555, 43, 1040));
    }
    @Test public void mismatchedIoBetweenSourcesIsUnknown() throws Exception {
        assertEquals(AudioCaptureLifecycle.External.UNKNOWN, AudioCaptureObservation.external(
                sample(F, P.replace("Input 18", "Input 19"), ownRaw(), 1000), 555, 42, 1040));
    }
    @Test public void everyOtherSourceIncludingDefaultAliasVetoes() throws Exception {
        for (int i = 0; i < 9; i++) if (i != 1) {
            JSONObject raw = ownRaw(); raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(i).put("active", true);
            assertEquals(AudioCaptureLifecycle.External.BUSY, evaluate(raw));
        }
    }
    @Test public void callSipStreamFocusAndModeCannotBeDiscounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            JSONObject raw = ownRaw(), audio = raw.getJSONObject("audio");
            if (i == 0) raw.getJSONObject("cellular").put("call_state", 2);
            if (i == 1) raw.getJSONObject("nexui").getJSONArray("statuses").put(0, "HOLDING");
            if (i == 2) audio.getJSONArray("streams").getJSONObject(3).put("remote_active", true);
            if (i == 3) audio.put("focus_gain", 1);
            if (i == 4) audio.put("mode", 3);
            assertEquals(AudioCaptureLifecycle.External.BUSY, evaluate(raw));
        }
    }
    @Test public void absentMalformedOrUnreadableSourcesAreUnknown() throws Exception {
        for (int i = 0; i < 5; i++) {
            JSONObject raw = ownRaw(), audio = raw.getJSONObject("audio");
            if (i == 0) audio.getJSONArray("sources").getJSONObject(1).put("active", "true");
            if (i == 1) audio.getJSONArray("sources").getJSONObject(1).put("source", 2);
            if (i == 2) audio.remove("sources");
            if (i == 3) raw.remove("nexui");
            if (i == 4) audio.put("system_error_type", "SecurityException");
            assertEquals(AudioCaptureLifecycle.External.UNKNOWN, evaluate(raw));
        }
    }
    @Test public void staleFutureIncompleteAndSlowSamplesAreUnknown() throws Exception {
        AudioCaptureObservation.Sample value = sample(F, P, ownRaw(), 1000);
        assertEquals(AudioCaptureLifecycle.External.UNKNOWN, AudioCaptureObservation.external(value, 555, 42, 5041));
        assertEquals(AudioCaptureLifecycle.External.UNKNOWN, AudioCaptureObservation.external(value, 555, 42, 1039));
        value = new AudioCaptureObservation.Sample(new AudioInputOwnership.Dump(F, false, 1000, 1010), value.policy, ownRaw(), 1030, 1040);
        assertEquals(AudioCaptureLifecycle.External.UNKNOWN, AudioCaptureObservation.external(value, 555, 42, 1040));
    }
    @Test public void preflightRequiresNoInputAndCombinedWindow() throws Exception {
        AudioCaptureObservation.requireEmpty(sample(AudioInputOwnershipTest.FLINGER, AudioInputOwnershipTest.POLICY,
                AndroidAudioOccupancyTest.idle(), 1000), 555, 1040);
        try { AudioCaptureObservation.requireEmpty(sample(F, P, ownRaw(), 1000), 555, 1040); fail(); }
        catch (IOException expected) { }
        AudioCaptureObservation.Sample value = sample(AudioInputOwnershipTest.FLINGER, AudioInputOwnershipTest.POLICY,
                AndroidAudioOccupancyTest.idle(), 1000);
        value = new AudioCaptureObservation.Sample(value.flinger, value.policy, AndroidAudioOccupancyTest.idle(), 2500, 2540);
        try { AudioCaptureObservation.requireEmpty(value, 555, 2540); fail(); } catch (IOException expected) { }
    }
}
