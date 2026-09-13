package net.elfradio.d31bootstrap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 执行真实生成shell，仅用隔离目录及函数替身模拟挂载，不触及系统挂载。 */
public class RemoteBaselineMountTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private void runCase(String initial, String fault, boolean success, String expectedMode,
                         boolean changed) throws Exception {
        File bash = new File("C:/Program Files/Git/bin/bash.exe");
        if (!bash.isFile()) bash = new File("/bin/bash");
        Assume.assumeTrue("需要本地bash执行隔离shell", bash.isFile());
        File root = temporary.newFolder();
        File folder = new File(root, "system/priv-app/D31ElfRemote"); assertTrue(folder.mkdirs());
        File baseline = new File(folder, "D31ElfRemote.apk");
        Files.write(baseline.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        File candidate = new File(root, "candidate.apk");
        Files.write(candidate.toPath(), "candidate".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "mounts").toPath(), initial.getBytes(StandardCharsets.UTF_8));
        String hash = RescueFiles.sha256(candidate);
        if (fault.equals("hash")) hash = new String(new char[64]).replace('\0', '0');
        String command = RemoteManualBootstrap.baselineReplaceCommand(new JSONObject()
                .put("path", "candidate.apk").put("sha256", hash))
                .replace(RemoteUpdatePlatform.BASELINE.getPath(), "/system/priv-app/D31ElfRemote/D31ElfRemote.apk")
                .replace("/system/bin/busybox", "busybox").replace("/proc/mounts", "mounts")
                .replace("/system", "./system");
        String fixture = "PATH=/usr/bin:/bin\nexport PATH\n"
                + "count=0\nbusybox() { command \"$@\"; }\n"
                + "mount() { count=$((count+1)); echo \"$*\" >> mount-calls; "
                + (fault.equals("restore") ? "[ \"$count\" != 2 ] || return 7; " : "")
                + (fault.equals("acquire") ? "[ \"$count\" != 1 ] || return 7; " : "")
                + (fault.equals("restore-no-effect") ? "[ \"$count\" != 2 ] || return 0; " : "")
                + "printf 'none ./system ext4 %s,relatime 0 0\\n' \"${2#remount,}\" > mounts; }\n"
                + "chown() { :; }\nchcon() { :; }\nsync() { :; }\n"
                + (fault.equals("copy") ? "cp() { return 9; }\n" : "");
        File script = new File(root, "check.sh");
        Files.write(script.toPath(), (fixture + command).getBytes(StandardCharsets.UTF_8));
        File log = new File(root, "output.txt");
        Process process = new ProcessBuilder(bash.getPath(), "check.sh").directory(root)
                .redirectErrorStream(true).redirectOutput(log).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            assertEquals(output, success, process.exitValue() == 0);
            if (expectedMode != null) assertTrue(new String(Files.readAllBytes(new File(root, "mounts").toPath()),
                    StandardCharsets.UTF_8).contains("ext4 " + expectedMode + ","));
            else assertFalse(new File(root, "mount-calls").exists());
            assertEquals(changed ? "candidate" : "old", new String(Files.readAllBytes(baseline.toPath()), StandardCharsets.UTF_8));
        } finally { process.destroy(); }
    }

    private static String mounted(String mode) { return "none ./system ext4 " + mode + ",relatime 0 0\n"; }
    @Test public void successRestoresReadonly() throws Exception { runCase(mounted("ro"), "", true, "ro", true); }
    @Test public void successPreservesReadwrite() throws Exception { runCase(mounted("rw"), "", true, "rw", true); }
    @Test public void copyFailureRestoresReadonly() throws Exception { runCase(mounted("ro"), "copy", false, "ro", false); }
    @Test public void hashFailurePreservesReadwrite() throws Exception { runCase(mounted("rw"), "hash", false, "rw", false); }
    @Test public void acquisitionFailureDoesNotCopy() throws Exception { runCase(mounted("ro"), "acquire", false, "ro", false); }
    @Test public void restorationCommandFailureCannotPass() throws Exception { runCase(mounted("ro"), "restore", false, "rw", true); }
    @Test public void restorationMustTakeEffect() throws Exception { runCase(mounted("ro"), "restore-no-effect", false, "rw", true); }
    @Test public void missingMountRejectsBeforeMutation() throws Exception { runCase("", "", false, null, false); }
    @Test public void duplicateMountRejectsBeforeMutation() throws Exception { runCase(mounted("ro") + mounted("rw"), "", false, null, false); }
    @Test public void ambiguousFlagsRejectBeforeMutation() throws Exception { runCase(mounted("ro,rw"), "", false, null, false); }
}
