import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

public final class HandoverRuntime implements HandoverPolicy.Ops {
    static final String ROOT = "/data/local/d31-startup-handover";
    static final String PATCH = "/data/local/d31-patches/";
    static final String NEXUI = "com.starnet.nexui", IMSCC = "com.starnet.cmcc.imscc";
    static final String GETNUMBER = "com.starnet.getnumber";
    static final String[] FLAGS = {"sys.4g.enable", "sys.volte.enable"};
    static final String[][] TARGETS = {
        {"imscc", PATCH + "imscc-firefox-telegram-messages-wrapper-v3.apk", "/system/vendor/3rd-app/imscc.apk", "966c005e5416aeee2f0cd44b690915ef2465079ba5e32f968501182802fb1678", "21d9c16603ad8536320e5748a39b267cb6c82c592d348567e79f7535b235bc02"},
        {"nexui", PATCH + "nexui-v8-ethernet-gate.apk", "/system/vendor/3rd-app/nexui.apk", "992b7169112f8438e0d16898ca4d61a68995c1a7c674021025ebf51ce9096243", "6343737a28acedd366e1d5f6e26c6b72dd46b2b9c945fb857f1208988c7279c7"},
        {"tls", PATCH + "libvsip-tls12-dns-transport-v2.so", "/data/app-lib/nexui/libvsip.so", "defa367ee93f4759fbb7b4debb9c5e4648bc71695de1f02975014f256596be37", "24b829f88a275b7e01e227ff583036f7b8e5512eab2efc90410cb4713d965617"},
        {"getnumber", PATCH + "getnumber-cellular-labels-v1-unsigned.apk", "/system/vendor/3rd-app/getnumber.apk", "3b69433f5c56dd253f1e0ee5e323ecfd5859c7dbecd9b6f19332f78e15205fa8", "5e1541f16f3636b115d7587f9c47b2a3bdd97c1c945fdc6a621b64bd4bb50dc5"}
    };
    final Object pm;
    final Method getState, setState;
    final File pending = new File(ROOT, "pending.properties");
    boolean cellular = new File(ROOT, "cellular-enabled").isFile();
    final Properties originalFlags = new Properties();
    String[] packages() { return cellular ? new String[]{IMSCC, GETNUMBER, NEXUI} : new String[]{IMSCC, NEXUI}; }
    List<String[]> targets() { return Arrays.asList(TARGETS).subList(0, cellular ? 4 : 3); }
    HandoverRuntime() throws Exception {
        if (((Integer)Class.forName("android.system.Os").getMethod("getuid").invoke(null)) != 0) throw new SecurityException("Root required");
        pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        getState = api.getMethod("getApplicationEnabledSetting", String.class, int.class);
        setState = api.getMethod("setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
    }
    static String read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) { out.write(b, 0, n); if (out.size() > 2000000) throw new IOException("Too large"); }
            return out.toString("UTF-8");
        }
    }
    static String hash(File file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : d.digest()) s.append(String.format(Locale.US, "%02x", b & 255));
        return s.toString();
    }
    static String command(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[1024]; int n;
                while ((n = in.read(b)) != -1) synchronized(out) { if (out.size() < 65536) out.write(b, 0, n); }
            } catch (IOException ignored) { }
        });
        drain.setDaemon(true); drain.start();
        long end = System.nanoTime() + 10_000_000_000L;
        try {
            for (;;) {
                try {
                    int rc = p.exitValue(); drain.join(1000);
                    String text; synchronized(out) { text = out.toString("UTF-8"); }
                    if (rc != 0) throw new IOException(Arrays.toString(args) + " rc=" + rc + " " + text);
                    return text.trim();
                } catch (IllegalThreadStateException running) {
                    if (System.nanoTime() > end) throw new IOException("Command timeout " + Arrays.toString(args));
                    Thread.sleep(20);
                }
            }
        } finally { p.destroy(); }
    }
    static void log(String text) { System.out.println("UPTIME_MS=" + System.nanoTime()/1000000 + " " + text); }
    static String[] target(String id) {
        for (String[] t : TARGETS) if (t[0].equals(id)) return t;
        throw new IllegalArgumentException(id);
    }
    int state(String pkg) throws Exception { return (Integer)getState.invoke(pm, pkg, 0); }
    public void state(String pkg, int value) throws Exception {
        // 交接自行同步停止进程，避免包状态广播异步杀进程后重拉旧桌面。
        setState.invoke(pm, pkg, value, 1, 0, "com.android.shell");
        if (state(pkg) != value) throw new IOException("State mismatch " + pkg);
        log("STATE " + pkg + "=" + value);
    }
    public void journal(Map<String,Integer> states) throws Exception {
        if (pending.exists()) throw new IOException("Pending transaction exists");
        Properties props = new Properties();
        props.setProperty("boot", read(new File("/proc/sys/kernel/random/boot_id")).trim());
        for (Map.Entry<String,Integer> e : states.entrySet()) props.setProperty(e.getKey(), e.getValue().toString());
        if (cellular) for (String flag : FLAGS) {
            String value = command("/system/bin/getprop", flag);
            if (!value.equals("true") && !value.equals("false") && !value.isEmpty()) throw new IOException("Unexpected flag " + flag);
            originalFlags.setProperty(flag, value); props.setProperty(flag, value);
        }
        File temp = new File(ROOT, "pending.new");
        if (!temp.createNewFile()) throw new IOException("Journal staging exists");
        try (FileOutputStream out = new FileOutputStream(temp)) { props.store(out, "D31 startup rollback"); out.getFD().sync(); }
        if (!temp.renameTo(pending)) throw new IOException("Journal commit failed");
        command("/system/bin/sync"); log("JOURNAL_READY");
    }
    public void clearJournal() throws Exception {
        if (!pending.delete()) throw new IOException("Journal retained");
        log("JOURNAL_CLEARED");
    }
    public void stopped(String pkg) throws Exception {
        command("/system/bin/am", "force-stop", "--user", "0", pkg);
        // 原厂AMS可能在结束旧HOME任务之前重拉一次空进程；任务结束后再收口一次。
        command("/system/bin/am", "force-stop", "--user", "0", pkg);
        long end = System.nanoTime() + 3_000_000_000L;
        for (;;) {
            boolean found = false;
            File[] entries = new File("/proc").listFiles();
            if (entries == null) throw new IOException("No proc listing");
            for (File entry : entries) {
                if (!entry.getName().matches("[0-9]+")) continue;
                String name;
                try { name = read(new File(entry, "cmdline")).split("\u0000", 2)[0]; }
                catch (IOException vanished) { continue; }
                if (name.equals(pkg) || name.startsWith(pkg + ":")) { found = true; break; }
            }
            if (!found) {
                log("STOPPED " + pkg);
                if (pkg.equals(NEXUI)) FactoryInit.nexuiDefaultsIfRequired();
                return;
            }
            if (System.nanoTime() > end) throw new IOException("Process did not stop " + pkg);
            Thread.sleep(25);
        }
    }
    boolean mounted(String path) throws Exception {
        for (String line : read(new File("/proc/mounts")).split("\n")) {
            String[] p = line.split(" ");
            if (p.length > 1 && p[1].equals(path)) return true;
        }
        return false;
    }
    public void mount(String id) throws Exception {
        if (id.equals("cellular-flags")) {
            for (String flag : FLAGS) command("/system/bin/setprop", flag, "true");
            log("CELLULAR_FLAGS_ENABLED"); return;
        }
        String[] t = target(id);
        command("/system/bin/mount", "-o", "bind", t[1], t[2]); log("BOUND " + id);
    }
    public void unmount(String id) throws Exception {
        if (id.equals("cellular-flags")) {
            for (String flag : FLAGS) command("/system/bin/setprop", flag, originalFlags.getProperty(flag));
            log("CELLULAR_FLAGS_RESTORED"); return;
        }
        String[] t = target(id);
        if (mounted(t[2])) command("/system/bin/umount", t[2]); log("UNBOUND " + id);
    }
    public void verify(String id) throws Exception {
        if (id.equals("cellular-flags")) {
            for (String flag : FLAGS) if (!command("/system/bin/getprop", flag).equals("true")) throw new IOException("Flag mismatch " + flag);
            log("CELLULAR_FLAGS_VERIFIED"); return;
        }
        String[] t = target(id);
        String src = command("/system/bin/stat", "-c", "%d:%i", t[1]);
        String dst = command("/system/bin/stat", "-c", "%d:%i", t[2]);
        if (!mounted(t[2]) || !src.equals(dst)) throw new IOException("Mount mismatch " + id);
        log("VERIFIED " + id + " " + src);
    }
    void recover(boolean preparingReboot) throws Exception {
        Properties saved = new Properties();
        try (InputStream in = new FileInputStream(pending)) { saved.load(in); }
        boolean sameBoot = read(new File("/proc/sys/kernel/random/boot_id")).trim().equals(saved.getProperty("boot"));
        if (sameBoot && !preparingReboot) throw new IOException("Same-boot pending: inspect first");
        cellular = saved.containsKey(GETNUMBER);
        for (String pkg : packages()) {
            String value = saved.getProperty(pkg);
            if (!"0".equals(value) && !"1".equals(value)) throw new IOException("Invalid recovery state");
        }
        if (cellular && sameBoot) {
            for (String flag : FLAGS) {
                String value = saved.getProperty(flag);
                if (value == null || (!value.isEmpty() && !value.equals("true") && !value.equals("false"))) throw new IOException("Invalid saved flag");
            }
            for (String flag : FLAGS) command("/system/bin/setprop", flag, saved.getProperty(flag));
        }
        for (String pkg : packages()) state(pkg, Integer.parseInt(saved.getProperty(pkg)));
        if (sameBoot) log("STATES_RESTORED_REBOOT_REQUIRED_JOURNAL_RETAINED");
        else { clearJournal(); log("RECOVERED_PREVIOUS_BOOT_NO_PATCH_THIS_BOOT"); }
    }
    void preflight() throws Exception {
        if (!"alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys".equals(command("/system/bin/getprop", "ro.build.fingerprint"))) throw new IOException("Build mismatch");
        for (String[] t : targets()) {
            if (!hash(new File(t[1])).equals(t[3])) throw new IOException("Source mismatch " + t[0]);
            String actual = hash(new File(t[2]));
            boolean inertNumber = t[0].equals("getnumber") && actual.equals("2162cac91fa16a676e4b2987ad313e70e9e351bea2448af87e1e13c02f4cf94f");
            if (!actual.equals(t[3]) && !actual.equals(t[4]) && !inertNumber) throw new IOException("Target mismatch " + t[0]);
        }
        for (String pkg : packages()) if (state(pkg) != 0 && state(pkg) != 1) throw new IOException("Already disabled " + pkg);
        log("PREFLIGHT_OK");
    }
    static void requireIdleAudio() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
        Object audio = Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        int mode = (Integer)Class.forName("android.media.IAudioService").getMethod("getMode").invoke(audio);
        log("AUDIO_MODE=" + mode);
        if (mode != 0) throw new IOException("Audio busy: no handover");
    }
    public static void main(String[] args) {
        try { execute(args); }
        catch (Throwable failure) {
            System.err.println("HANDOVER_FAILED " + failure);
            failure.printStackTrace();
            try {
                HandoverRuntime self = new HandoverRuntime();
                if (!self.pending.exists() && (self.state(NEXUI) == 0 || self.state(NEXUI) == 1)) {
                    System.out.println(command("/system/bin/am", "start", "-n", NEXUI + "/.nexlauncher.LauncherActivity"));
                    log("FALLBACK_DESKTOP_STARTED");
                }
            } catch (Throwable fallback) { System.err.println("FALLBACK_FAILED " + fallback); }
            System.exit(1);
        }
    }
    static void execute(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Specify mode");
        HandoverRuntime self = new HandoverRuntime();
        if (args[0].equals("--preflight")) { self.preflight(); requireIdleAudio(); }
        else if (args[0].equals("--preflight-cellular")) { self.cellular = true; self.preflight(); requireIdleAudio(); }
        else if (args[0].equals("--check-state-api")) {
            self.preflight(); requireIdleAudio();
            for (String pkg : new String[]{IMSCC, NEXUI}) {
                int original = self.state(pkg);
                self.state(pkg, original);
            }
            log("STATE_API_SAME_VALUES_OK");
        }
        else if (args[0].equals("--recover")) { if (self.pending.exists()) self.recover(false); }
        else if (args[0].equals("--restore-states-for-reboot")) { if (self.pending.exists()) self.recover(true); }
        else if (args[0].equals("--apply")) {
            if (self.pending.exists()) throw new IOException("Pending recovery required");
            if (!"1".equals(command("/system/bin/getprop", "sys.boot_completed"))) throw new IOException("Boot incomplete");
            self.preflight();
            requireIdleAudio();
            for (String[] t : self.targets()) if (self.mounted(t[2])) throw new IOException("Preexisting bind");
            LinkedHashMap<String,Integer> states = new LinkedHashMap<>();
            for (String pkg : self.packages()) states.put(pkg, self.state(pkg));
            List<String> ids = new ArrayList<>(Arrays.asList("imscc", "nexui", "tls"));
            if (self.cellular) { ids.add("getnumber"); ids.add("cellular-flags"); }
            HandoverPolicy.apply(self, states, ids);
            log("HANDOVER_COMPLETE");
            System.out.println(command("/system/bin/am", "start", "-n", NEXUI + "/.nexlauncher.LauncherActivity"));
        } else throw new IllegalArgumentException("Unknown mode");
        System.out.println("D31_JAVA_EXIT=0"); System.exit(0);
    }
}
