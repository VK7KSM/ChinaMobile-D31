package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

/** 118真实活动列形状的脱敏摘录；多客户及异常行仍是离线故障注入。 */
public final class AudioInputOwnershipRecordsTest {
    private static final String TRACK = "       yes 555 1 00000010 42 6 00003FC0 1024 16000\n";
    private static final String INPUT = "Input thread 0xdef type 3 (RECORD):\n"
            + "  I/O handle: 18\n  Standby: no\n  Sample rate: 16000 Hz\n  Channel count: 1\n"
            + "  Channel mask: 0x00000010 (front)\n  Format: 0x1 (pcm16)\n"
            + "  Input device: 0x80000004 (BUILTIN_MIC)\n  Audio source: 1 (mic)\n"
            + "  Fast capture thread: no\n  Fast track available: no\n  FastCapture not initialized\n"
            + "  1 Tracks of which 1 are active\n"
            + "    Active Client Fmt Chn mask Session S   Server fCount SRate\n" + TRACK
            + "  0 Effect Chains\n";
    private static final String POLICY_INPUT = "- Input 18 dump:\n ID: 8\n Sampling rate: 16000\n"
            + " Format: 1\n Channels: 00000010\n Devices 80000004\n Ref Count 1\n Open Ref Count 1\n";
    private static String flinger(String input, String refs) {
        return AudioInputOwnershipTest.FLINGER.replace("usb_hw", input + "usb_hw")
                .replace("session   pid count\n", "session   pid count\n" + refs);
    }
    private static String policy(String input) { return AudioInputOwnershipTest.POLICY.replace("Inputs dump:\n", "Inputs dump:\n" + input); }
    private static AudioInputOwnership.Result result(String f, String p) {
        return AudioInputOwnership.evaluate(new AudioInputOwnership.Dump(f, true, 1000, 1030),
                new AudioInputOwnership.Dump(p, true, 1100, 1130), 555, 42, 1140);
    }
    private static AudioInputOwnership.Result normal() { return result(flinger(INPUT, " 42 555 1\n"), policy(POLICY_INPUT)); }
    private static void unknown(String input, String refs, String p) {
        AudioInputOwnership.Result result = result(flinger(input, refs), policy(p));
        assertEquals(result.reason, AudioInputOwnership.State.UNKNOWN, result.state);
        assertEquals(-1, result.activeInputs); assertFalse(result.selfExemptionAllowed);
    }
    @Test public void activeClientAndSessionMatchWithoutExemption() {
        AudioInputOwnership.Result r = normal();
        assertEquals(r.reason, AudioInputOwnership.State.SELF_ONLY, r.state);
        assertEquals(1, r.activeInputs); assertFalse(r.selfExemptionAllowed); assertFalse(r.atomicReservation);
    }
    @Test public void samePidDifferentSessionIsOther() {
        assertEquals(AudioInputOwnership.State.OTHER_ACTIVE,
                result(flinger(INPUT.replace("42 6", "43 6"), "43 555 1\n"), policy(POLICY_INPUT)).state);
    }
    @Test public void sameSessionDifferentPidIsOther() {
        assertEquals(AudioInputOwnership.State.OTHER_ACTIVE,
                result(flinger(INPUT.replace("yes 555", "yes 556"), "42 556 1\n"), policy(POLICY_INPUT)).state);
    }
    @Test public void ownAndOtherActiveRowsNeverBecomeSelfOnly() {
        String two = INPUT.replace("1 Tracks of which 1", "2 Tracks of which 2")
                .replace(TRACK, TRACK + TRACK.replace("yes 555", "yes 556"));
        AudioInputOwnership.Result r = result(flinger(two, "42 555 1\n42 556 1\n"), policy(POLICY_INPUT));
        assertEquals(AudioInputOwnership.State.OTHER_ACTIVE, r.state); assertEquals(2, r.activeInputs);
    }
    @Test public void startupBetweenDumpsIsUnknown() {
        assertEquals("SOURCE_INPUTS_DISAGREE", result(flinger("", ""), policy(POLICY_INPUT)).reason);
        unknown(INPUT, "42 555 1\n", "");
    }
    @Test public void policyIdIsNotIoHandle() {
        unknown(INPUT, "42 555 1\n", POLICY_INPUT.replace("Input 18", "Input 8"));
    }
    @Test public void sessionReferenceAloneNeverProvesActive() {
        assertEquals(AudioInputOwnership.State.NO_ACTIVE_INPUT, result(flinger("", "42 555 1\n"), policy("")).state);
        unknown(INPUT, "", POLICY_INPUT);
        unknown(INPUT, "43 555 1\n", POLICY_INPUT);
    }
    @Test public void missingOwnerColumnIsNotAttributable() {
        AudioInputOwnership.Result r = result(flinger(INPUT.replace("Active Client", "Active UID"), "42 555 1\n"), policy(POLICY_INPUT));
        assertEquals(AudioInputOwnership.State.UNKNOWN, r.state); assertTrue(r.reason.contains("NOT_ATTRIBUTABLE"));
    }
    @Test public void inactiveAndUnknownRowsCannotBecomeIdle() {
        unknown(INPUT.replace("yes 555", "no 555"), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT.replace("1 are active", "0 are active"), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT.replace(TRACK, ""), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT.replace(TRACK, TRACK + TRACK), "42 555 1\n", POLICY_INPUT);
    }
    @Test public void malformedPidAndPolicyCountsReject() {
        unknown(INPUT.replace("yes 555", "yes 2147483648"), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT, "42 555 1\n", POLICY_INPUT.replace("Ref Count 1", "Ref Count 0"));
        unknown(INPUT, "42 555 1\n", POLICY_INPUT + POLICY_INPUT);
    }
    @Test public void changedRoutesAndTableConfigurationReject() {
        unknown(INPUT.replace("1 (mic)", "7 (voice_communication)"), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT.replace("00003FC0 1024 16000", "00003FC0 1024 8000"), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT, "42 555 1\n", POLICY_INPUT.replace("Sampling rate: 16000", "Sampling rate: 8000"));
    }
    @Test public void duplicateThreadExtraSectionAndControlCharactersReject() {
        unknown(INPUT + INPUT, "42 555 1\n", POLICY_INPUT);
        unknown(INPUT.replace("  I/O handle:", "Vendor input: active\n  I/O handle:"), "42 555 1\n", POLICY_INPUT);
        unknown(INPUT.replace("yes 555", "yes \u000b555"), "42 555 1\n", POLICY_INPUT);
    }
}
