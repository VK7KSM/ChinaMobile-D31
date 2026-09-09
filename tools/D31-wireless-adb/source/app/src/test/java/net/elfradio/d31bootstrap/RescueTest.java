package net.elfradio.d31bootstrap;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class RescueTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void rejectsUnsafeIdsAndInvalidLimits() {
        String[] ids = {"../x", "", "x/y", "a.b"};
        for (String id : ids) assertThrows(IllegalArgumentException.class,
                () -> RescueJobs.validate(id, "id", 30));
        assertThrows(IllegalArgumentException.class, () -> RescueJobs.validate("a", "", 30));
        assertThrows(IllegalArgumentException.class, () -> RescueJobs.validate("a", "id", 121));
        assertThrows(IllegalArgumentException.class, () -> RescueJobs.validate("a", "id", 0));
        assertThrows(IllegalArgumentException.class, () -> RescueJobs.validate("a", "x\0y", 30));
        assertThrows(IllegalArgumentException.class, () -> RescueJobs.validate("a", new String(new char[8193]).replace('\0', 'a'), 30));
    }

    @Test public void deduplicatesAndRejectsBusyWithoutBlockingReads() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        RescueJobs jobs = new RescueJobs(temp.newFolder(), (f,c,t) -> {
            if (!release.await(3, TimeUnit.SECONDS)) throw new Exception("timeout");
            return new JSONObject().put("state", "completed").put("exit_code", 7);
        });
        try {
            assertEquals("running", jobs.submit("a", "exit 7", 30).getString("state"));
            assertEquals("running", jobs.submit("a", "exit 7", 30).getString("state"));
            assertThrows(IllegalStateException.class, () -> jobs.submit("a", "id", 30));
            assertThrows(IllegalStateException.class, () -> jobs.submit("b", "id", 30));
            assertNotNull(jobs.get("a"));
            assertNull(jobs.get("missing"));
        } finally { release.countDown(); }
        long end = System.currentTimeMillis() + 3000;
        while (jobs.isBusy() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertFalse(jobs.isBusy());
        assertEquals(7, jobs.get("a").getInt("exit_code"));
    }

    @Test public void restoresInterruptedAndCorruptRecordsWithoutReplay() throws Exception {
        File root = temp.newFolder();
        File a = new File(root, "a"); assertTrue(a.mkdir());
        File b = new File(root, "b"); assertTrue(b.mkdir());
        RescueFiles.write(new File(a, "result.json"), "{\"state\":\"running\"}");
        RescueFiles.write(new File(b, "result.json"), "broken");
        RescueJobs jobs = new RescueJobs(root, (f,c,t) -> { throw new AssertionError("replayed"); });
        assertEquals("interrupted", jobs.get("a").getString("state"));
        assertEquals("interrupted", jobs.get("b").getString("state"));
    }

    @Test public void persistenceFailureCannotLookCompletedOrAcceptNewWork() throws Exception {
        RescueJobs jobs = new RescueJobs(temp.newFolder(), (folder,command,timeout) -> {
            assertTrue(new File(folder, "result.json.tmp").mkdir());
            return new JSONObject().put("state", "completed").put("exit_code", 0);
        });
        jobs.submit("failed-write", "true", 30);
        long end = System.currentTimeMillis() + 3000;
        while (jobs.isBusy() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertFalse(jobs.isBusy());
        assertThrows(java.io.IOException.class, () -> jobs.get("failed-write"));
        assertThrows(java.io.IOException.class, () -> jobs.submit("new-job", "true", 30));
    }

    @Test public void bootHookPreservesOriginalAndIsIdempotent() {
        String original = "#!/system/bin/sh\necho original\n";
        String patched = RescueInstaller.patchedHook(original);
        assertTrue(patched.endsWith("echo original\n"));
        assertEquals(patched, RescueInstaller.patchedHook(patched));
        assertThrows(IllegalArgumentException.class, () -> RescueInstaller.patchedHook("unknown"));
    }

    @Test public void boundedFilesAndShellQuoting() throws Exception {
        File file = temp.newFile();
        RescueFiles.write(file, "hello");
        assertEquals("hello", RescueFiles.read(file, 5));
        assertThrows(java.io.IOException.class, () -> RescueFiles.read(file, 4));
        assertEquals("'a'\\''b'", RescueFiles.quote("a'b"));
    }

    @Test public void httpRejectsBrowserWritesAndMalformedJson() throws Exception {
        RescueJobs jobs = new RescueJobs(temp.newFolder(), (f,c,t) ->
                new JSONObject().put("state", "completed").put("exit_code", 0));
        RescueHttpServer server = new RescueHttpServer(0, jobs, () -> "cached");
        server.start(1000, true);
        try {
            assertEquals(200, http(server, "/health", null, null));
            assertEquals(404, http(server, "/exec", null, null));
            assertEquals(400, http(server, "/exec", "broken", null));
            String request = "{\"id\":\"a\",\"command\":\"id\",\"timeout\":30}";
            assertEquals(400, http(server, "/exec", request, "https://example.org"));
            assertEquals(202, http(server, "/exec", request, null));
            assertEquals(200, http(server, "/jobs/a", null, null));
            long end = System.currentTimeMillis() + 3000;
            while (jobs.isBusy() && System.currentTimeMillis() < end) Thread.sleep(10);
            assertFalse(jobs.isBusy());
            assertEquals("completed", jobs.get("a").getString("state"));
            assertEquals(0, jobs.get("a").getInt("exit_code"));
        } finally { server.stop(); }
    }

    @Test public void largeRequestDoesNotUseTemporaryFiles() throws Exception {
        CountDownLatch executed = new CountDownLatch(1);
        String command = new String(new char[6000]).replace('\0', 'x');
        RescueJobs jobs = new RescueJobs(temp.newFolder(), (f,c,t) -> {
            assertEquals(command, c);
            executed.countDown();
            return new JSONObject().put("state", "completed").put("exit_code", 0);
        });
        RescueHttpServer server = new RescueHttpServer(0, jobs, () -> "cached");
        server.setTempFileManagerFactory(() -> new fi.iki.elonen.NanoHTTPD.TempFileManager() {
            public void clear() { }
            public fi.iki.elonen.NanoHTTPD.TempFile createTempFile(String hint) throws Exception {
                throw new java.io.IOException("临时目录不可用");
            }
        });
        server.start(1000, true);
        try {
            String body = new JSONObject().put("id", "large").put("command", command).toString();
            assertEquals(202, http(server, "/exec", body, null));
            assertTrue(executed.await(2, TimeUnit.SECONDS));
            assertEquals(200, http(server, "/health", null, null));
            assertEquals(400, http(server, "/exec", new String(new char[16385]).replace('\0', 'x'), null));
            assertEquals(200, http(server, "/health", null, null));
        } finally { server.stop(); }
    }

    private int http(RescueHttpServer server, String path, String body, String origin) throws Exception {
        try (java.net.Socket connection = new java.net.Socket("127.0.0.1", server.getListeningPort())) {
            connection.setSoTimeout(2000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String headers = (body == null ? "GET " : "POST ") + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n";
            connection.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            connection.getOutputStream().write(bytes);
            java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream()));
            int status = Integer.parseInt(input.readLine().split(" ")[1]);
            if (status >= 400) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = input.readLine()) != null) response.append(line).append('\n');
                System.err.println("HTTP响应诊断 " + path + " " + status + "\n" + response);
            }
            return status;
        }
    }
}
