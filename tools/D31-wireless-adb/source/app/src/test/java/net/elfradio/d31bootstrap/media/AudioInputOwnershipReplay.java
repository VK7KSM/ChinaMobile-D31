package net.elfradio.d31bootstrap.media;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

/** 宿主离线回放；仅输出脱敏解析结论，逻辑时间不冒充原件采集时效。 */
public final class AudioInputOwnershipReplay {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 8) throw new IllegalArgumentException("需要两份路径；活动回放另传PID、session及两源起止");
        int pid = args.length == 8 ? Integer.parseInt(args[2]) : 1;
        int session = args.length == 8 ? Integer.parseInt(args[3]) : 1;
        long fb = args.length == 8 ? Long.parseLong(args[4]) : 0;
        long fe = args.length == 8 ? Long.parseLong(args[5]) : 0;
        long pb = args.length == 8 ? Long.parseLong(args[6]) : 0;
        long pe = args.length == 8 ? Long.parseLong(args[7]) : 0;
        AudioInputOwnership.Result result = AudioInputOwnership.evaluate(
                new AudioInputOwnership.Dump(read(args[0]), true, fb, fe),
                new AudioInputOwnership.Dump(read(args[1]), true, pb, pe), pid, session, Math.max(fe, pe));
        System.out.println("offlineReplay=true liveFreshnessVerified=false identityVerified=false state="
                + result.state + " reason=" + result.reason + " selfExemptionAllowed="
                + result.selfExemptionAllowed + " activeInputs=" + result.activeInputs + " scope=" + result.scope);
        if (result.state == AudioInputOwnership.State.UNKNOWN
                || (args.length == 2 && result.state != AudioInputOwnership.State.NO_ACTIVE_INPUT)) System.exit(2);
    }

    private static String read(String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] block = new byte[4096];
            int size;
            while ((size = input.read(block)) != -1) {
                if (out.size() + size > AudioInputOwnership.MAX_CHARS) throw new IOException("原件超限");
                out.write(block, 0, size);
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }
}
