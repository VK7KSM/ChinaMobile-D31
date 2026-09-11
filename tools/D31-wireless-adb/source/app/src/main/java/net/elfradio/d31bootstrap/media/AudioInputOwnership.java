package net.elfradio.d31bootstrap.media;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** 仅解析已取得的完整转储；没有设备访问、后台刷新或自身录音豁免。 */
public final class AudioInputOwnership {
    public static final int MAX_CHARS = 262144;
    public static final int MAX_LINES = 8192;
    public static final int MAX_LINE_CHARS = 4096;
    public static final long MAX_SAMPLE_MS = 1500;
    public static final long MAX_AGE_MS = 4000;
    public static final String SCOPE = "D31_114_118_DUMP_INPUTS_ONLY_NOT_ATOMIC";

    public enum State { UNKNOWN, NO_ACTIVE_INPUT, SELF_ONLY, OTHER_ACTIVE }

    /** complete只能由采集方在真实退出成功、无超时、无截断时提供。 */
    public static final class Dump {
        public final String text;
        public final boolean complete;
        public final long startedElapsedMs;
        public final long finishedElapsedMs;

        public Dump(String text, boolean complete, long startedElapsedMs, long finishedElapsedMs) {
            this.text = text;
            this.complete = complete;
            this.startedElapsedMs = startedElapsedMs;
            this.finishedElapsedMs = finishedElapsedMs;
        }
    }

    /** 脱敏结果不含原始行、客户PID、session、指针或设备身份。 */
    public static final class Result {
        public final State state;
        public final String reason;
        public final int activeInputs;
        public final boolean selfExemptionAllowed = false;
        public final boolean atomicReservation = false;
        public final String scope = SCOPE;

        private Result(State state, String reason) {
            this(state, reason, state == State.NO_ACTIVE_INPUT ? 0 : -1);
        }

        private Result(State state, String reason, int activeInputs) {
            this.state = state;
            this.reason = reason;
            this.activeInputs = activeInputs;
        }
    }

    private static final Pattern OUTPUT = Pattern.compile("Output thread 0x[0-9a-fA-F]+ type 0 \\(MIXER\\):");
    private static final Pattern ROUTE = Pattern.compile("route\\[([0-9])\\] rate in=[0-9]+ out=[0-9]+, addr=\\[.*\\]");
    private static final Pattern FAILURE = Pattern.compile(
            "(?i).*(permission denial|permission denied|timed out|timeout|dead object|deadobject|"
            + "failed to dump|error dumping|can't find service|cannot find service|truncated|"
            + "could not lock|unable to lock|lock is taken|dump skipped).*" );

    private AudioInputOwnership() { }

    /** PID/session必须来自本次实际AudioRecord；SELF_ONLY仅描述观察，不授予豁免。 */
    public static Result evaluate(Dump flinger, Dump policy, int ownPid, int ownSession,
            long nowElapsedMs) {
        if (ownPid <= 0 || ownSession <= 0) return unknown("CLIENT_IDENTITY_INVALID");
        String failure = validateTime(flinger, nowElapsedMs);
        if (failure != null) return unknown("FLINGER_" + failure);
        failure = validateTime(policy, nowElapsedMs);
        if (failure != null) return unknown("POLICY_" + failure);
        long first = Math.min(flinger.startedElapsedMs, policy.startedElapsedMs);
        long last = Math.max(flinger.finishedElapsedMs, policy.finishedElapsedMs);
        if (last - first > MAX_SAMPLE_MS) return unknown("PAIR_WINDOW_EXCEEDED");
        try {
            String[] f = lines(flinger.text);
            String[] p = lines(policy.text);
            Map<Integer, List<AudioInputOwnershipRecords.Client>> inputs = validateFlinger(f);
            Set<Integer> policyInputs = validatePolicy(p);
            if (!inputs.keySet().equals(policyInputs)) throw new Invalid("SOURCE_INPUTS_DISAGREE");
            Set<String> references = AudioInputOwnershipRecords.references(f,
                    unique(f, "session   pid count") + 1, unique(f, "Hardware status: 0"));
            int count = 0;
            boolean other = false;
            for (List<AudioInputOwnershipRecords.Client> clients : inputs.values()) {
                for (AudioInputOwnershipRecords.Client client : clients) {
                    if (!references.contains(client.key())) throw new Invalid("ACTIVE_CLIENT_REFERENCE_MISSING");
                    count++;
                    if (client.pid != ownPid || client.session != ownSession) other = true;
                }
            }
            if (count == 0) return new Result(State.NO_ACTIVE_INPUT, "EMPTY_INPUT_SECTIONS_OBSERVED");
            return new Result(other ? State.OTHER_ACTIVE : State.SELF_ONLY,
                    other ? "OTHER_ACTIVE_CLIENT_OBSERVED" : "MATCHED_ACTIVE_CLIENT_OBSERVED", count);
        } catch (Invalid invalid) {
            return unknown(invalid.getMessage());
        }
    }

    private static String validateTime(Dump dump, long now) {
        if (dump == null || !dump.complete) return "INCOMPLETE";
        if (now < 0 || dump.startedElapsedMs < 0 || dump.finishedElapsedMs < dump.startedElapsedMs
                || dump.finishedElapsedMs > now) return "TIME_INVALID";
        if (dump.finishedElapsedMs - dump.startedElapsedMs > MAX_SAMPLE_MS) return "SAMPLE_TOO_SLOW";
        if (now - dump.startedElapsedMs > MAX_AGE_MS) return "STALE";
        return null;
    }

    private static String[] lines(String text) throws Invalid {
        if (text == null || text.length() == 0) throw new Invalid("EMPTY_DUMP");
        if (text.length() > MAX_CHARS) throw new Invalid("DUMP_TOO_LARGE");
        // 旧ADB可能产生CRCRLF；只在派生文本规范化，不改原始转储。
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int count = 1;
        int width = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                if (++count > MAX_LINES) throw new Invalid("TOO_MANY_LINES");
                width = 0;
            } else if (++width > MAX_LINE_CHARS) throw new Invalid("LINE_TOO_LONG");
        }
        String[] result = normalized.split("\n", -1);
        for (String line : result) {
            if (FAILURE.matcher(line).matches()) throw new Invalid("DUMP_REPORTED_FAILURE");
        }
        return result;
    }

    private static Map<Integer, List<AudioInputOwnershipRecords.Client>> validateFlinger(String[] lines) throws Invalid {
        int suspend = unique(lines, "mAFSuspend: 0");
        int mute = unique(lines, "mMicMute: 0");
        int clients = unique(lines, "Clients:");
        int notifications = unique(lines, "Notification Clients:");
        int refs = unique(lines, "Global session refs:");
        int refHeader = unique(lines, "session   pid count");
        int hardware = unique(lines, "Hardware status: 0");
        int standby = unique(lines, "Standby Time mSec: 3000");
        int usb = unique(lines, "usb_hw version 2.2.0");
        int tail = unique(lines, "Reroute submix audio module:");
        if (!(suspend < mute && mute < clients && clients < notifications && notifications < refs
                && refs < refHeader && refHeader < hardware && hardware < standby
                && standby < usb && usb < tail)) throw new Invalid("FLINGER_SECTION_ORDER");
        int outputs = 0;
        Map<Integer, List<AudioInputOwnershipRecords.Client>> inputs = new HashMap<Integer, List<AudioInputOwnershipRecords.Client>>();
        boolean closed = true;
        for (String raw : lines) if (raw.indexOf('\0') >= 0) throw new Invalid("FLINGER_CONTROL_CHARACTER");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (raw.indexOf('\0') >= 0) throw new Invalid("FLINGER_CONTROL_CHARACTER");
            if (line.startsWith("Record thread") || line.startsWith("Input thread")) {
                if (!closed || i <= standby || i >= usb) throw new Invalid("INPUT_THREAD_POSITION_INVALID");
                List<String> block = new ArrayList<String>();
                block.add(raw);
                int end = i + 1;
                while (end < usb && !lines[end].trim().equals("0 Effect Chains")) block.add(lines[end++]);
                if (end >= usb) throw new Invalid("INPUT_THREAD_INCOMPLETE");
                AudioInputOwnershipRecords.Input input = AudioInputOwnershipRecords.input(block);
                if (inputs.size() >= 16 || inputs.put(input.handle, input.clients) != null) {
                    throw new Invalid("INPUT_THREAD_DUPLICATE_OR_LIMIT");
                }
                i = end;
                continue;
            }
            if (i <= standby || i >= usb || line.length() == 0) continue;
            if (OUTPUT.matcher(line).matches()) {
                if (!closed) throw new Invalid("FLINGER_THREAD_INCOMPLETE");
                outputs++;
                closed = false;
            } else if (!Character.isWhitespace(raw.charAt(0))) {
                throw new Invalid("FLINGER_THREAD_FORMAT_UNVERIFIED");
            } else if (closed) {
                throw new Invalid("FLINGER_UNEXPECTED_THREAD_DATA");
            } else if (line.equals("0 Effect Chains")) {
                closed = true;
            }
        }
        if (outputs == 0 || !closed) throw new Invalid("FLINGER_THREAD_INCOMPLETE");
        int route = 0;
        for (int i = tail + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            java.util.regex.Matcher match = ROUTE.matcher(line);
            if (!match.matches() || Integer.parseInt(match.group(1)) != route++) {
                throw new Invalid("FLINGER_TAIL_UNVERIFIED");
            }
        }
        if (route != 10) throw new Invalid("FLINGER_TAIL_INCOMPLETE");
        return inputs;
    }

    private static Set<Integer> validatePolicy(String[] lines) throws Invalid {
        int manager = uniquePrefix(lines, "AudioPolicyManager Dump:");
        int inputs = unique(lines, "Inputs dump:");
        int streams = unique(lines, "Streams dump:");
        int effects = unique(lines, "Registered effects:");
        int patches = unique(lines, "Audio Patches:");
        int voe = unique(lines, "Voe volume dump:");
        if (!(manager < inputs && inputs < streams && streams < effects && effects < patches
                && patches < voe)) throw new Invalid("POLICY_SECTION_ORDER");
        Set<Integer> handles = AudioInputOwnershipRecords.policy(lines, inputs + 1, streams);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].trim().length() == 0) last--;
        if (last <= voe || !lines[last].trim().matches(
                "0x00000030\\s+[0-9]+\\s+[0-9]+\\s+[0-9]+\\s+[0-9]+\\s+bluetooth-sco")) {
            throw new Invalid("POLICY_TAIL_INCOMPLETE");
        }
        return handles;
    }

    private static int unique(String[] lines, String expected) throws Invalid {
        return unique(lines, expected, false);
    }

    private static int uniquePrefix(String[] lines, String expected) throws Invalid {
        return unique(lines, expected, true);
    }

    private static int unique(String[] lines, String expected, boolean prefix) throws Invalid {
        int found = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (prefix ? line.startsWith(expected) : line.equals(expected)) {
                if (found >= 0) throw new Invalid("DUPLICATE_SECTION");
                found = i;
            }
        }
        if (found < 0) throw new Invalid("MISSING_SECTION");
        return found;
    }

    private static Result unknown(String reason) { return new Result(State.UNKNOWN, reason); }

    static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        Invalid(String reason) { super(reason); }
    }
}
