package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteManualBootstrapTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void stopMarkerOwnershipPreservesOtherMaintenance() throws Exception {
        File stop = new File(temporary.newFolder(), "stop-supervisor");
        RemoteManualBootstrap.requireOwnedStop(stop); assertFalse(stop.exists());
        RescueFiles.write(stop, "system-migration\n");
        try { RemoteManualBootstrap.requireOwnedStop(stop); fail(); } catch (java.io.IOException expected) { }
        assertEquals("system-migration\n", RescueFiles.read(stop,128));
    }
    @Test public void ownInterruptedMarkerCanResumeWithoutDeletingItEarly() throws Exception {
        File stop = new File(temporary.newFolder(), "stop-supervisor");
        RescueFiles.write(stop, "manual-bootstrap\n");
        RemoteManualBootstrap.requireOwnedStop(stop);
        assertEquals("manual-bootstrap\n", RescueFiles.read(stop,128));
    }
    @Test public void explicitMaintenanceTracksClientButOrdinaryInstallKeeps85() {
        assertEquals(85,RemoteManualBootstrap.requiredSupervisor(false,98));
        assertEquals(96,RemoteManualBootstrap.requiredSupervisor(true,95));
        assertEquals(97,RemoteManualBootstrap.requiredSupervisor(true,97));
        assertEquals(98,RemoteManualBootstrap.requiredSupervisor(true,98));
    }
    @Test public void launchCommandHasNoBackgroundTaskAndRedirectsAllStandardDescriptors() {
        String command=RemoteManualBootstrap.launchCommand(new File("fixture's log"));
        assertTrue(command.startsWith("exec /system/bin/sh /system/bin/d31-elfremote-start "));
        assertTrue(command.contains("</dev/null >"));
        assertTrue(command.endsWith(" 2>&1"));
        assertFalse(command.endsWith("&"));
        assertTrue(command.contains("'\\''"));
    }
    @Test public void confirmsFreshMappedVersionAndClosesPipesWithoutKillingSupervisor() throws Exception {
        FakeLaunch access=new FakeLaunch();
        JSONObject receipt=launch(access);
        assertEquals("supervisor_confirmed",receipt.getString("state"));
        assertEquals(222,receipt.getInt("pid"));
        assertEquals(1,access.starts);
        assertEquals(0,access.pauses);
        assertTrue(access.process.stdout.closed && access.process.stderr.closed && access.process.stdin.closed);
        assertFalse(access.process.destroyed);
        assertTrue(new File(receipt.getString("log")+".json").isFile());
    }
    @Test public void stalePidVersionMapsAndHashCannotPass() throws Exception {
        for(String fault:new String[]{"same_pid","old_version","old_time","future_time","maps","hash","protocol","uid","pid_changed"}) {
            FakeLaunch access=new FakeLaunch();access.fault=fault;
            RemoteManualBootstrap.LaunchFailure failure=reject(access);
            assertEquals("unconfirmed",failure.receipt.getString("state"));
            assertEquals(fault,1,access.starts);
            assertFalse(access.process.destroyed);
        }
    }
    @Test public void zeroLauncherExitStillNeedsFreshHeartbeat() throws Exception {
        FakeLaunch access=new FakeLaunch();access.process.exit=0;access.readyAfter=200;
        assertEquals("supervisor_confirmed",launch(access).getString("state"));
        assertEquals(2,access.pauses);
        access=new FakeLaunch();access.process.exit=0;access.fault="maps";
        assertEquals(0,reject(access).receipt.getInt("launcher_exit_code"));
    }
    @Test public void nonzeroExitFailsPromptlyWithUniqueRetainedLogs() throws Exception {
        File root=temporary.newFolder();
        FakeLaunch first=new FakeLaunch();first.process.exit=7;
        FakeLaunch second=new FakeLaunch();second.process.exit=7;
        JSONObject a=reject(root,first).receipt,b=reject(root,second).receipt;
        assertNotEquals(a.getString("log"),b.getString("log"));
        assertEquals("fixture-stderr",RescueFiles.read(new File(a.getString("log")),128));
        assertEquals(7,a.getInt("launcher_exit_code"));
        assertEquals(0,first.pauses);
    }
    @Test public void retryAdoptsAlreadyHealthySupervisorWithoutLaunchingDuplicate() throws Exception {
        FakeLaunch access=new FakeLaunch();access.alreadyRunning=true;
        assertEquals("already_running",launch(access).getString("state"));
        assertEquals(0,access.starts);
    }
    @Test public void rollbackCanHandshakeActual85WithoutNewMaintenanceProtocol() throws Exception {
        FakeLaunch access=new FakeLaunch();access.version=85;
        assertEquals("supervisor_confirmed",launch(access).getString("state"));
        assertEquals(1,access.starts);
    }
    @Test public void handshakeAcceptsRealCacheOnlyMapsAndRejectsFakeEncodedNames() throws Exception {
        String cache="/data/dalvik-cache/arm64/system@priv-app@D31ElfRemote@D31ElfRemote.apk@classes.dex";
        FakeLaunch access=new FakeLaunch();access.mapPath=cache;
        assertEquals("supervisor_confirmed",launch(access).getString("state"));
        for(String fake:new String[]{"/fake"+cache,cache+" (deleted)",cache+".old",cache.replace("/arm64/","/arm64/prefix")}) {
            access=new FakeLaunch();access.mapPath=fake;
            assertEquals("unconfirmed",reject(access).receipt.getString("state"));
        }
    }
    @Test public void mainReportsFailureAndExitsOneInsteadOfUncaughtException() throws Exception {
        java.util.Set<String> locations=new java.util.LinkedHashSet<String>();
        for(Class<?> type:new Class<?>[]{RemoteManualBootstrap.class,RemoteUpdatePlatform.class,
                JSONObject.class,android.content.Context.class}) {
            locations.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        }
        StringBuilder classpath=new StringBuilder();
        for(String location:locations){if(classpath.length()>0)classpath.append(File.pathSeparator);classpath.append(location);}
        String executable=new File(new File(System.getProperty("java.home"),"bin"),
                File.separatorChar=='\\' ? "java.exe" : "java").getPath();
        File log=temporary.newFile();
        Process child=new ProcessBuilder(executable,"-cp",classpath.toString(),RemoteManualBootstrap.class.getName(),"invalid")
                .redirectErrorStream(true).redirectOutput(log).start();
        try {
            assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,child.exitValue());
            String output=RescueFiles.read(log,16000);
            assertTrue(output,output.contains("SecurityException"));
            assertFalse(output,output.contains("Exception in thread"));
        } finally { child.destroy(); }
    }
    @Test public void interruptedHandshakeKeepsProcessAndRestoresInterruptFlag() throws Exception {
        FakeLaunch access=new FakeLaunch();access.fault="maps";access.interrupt=true;
        try {
            reject(access);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(access.process.destroyed);
        } finally { Thread.interrupted(); }
    }
    @Test public void realChildFailureRetainsStderrAndExitStatus() throws Exception {
        FakeLaunch access=new FakeLaunch() {
            @Override public Process start(File log) throws Exception {
                starts++;
                String executable=new File(new File(System.getProperty("java.home"),"bin"),
                        File.separatorChar=='\\' ? "java.exe" : "java").getPath();
                String fixtureClasses=new File(FailingLauncher.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).getPath();
                return new ProcessBuilder(executable,"-cp",fixtureClasses,
                        FailingLauncher.class.getName()).redirectErrorStream(true).redirectOutput(log).start();
            }
            @Override public long elapsedTime() { return System.nanoTime()/1000000; }
            @Override public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
        access.fault="maps";
        try {
            RemoteManualBootstrap.launchSupervisor(temporary.newFolder(),access.baseline(),10000,access);
            fail();
        } catch(RemoteManualBootstrap.LaunchFailure failure) {
            assertEquals(7,failure.receipt.getInt("launcher_exit_code"));
            assertTrue(RescueFiles.read(new File(failure.receipt.getString("log")),4096).contains("fixture-child-stderr"));
        }
    }
    public static class FailingLauncher {
        public static void main(String[] args) { System.err.println("fixture-child-stderr");System.exit(7); }
    }
    private JSONObject launch(FakeLaunch access) throws Exception {
        return RemoteManualBootstrap.launchSupervisor(temporary.newFolder(),access.baseline(),500,access);
    }
    private RemoteManualBootstrap.LaunchFailure reject(FakeLaunch access) throws Exception {
        return reject(temporary.newFolder(),access);
    }
    private RemoteManualBootstrap.LaunchFailure reject(File root,FakeLaunch access) throws Exception {
        try { RemoteManualBootstrap.launchSupervisor(root,access.baseline(),500,access);fail("不能误判启动成功"); }
        catch(RemoteManualBootstrap.LaunchFailure failure) { return failure; }
        throw new AssertionError();
    }
    private static class FakeLaunch implements RemoteManualBootstrap.LaunchAccess {
        long elapsed,readyAfter;
        int starts,pauses,healthReads,version=98;
        boolean alreadyRunning,interrupt;
        String fault="",mapPath;
        final FakeProcess process=new FakeProcess();
        final String hash="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        JSONObject baseline() throws Exception {
            return new JSONObject().put("versionCode",version).put("sha256",hash)
                    .put("path","/system/priv-app/D31ElfRemote/D31ElfRemote.apk");
        }
        public long wallTime() { return 100000+elapsed; }
        public long elapsedTime() { return elapsed; }
        public String hash(File file) { return fault.equals("hash") ? "bad" : hash; }
        public String read(File file,int limit) throws Exception {
            if(file.getName().equals("maps")) {
                if(starts==0 && !alreadyRunning)throw new FileNotFoundException();
                return "70000000-70001000 r--p 00000000 103:00 123 "
                        +(fault.equals("maps") ? "/old.apk" : mapPath!=null ? mapPath : baseline().getString("path"))+"\n";
            }
            boolean fresh=alreadyRunning || (starts>0 && elapsed>=readyAfter);
            int pid=fresh && !fault.equals("same_pid") ? 222 : 111;
            if(starts>0 && fault.equals("pid_changed") && ++healthReads%2==0)pid=333;
            JSONObject health=new JSONObject().put("pid",pid).put("uid",fault.equals("uid") ? 1 : 0)
                    .put("time_ms",fault.equals("future_time") ? wallTime()+1000
                            : fault.equals("old_time") || !fresh ? 1 : wallTime())
                    .put("version_code",fault.equals("old_version") ? 85 : version);
            if(version>=96)health.put("maintenance_protocol",fault.equals("protocol") ? 0 : 1);
            return health.toString();
        }
        public Process start(File log) throws Exception {
            starts++;
            try(FileOutputStream out=new FileOutputStream(log)){out.write("fixture-stderr".getBytes("UTF-8"));}
            return process;
        }
        public void pause(long millis) throws InterruptedException {
            pauses++;
            if(interrupt)throw new InterruptedException();
            elapsed+=millis;
        }
    }
    private static final class FakeProcess extends Process {
        final TrackedInput stdout=new TrackedInput(),stderr=new TrackedInput();
        final TrackedOutput stdin=new TrackedOutput();
        Integer exit;
        boolean destroyed;
        public OutputStream getOutputStream(){return stdin;}
        public InputStream getInputStream(){return stdout;}
        public InputStream getErrorStream(){return stderr;}
        public int waitFor(){throw new AssertionError("不能等待常驻进程退出");}
        public int exitValue(){if(exit==null)throw new IllegalThreadStateException();return exit;}
        public void destroy(){destroyed=true;}
    }
    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(){super(new byte[0]);}
        @Override public void close(){closed=true;}
    }
    private static final class TrackedOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close(){closed=true;}
    }
}
