package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 复用118活动列的脱敏样本，验证时间、身份及失败后的不可复活合同。 */
public final class AudioCaptureLifecycleTest {
    static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String BOOT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final AudioCaptureLifecycle.Identity ID = identity(HASH, BOOT, 10001, 555, 42);
    static final String INPUT = "Input thread 0xdef type 3 (RECORD):\n"
            + "  I/O handle: 18\n  Standby: no\n  Sample rate: 16000 Hz\n  Channel count: 1\n"
            + "  Channel mask: 0x00000010 (front)\n  Format: 0x1 (pcm16)\n"
            + "  Input device: 0x80000004 (BUILTIN_MIC)\n  Audio source: 1 (mic)\n"
            + "  Fast capture thread: no\n  Fast track available: no\n  FastCapture not initialized\n"
            + "  1 Tracks of which 1 are active\n"
            + "    Active Client Fmt Chn mask Session S   Server fCount SRate\n"
            + "       yes 555 1 00000010 42 6 00003FC0 1024 16000\n  0 Effect Chains\n";
    static final String F = AudioInputOwnershipTest.FLINGER.replace("usb_hw", INPUT + "usb_hw")
            .replace("session   pid count\n", "session   pid count\n 42 555 1\n");
    static final String P = AudioInputOwnershipTest.POLICY.replace("Inputs dump:\n", "Inputs dump:\n"
            + "- Input 18 dump:\n ID: 8\n Sampling rate: 16000\n Format: 1\n Channels: 00000010\n"
            + " Devices 80000004\n Ref Count 1\n Open Ref Count 1\n");
    static final class Time implements AudioCaptureLifecycle.Clock, MediaCapture.Clock {
        volatile long now = 1000;
        public long elapsed() { return now; }
        public long wall() { return 100000; }
    }
    static AudioCaptureLifecycle.Identity identity(String hash, String boot, int uid, int pid, int session) {
        return new AudioCaptureLifecycle.Identity(AudioCaptureLifecycle.PACKAGE, hash, boot, uid, pid, session);
    }
    static AudioCaptureLifecycle.Evidence evidence(String f, String p, long from) {
        return evidence(ID, "request-A", "session-1", f, p, from, AudioCaptureLifecycle.External.IDLE);
    }
    static AudioCaptureLifecycle.Evidence evidence(AudioCaptureLifecycle.Identity id, String request, String session,
            String f, String p, long from, AudioCaptureLifecycle.External external) {
        return new AudioCaptureLifecycle.Evidence(id, request, session,
                new AudioInputOwnership.Dump(f, true, from, from + 10),
                new AudioInputOwnership.Dump(p, true, from + 20, from + 30), external, from, from + 5);
    }
    static AudioCaptureLifecycle.Evidence idle() {
        return evidence(AudioInputOwnershipTest.FLINGER, AudioInputOwnershipTest.POLICY, 900);
    }
    static AudioCaptureLifecycle core(Time time) {
        return new AudioCaptureLifecycle(ID, "request-A", "session-1", time);
    }
    static AudioCaptureLifecycle verifying(Time time) throws Exception {
        AudioCaptureLifecycle core = core(time); core.begin(idle()); core.started(); return core;
    }
    static AudioCaptureLifecycle active(Time time) throws Exception {
        AudioCaptureLifecycle core = verifying(time); time.now = 1140;
        assertTrue(core.observe(evidence(F, P, 1100)).continuationEligible); return core;
    }
    static AudioCaptureLifecycle.Release released(Time time) {
        return new AudioCaptureLifecycle.Release(ID, true, true, true, 0, 1, time.now);
    }
    private static void denied(AudioCaptureLifecycle core, AudioCaptureLifecycle.Evidence evidence) {
        assertFalse(core.observe(evidence).continuationEligible);
        assertTrue(core.snapshot().stopRequired);
        assertFalse(core.observe(evidence(F, P, 1100)).continuationEligible);
    }
    @Test public void idleStartBindsOnlyPostStartOwnInputThenRealRelease() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = verifying(time);
        assertEquals(AudioCaptureLifecycle.Phase.VERIFYING, core.snapshot().phase);
        assertFalse(core.snapshot().continuationEligible); time.now = 1140;
        assertTrue(core.observe(evidence(F, P, 1100)).continuationEligible);
        assertTrue(core.snapshot().ioBound); assertFalse(core.snapshot().managedMedia);
        assertFalse(core.snapshot().atomicReservation); core.requestStop();
        assertTrue(core.confirmRelease(released(time)));
        assertEquals(AudioCaptureLifecycle.Phase.CLOSED, core.snapshot().phase);
    }
    @Test public void existingOwnInputCannotBeTakenOver() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = core(time);
        try { core.begin(evidence(F, P, 900)); fail(); } catch (IOException expected) { }
        assertEquals("INPUT_PRESENT_BEFORE_START", core.snapshot().reason);
    }
    @Test public void idlePreflightMayPrecedeRecordConstructionButNotFreshnessLimit() throws Exception {
        Time time = new Time(); time.now = 4800;
        core(time).begin(idle()); time.now = 4901;
        try { core(time).begin(idle()); fail(); } catch (IOException expected) { }
    }
    @Test public void noOwnershipProofTimesOut() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = verifying(time); time.now = 2501;
        assertTrue(core.snapshot().stopRequired);
    }
    @Test public void lateNativeStartCannotRevive() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = core(time); core.begin(idle());
        time.now = 2501; core.started(); assertTrue(core.snapshot().stopRequired);
        denied(core, evidence(F, P, 2400));
    }
    @Test public void goodEvidenceWhileStartingNeitherActivatesNorBindsIo() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = core(time); core.begin(idle());
        time.now = 1140;
        for (AudioCaptureLifecycle.Evidence e : new AudioCaptureLifecycle.Evidence[] {idle(), evidence(F, P, 1100)}) {
            AudioCaptureLifecycle.Snapshot s = core.observe(e);
            assertEquals(AudioCaptureLifecycle.Phase.STARTING, s.phase);
            assertFalse(s.continuationEligible); assertFalse(s.ioBound);
        }
        core.started(); assertEquals(AudioCaptureLifecycle.Phase.VERIFYING, core.snapshot().phase);
        core.requestStop(); assertTrue(core.confirmRelease(released(time)));
    }
    @Test public void oldRequestCannotCancelAndCurrentCancellationIsIrreversible() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = active(time);
        assertFalse(core.cancelRequest("request-old")); assertTrue(core.snapshot().continuationEligible);
        assertTrue(core.cancelRequest("request-A")); core.started(); denied(core, evidence(F, P, 1100));
    }
    @Test public void externalBusyAndUnknownAlwaysRevoke() throws Exception {
        for (AudioCaptureLifecycle.External external : new AudioCaptureLifecycle.External[] {
                AudioCaptureLifecycle.External.BUSY, AudioCaptureLifecycle.External.UNKNOWN, null}) {
            Time time = new Time(); AudioCaptureLifecycle core = active(time);
            denied(core, evidence(ID, "request-A", "session-1", F, P, 1100, external));
        }
    }
    @Test public void crossSourceDisagreementAndMissingEvidenceRevoke() throws Exception {
        Time time = new Time(); denied(active(time), evidence(F, AudioInputOwnershipTest.POLICY, 1100));
        time = new Time(); denied(active(time), null);
    }
    @Test public void otherPidOrSessionAlwaysRevoke() throws Exception {
        for (String f : new String[] { F.replace("555", "556"), F.replace("42", "43") }) {
            Time time = new Time(); AudioCaptureLifecycle core = active(time); denied(core, evidence(f, P, 1100));
            assertEquals("OTHER_ACTIVE", core.snapshot().reason);
        }
    }
    @Test public void fixedAppBootUidPidAndSessionCannotChange() throws Exception {
        AudioCaptureLifecycle.Identity[] ids = {
            identity(HASH.replace('a', 'b'), BOOT, 10001, 555, 42),
            identity(HASH, BOOT.replace('a', 'b'), 10001, 555, 42),
            identity(HASH, BOOT, 10002, 555, 42), identity(HASH, BOOT, 10001, 556, 42),
            identity(HASH, BOOT, 10001, 555, 43)
        };
        for (AudioCaptureLifecycle.Identity id : ids) {
            Time time = new Time(); denied(active(time), evidence(id, "request-A", "session-1", F, P, 1100, AudioCaptureLifecycle.External.IDLE));
        }
    }
    @Test public void differentPackageRejectedAtConstruction() {
        try { new AudioCaptureLifecycle.Identity("another.app", HASH, BOOT, 10001, 555, 42); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void requestAndMediaSessionCannotChange() throws Exception {
        Time time = new Time(); denied(active(time), evidence(ID, "request-B", "session-1", F, P, 1100, AudioCaptureLifecycle.External.IDLE));
        time = new Time(); denied(active(time), evidence(ID, "request-A", "session-2", F, P, 1100, AudioCaptureLifecycle.External.IDLE));
    }
    @Test public void matchingNewIoOnBothSourcesCannotRebind() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = active(time);
        denied(core, evidence(F.replace("handle: 18", "handle: 19"), P.replace("Input 18", "Input 19"), 1100));
        assertEquals("INPUT_HANDLE_CHANGED", core.snapshot().reason);
    }
    @Test public void evidenceFromBeforeStartCannotProveOwnInput() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = verifying(time); time.now = 1140;
        denied(core, evidence(F, P, 999)); assertEquals("PRE_START_INPUT_EVIDENCE", core.snapshot().reason);
    }
    @Test public void duplicateEvidenceNeverRenewsItsAge() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = active(time);
        time.now = 5000; assertTrue(core.observe(evidence(F, P, 1100)).continuationEligible);
        time.now = 5101; assertTrue(core.snapshot().stopRequired);
    }
    @Test public void reorderedSourcesRevokeEvenIfStillFresh() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = active(time); denied(core, evidence(F, P, 1099));
        assertEquals("EVIDENCE_REORDERED", core.snapshot().reason);
    }
    @Test public void futureEvidenceAndExcessiveCombinedWindowRevoke() throws Exception {
        Time time = new Time(); denied(active(time), evidence(F, P, 1140));
        time = new Time(); AudioCaptureLifecycle core = active(time); time.now = 2700;
        AudioCaptureLifecycle.Evidence fresh = evidence(F, P, 2600);
        denied(core, new AudioCaptureLifecycle.Evidence(ID, "request-A", "session-1", fresh.flinger, fresh.policy,
                AudioCaptureLifecycle.External.IDLE, 1100, 1105));
        assertEquals("COMBINED_EVIDENCE_WINDOW_INVALID", core.snapshot().reason);
    }
    @Test public void clockRegressionIsStickyAndCannotConfirmRelease() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = active(time); time.now = 1139;
        assertTrue(core.snapshot().stopRequired); time.now = 1200;
        assertFalse(core.confirmRelease(released(time)));
    }
    @Test public void releaseBeforeStartReturnsCannotClose() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = core(time); core.begin(idle()); core.requestStop();
        assertFalse(core.confirmRelease(released(time))); core.started();
        assertTrue(core.confirmRelease(released(time)));
    }
    @Test public void everyReleaseGateMustBeProved() throws Exception {
        for (int bad = 0; bad < 8; bad++) {
            Time time = new Time(); AudioCaptureLifecycle core = active(time); core.requestStop();
            AudioCaptureLifecycle.Release release = new AudioCaptureLifecycle.Release(bad == 0 ? null : ID,
                    bad != 1, bad != 2, bad != 3, bad == 4 ? 1 : 0, bad == 5 ? 3 : 1,
                    time.now + (bad == 6 ? 1 : bad == 7 ? -1 : 0));
            assertFalse(core.confirmRelease(release)); assertFalse(core.snapshot().releaseConfirmed);
            assertTrue(core.snapshot().stopRequired);
        }
    }
    @Test public void timeoutAndExceptionDoNotMeanReleasedButLateProofCanClose() throws Exception {
        Time time = new Time(); AudioCaptureLifecycle core = active(time); core.requestStop(); time.now += 1501;
        assertEquals(AudioCaptureLifecycle.Phase.RELEASE_UNCONFIRMED, core.snapshot().phase);
        core.releaseFailed(); assertFalse(core.snapshot().releaseConfirmed);
        assertTrue(core.confirmRelease(released(time)));
        try { core.begin(idle()); fail(); } catch (IOException expected) { }
    }
    @Test public void activeOldAdbCrCrLfHasSameMeaningAndDoesNotGrantGuardExemption() throws Exception {
        for (String newline : new String[] {"\n", "\r\n", "\r\r\n"}) {
            String f = F.trim().replace("\n", newline), p = P.trim().replace("\n", newline);
            AudioCaptureLifecycle.Evidence e = evidence(f, p, 1100);
            AudioInputOwnership.Result r = AudioInputOwnership.evaluate(e.flinger, e.policy, 555, 42, 1140);
            assertEquals(AudioInputOwnership.State.SELF_ONLY, r.state); assertFalse(r.selfExemptionAllowed);
            Time time = new Time(); AudioCaptureLifecycle core = verifying(time); time.now = 1140;
            assertTrue(core.observe(e).continuationEligible); assertTrue(core.snapshot().ioBound);
            core.requestStop(); assertTrue(core.confirmRelease(released(time)));
        }
    }
}
