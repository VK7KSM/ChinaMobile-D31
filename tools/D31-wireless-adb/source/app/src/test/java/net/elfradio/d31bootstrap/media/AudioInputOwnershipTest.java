package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

/** 使用脱敏的结构摘录验证拒绝合同；活动行是故障注入，不是D31活动实证。 */
public final class AudioInputOwnershipTest {
    static final String FLINGER = "mAFSuspend: 0\nmMicMute: 0\nClients:\n"
            + "Notification Clients:\n  pid: 555\nGlobal session refs:\n  session   pid count\n"
            + "Hardware status: 0\nStandby Time mSec: 3000\n"
            + "Output thread 0xabc type 0 (MIXER):\n  Input device: 0 (NONE)\n"
            + "  Audio source: 0 (default)\n  0 Tracks\n  0 Effect Chains\n"
            + "usb_hw version 2.2.0\n  mic_mute: 0\nReroute submix audio module:\n"
            + routes();
    static final String POLICY = "AudioPolicyManager Dump: 0xabc\n"
            + "HW Modules dump:\n  Gain: \0\nInputs dump:\n\nStreams dump:\n"
            + "Registered effects:\nAudio Patches:\n  - owner uid: 555\n"
            + "Voe volume dump:\n  Id Min Max Cur Amplification Device\n"
            + " 0x00000030 0 7 5 55 bluetooth-sco\n";

    private static String routes() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 10; i++) out.append(" route[").append(i)
                .append("] rate in=48000 out=48000, addr=[]\n");
        return out.toString();
    }
    private static AudioInputOwnership.Dump dump(String text) {
        return new AudioInputOwnership.Dump(text, true, 100, 120);
    }
    private static AudioInputOwnership.Result result(String f, String p) {
        return AudioInputOwnership.evaluate(dump(f), dump(p), 555, 42, 130);
    }
    private static void unknown(String f, String p) {
        AudioInputOwnership.Result result = result(f, p);
        assertEquals(result.reason, AudioInputOwnership.State.UNKNOWN, result.state);
        assertEquals(-1, result.activeInputs);
        assertFalse(result.selfExemptionAllowed);
    }

    @Test public void idleStructureIsOnlyInputObservation() {
        AudioInputOwnership.Result result = result(FLINGER, POLICY);
        assertEquals(result.reason, AudioInputOwnership.State.NO_ACTIVE_INPUT, result.state);
        assertEquals(0, result.activeInputs);
        assertFalse(result.selfExemptionAllowed);
        assertFalse(result.atomicReservation);
    }
    @Test public void oldAdbCrCrLfAndNoFinalNewline() {
        assertEquals(AudioInputOwnership.State.NO_ACTIVE_INPUT,
                result(FLINGER.trim().replace("\n", "\r\r\n"),
                        POLICY.trim().replace("\n", "\r\r\n")).state);
    }
    @Test public void notificationPidAndPatchOwnerNeverGrantSelfExemption() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n  owner uid: 555"));
    }
    @Test public void matchingGlobalSessionRefIsNotAnActiveInput() {
        AudioInputOwnership.Result result = result(FLINGER.replace("session   pid count",
                "session   pid count\n 42 555 1"), POLICY);
        assertEquals(AudioInputOwnership.State.NO_ACTIVE_INPUT, result.state);
        assertFalse(result.selfExemptionAllowed);
    }
    @Test public void inputThreadCannotBeIgnored() {
        unknown(FLINGER.replace("usb_hw", "Record thread 0xdef type 3 (RECORD):\n"
                + "  Client Session Active\n  555 42 yes\nusb_hw"), POLICY);
    }
    @Test public void samePidDifferentSessionCannotBeExempted() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n  555 43 active"));
    }
    @Test public void sameSessionDifferentPidCannotBeExempted() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n  556 42 active"));
    }
    @Test public void evenMatchingPidSessionNeedsRealFormatEvidence() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n  555 42 active"));
    }
    @Test public void policyNonemptyFlingerEmptyDisagrees() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n- Input 7 dump:"));
    }
    @Test public void inputControlByteIsNotWhitespace() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n\0"));
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\n\u000b"));
    }
    @Test public void missingAndDuplicateSectionsReject() {
        unknown(FLINGER, POLICY.replace("Inputs dump:", ""));
        unknown(FLINGER, POLICY.replace("Inputs dump:", "Inputs dump:\nInputs dump:"));
        unknown(FLINGER + FLINGER, POLICY);
    }
    @Test public void reorderedSectionsReject() {
        unknown(FLINGER, POLICY.replace("Inputs dump:\n\nStreams dump:", "Streams dump:\nInputs dump:"));
    }
    @Test public void truncatedTailsRejectEvenWithCompleteFlag() {
        unknown(FLINGER.substring(0, FLINGER.indexOf(" route[9]")), POLICY);
        unknown(FLINGER, POLICY.substring(0, POLICY.indexOf(" 0x00000030")));
        unknown(FLINGER.replace("  0 Effect Chains", ""), POLICY);
    }
    @Test public void unfamiliarThreadNeverLooksIdle() {
        unknown(FLINGER.replace("type 0 (MIXER)", "type 8 (VENDOR_INPUT)"), POLICY);
    }
    @Test public void serviceErrorsOverrideEmptySections() {
        unknown(FLINGER + "Permission Denial: caller\n", POLICY);
        unknown(FLINGER, POLICY + "dump timed out\n");
        unknown(FLINGER.replace("Clients:", "Clients:\n could not lock"), POLICY);
    }
    @Test public void incompleteCommandRejects() {
        assertEquals(AudioInputOwnership.State.UNKNOWN, AudioInputOwnership.evaluate(
                new AudioInputOwnership.Dump(FLINGER, false, 100, 120), dump(POLICY), 555, 42, 130).state);
    }
    @Test public void staleSlowFutureAndReversedTimesReject() {
        long[][] times = {{100, 120, 4101}, {100, 1601, 1602}, {100, 120, 119},
                {120, 100, 130}, {-1, 20, 130}};
        for (long[] t : times) assertEquals(AudioInputOwnership.State.UNKNOWN,
                AudioInputOwnership.evaluate(new AudioInputOwnership.Dump(FLINGER, true, t[0], t[1]),
                        dump(POLICY), 555, 42, t[2]).state);
    }
    @Test public void individuallyFreshButDistantSamplesReject() {
        assertEquals("PAIR_WINDOW_EXCEEDED", AudioInputOwnership.evaluate(dump(FLINGER),
                new AudioInputOwnership.Dump(POLICY, true, 1700, 1720), 555, 42, 1730).reason);
    }
    @Test public void invalidIdentityAndMissingDumpReject() {
        assertEquals(AudioInputOwnership.State.UNKNOWN,
                AudioInputOwnership.evaluate(dump(FLINGER), dump(POLICY), 0, 42, 130).state);
        assertEquals(AudioInputOwnership.State.UNKNOWN,
                AudioInputOwnership.evaluate(dump(FLINGER), dump(POLICY), 555, 0, 130).state);
        assertEquals(AudioInputOwnership.State.UNKNOWN,
                AudioInputOwnership.evaluate(null, dump(POLICY), 555, 42, 130).state);
    }
    @Test public void resourceBoundsRejectWithoutDroppingTail() {
        unknown(repeat('x', AudioInputOwnership.MAX_CHARS + 1), POLICY);
        unknown(FLINGER + repeat('\n', AudioInputOwnership.MAX_LINES), POLICY);
        unknown(FLINGER + repeat('x', AudioInputOwnership.MAX_LINE_CHARS + 1), POLICY);
    }
    private static String repeat(char value, int size) {
        char[] data = new char[size];
        java.util.Arrays.fill(data, value);
        return new String(data);
    }
}
