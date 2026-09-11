package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

/** 独立测试进程，只读系统文件并在唯一临时目录验证创建与回读；不更改现有文件。 */
public final class FileDescriptorLifetimeCheck {
    private static final byte[] PAYLOAD = "D31_FILE_FD_TEST\n".getBytes(StandardCharsets.US_ASCII);
    private static int count() throws Exception {
        String[] names = new File("/proc/self/fd").list();
        if (names == null) throw new Exception("FD_COUNT_UNAVAILABLE");
        return names.length;
    }
    private static void round(File directory, int index) throws Exception {
        FileOperations.Access access = new FileOperations.Access();
        try (InputStream input = access.read(new File("/system/build.prop"))) {
            if (input.read() < 0) throw new Exception("SYSTEM_SOURCE_EMPTY");
        }
        File file = new File(directory, "sample-" + index + ".bin");
        try (FileOutputStream output = access.create(file)) {
            output.write(PAYLOAD); output.flush(); output.getFD().sync();
        }
        byte[] read = new byte[PAYLOAD.length];
        try (InputStream input = access.read(file)) {
            int at = 0;
            while (at < read.length) {
                int n = input.read(read, at, read.length - at);
                if (n <= 0) throw new Exception("ROUND_TRUNCATED");
                at += n;
            }
            if (input.read() != -1 || !Arrays.equals(PAYLOAD, read)) throw new Exception("ROUND_CONTENT_MISMATCH");
        }
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE) || args.length != 1
                || !args[0].matches("[a-f0-9]{32}")) throw new Exception("TARGET_INVALID");
        android.system.Os.umask(0077);
        File directory = new File("/data/local/tmp/d31-fd-check-" + args[0]);
        if (!directory.mkdir()) throw new Exception("NEW_DIRECTORY_REQUIRED");
        round(directory, 0);
        int before = count();
        int[] samples = new int[4];
        for (int i = 1; i <= 64; i++) {
            round(directory, i);
            if (i % 16 == 0) samples[i / 16 - 1] = count();
        }
        int after = count();
        JSONObject result = new JSONObject().put("rounds", 64).put("fd_before", before).put("fd_after", after)
                .put("fd_delta", after - before).put("samples", new org.json.JSONArray(samples))
                .put("content_verified", true).put("existing_files_modified", false)
                .put("test_files_retained", true).put("resource_growth_observed", after > before + 1);
        System.out.println(result);
    }
    private FileDescriptorLifetimeCheck() { }
}
