package net.elfradio.d31bootstrap.diagnostics.collection;

import android.os.Build;
import android.os.Process;
import android.system.Os;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 独立API23同进程FD验收；只读固定boot_id，不输出其内容或摘要，不写设备文件。 */
public final class DescriptorOwnershipCheck {
    private static final String SOURCE = "/proc/sys/kernel/random/boot_id";
    public static void main(String[] args) {
        try {
            if (Build.VERSION.SDK_INT != 23 || Process.myUid() != 0
                    || !"hct6735_66_m0".equals(Build.DEVICE) || !"hct6737t_66_m0".equals(Build.MODEL))
                throw new IOException("D31_API23_ROOT_REQUIRED");
            int iterations = iterations(args);
            AndroidCollectionAccess access = new AndroidCollectionAccess("/system/bin/busybox", AndroidCollectionAccess.systemClock());
            read(access);
            int before = fdCount(), sourceBefore = sourceCount(), peak = before, sourcePeak = sourceBefore;
            boolean stable = true;
            for (int i = 0; i < iterations; i++) {
                read(access);
                int current = fdCount(), references = sourceCount();
                peak = Math.max(peak, current); sourcePeak = Math.max(sourcePeak, references);
                if (current != before || references != sourceBefore) stable = false;
            }
            int after = fdCount(), sourceAfter = sourceCount();
            boolean ok = stable && after == before && sourceBefore == 0 && sourceAfter == 0;
            System.out.println(new JSONObject().put("schemaVersion", 1).put("kind", "COLLECTION_DESCRIPTOR_OWNERSHIP")
                    .put("readOnly", true).put("sourceValuesEmitted", false).put("iterations", iterations)
                    .put("warmupReads", 1).put("fdBefore", before).put("fdAfter", after).put("fdPeakAfterClose", peak)
                    .put("sourceFdBefore", sourceBefore).put("sourceFdAfter", sourceAfter).put("sourceFdPeakAfterClose", sourcePeak)
                    .put("sameProcess", true).put("gcRequested", false).put("ok", ok).toString());
            System.exit(ok ? 0 : 1);
        } catch (Exception failure) {
            System.err.println("COLLECTION_DESCRIPTOR_CHECK_FAILED:" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    static int iterations(String[] args) throws IOException {
        if (args == null || args.length != 1 || !("64".equals(args[0]) || "128".equals(args[0])))
            throw new IOException("ITERATIONS_64_OR_128_REQUIRED");
        return Integer.parseInt(args[0]);
    }
    private static void read(CollectionAccess access) throws IOException {
        CollectionAccess.Stat before = access.lstat(SOURCE);
        try (CollectionAccess.Handle handle = access.openRegular(SOURCE, before)) {
            byte[] bytes = new byte[128]; int used = 0;
            while (used < bytes.length) {
                int count = handle.read(bytes, used, bytes.length - used);
                if (count < 0) break;
                if (count == 0) throw new IOException("READ_STALLED");
                used += count;
            }
            if (used != 37 || bytes[36] != '\n' || !before.same(handle.stat())) throw new IOException("BOOT_READ_INVALID");
        }
    }
    private static String[] fds() throws IOException {
        String[] names = new File("/proc/self/fd").list();
        if (names == null) throw new IOException("FD_LIST_FAILED");
        return names;
    }
    private static int fdCount() throws IOException { return fds().length; }
    private static int sourceCount() throws Exception {
        int count = 0;
        for (String name : fds()) {
            try { if (SOURCE.equals(Os.readlink("/proc/self/fd/" + name))) count++; }
            catch (android.system.ErrnoException vanished) {
                if (vanished.errno != android.system.OsConstants.ENOENT) throw vanished;
            }
        }
        return count;
    }
    private DescriptorOwnershipCheck() { }
}
