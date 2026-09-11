package net.elfradio.d31bootstrap.media;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.elfradio.d31bootstrap.media.AudioInputOwnership.Invalid;

/** 118实证的普通MIC/16k/mono输入表；其它路由、非活动格式及未知列严格拒绝。 */
final class AudioInputOwnershipRecords {
    static final class Client {
        final int pid, session;
        Client(int pid, int session) { this.pid = pid; this.session = session; }
        String key() { return pid + ":" + session; }
    }
    static final class Input {
        final int handle;
        final List<Client> clients;
        Input(int handle, List<Client> clients) { this.handle = handle; this.clients = clients; }
    }

    static Input input(List<String> source) throws Invalid {
        List<String> lines = compact(source);
        if (lines.isEmpty() || !lines.get(0).matches("Input thread 0x[0-9a-fA-F]+ type 3 \\(RECORD\\):")) {
            throw new Invalid("INPUT_THREAD_FORMAT_UNVERIFIED");
        }
        int handle = number(value(lines, "I/O handle:"));
        // 独立核对线程配置和轨道列；不从S内部状态值或session引用推断active。
        requireValue(lines, "Standby:", "no");
        requireValue(lines, "Sample rate:", "16000 Hz");
        requireValue(lines, "Channel count:", "1");
        requireValue(lines, "Channel mask:", "0x00000010 (front)");
        requireValue(lines, "Format:", "0x1 (pcm16)");
        requireValue(lines, "Input device:", "0x80000004 (BUILTIN_MIC)");
        requireValue(lines, "Audio source:", "1 (mic)");
        requireValue(lines, "Fast capture thread:", "no");
        requireValue(lines, "Fast track available:", "no");
        int table = -1, total = -1, active = -1;
        Pattern counts = Pattern.compile("([0-9]+) Tracks of which ([0-9]+) are active");
        for (int i = 1; i < lines.size(); i++) {
            Matcher match = counts.matcher(lines.get(i));
            if (match.matches()) {
                if (table != -1) throw new Invalid("TRACK_TABLE_DUPLICATE");
                table = i; total = number(match.group(1)); active = number(match.group(2));
            }
        }
        if (table < 0 || total > 64 || active != total || table + total + 2 != lines.size()) {
            throw new Invalid("TRACK_COUNT_OR_FORMAT_UNVERIFIED");
        }
        String[] fields = {"mInput name:", "Thread name:", "I/O handle:", "TID:", "Standby:",
                "Sample rate:", "HAL frame count:", "HAL format:", "HAL buffer size:", "Channel count:",
                "Channel mask:", "Format:", "Frame size:", "Pending config events:", "Output device:",
                "Input device:", "Audio source:", "Fast capture thread:", "Fast track available:"};
        Set<String> fieldNames = new HashSet<String>();
        for (int i = 1; i < table; i++) {
            String line = lines.get(i), key = null;
            for (String field : fields) if (line.startsWith(field)) key = field;
            if (line.equals("FastCapture not initialized")) key = line;
            if (key == null || !fieldNames.add(key)) throw new Invalid("INPUT_PREAMBLE_UNVERIFIED");
        }
        if (!lines.get(table + 1).replaceAll("[ \\t]+", " ").equals(
                "Active Client Fmt Chn mask Session S Server fCount SRate")) {
            throw new Invalid("TRACK_COLUMNS_UNVERIFIED_NOT_ATTRIBUTABLE");
        }
        List<Client> clients = new ArrayList<Client>();
        Set<String> seen = new HashSet<String>();
        for (int i = table + 2; i < lines.size(); i++) {
            String[] row = lines.get(i).split("[ \\t]+");
            if (row.length != 9 || !row[0].equals("yes")) throw new Invalid("TRACK_ACTIVE_FORMAT_UNVERIFIED");
            if (!row[2].equals("1") || !row[3].equals("00000010") || !row[8].equals("16000")
                    || !row[5].matches("[0-9]{1,2}") || !row[6].matches("[0-9a-fA-F]{8}")) {
                throw new Invalid("TRACK_CONFIGURATION_UNVERIFIED");
            }
            number(row[7]);
            Client client = new Client(number(row[1]), number(row[4]));
            if (!seen.add(client.key())) throw new Invalid("TRACK_CLIENT_DUPLICATE");
            clients.add(client);
        }
        return new Input(handle, clients);
    }

    static Set<Integer> policy(String[] source, int from, int to) throws Invalid {
        List<String> raw = new ArrayList<String>();
        for (int i = from; i < to; i++) raw.add(source[i]);
        List<String> lines = compact(raw);
        Set<Integer> handles = new HashSet<Integer>();
        Set<Integer> ids = new HashSet<Integer>();
        if (lines.size() % 8 != 0 || lines.size() > 16 * 8) throw new Invalid("POLICY_INPUT_FORMAT_UNVERIFIED");
        for (int i = 0; i < lines.size(); i += 8) {
            Matcher header = Pattern.compile("- Input ([0-9]+) dump:").matcher(lines.get(i));
            if (!header.matches() || !handles.add(number(header.group(1)))) throw new Invalid("POLICY_INPUT_HEADER_INVALID");
            List<String> block = lines.subList(i + 1, i + 8);
            if (!ids.add(number(value(block, "ID:")))) throw new Invalid("POLICY_INPUT_ID_DUPLICATE");
            requireValue(block, "Sampling rate:", "16000");
            requireValue(block, "Format:", "1");
            requireValue(block, "Channels:", "00000010");
            requireValue(block, "Devices ", "80000004");
            number(value(block, "Ref Count "));
            number(value(block, "Open Ref Count "));
        }
        return handles;
    }

    static Set<String> references(String[] lines, int from, int to) throws Invalid {
        Set<String> refs = new HashSet<String>();
        for (int i = from; i < to; i++) {
            String line = clean(lines[i]);
            if (line.isEmpty()) continue;
            String[] row = line.split("[ \\t]+");
            if (row.length != 3 || refs.size() >= 128) throw new Invalid("SESSION_REFS_INVALID");
            int session = number(row[0]), pid = number(row[1]); number(row[2]);
            if (!refs.add(pid + ":" + session)) throw new Invalid("SESSION_REFS_DUPLICATE");
        }
        return refs;
    }

    private static List<String> compact(List<String> source) throws Invalid {
        List<String> result = new ArrayList<String>();
        for (String raw : source) { String line = clean(raw); if (!line.isEmpty()) result.add(line); }
        return result;
    }
    private static String clean(String raw) throws Invalid {
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if ((value < 32 && value != '\t') || value == 127) throw new Invalid("INPUT_CONTROL_CHARACTER");
        }
        return raw.trim();
    }
    private static String value(List<String> lines, String prefix) throws Invalid {
        String found = null;
        for (String line : lines) if (line.startsWith(prefix)) {
            if (found != null) throw new Invalid("INPUT_FIELD_DUPLICATE");
            found = line.substring(prefix.length()).trim();
        }
        if (found == null) throw new Invalid("INPUT_FIELD_MISSING");
        return found;
    }
    private static void requireValue(List<String> lines, String prefix, String expected) throws Invalid {
        if (!value(lines, prefix).equals(expected)) throw new Invalid("INPUT_CONFIGURATION_UNVERIFIED");
    }
    private static int number(String raw) throws Invalid {
        if (!raw.matches("[0-9]{1,10}")) throw new Invalid("INPUT_NUMBER_INVALID");
        try { int value = Integer.parseInt(raw); if (value > 0) return value; }
        catch (NumberFormatException ignored) { }
        throw new Invalid("INPUT_NUMBER_INVALID");
    }
    private AudioInputOwnershipRecords() { }
}
