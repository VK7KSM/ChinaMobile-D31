package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteFileCommandTest {
    private static final String APK = "/system/priv-app/D31ElfRemote/D31ElfRemote.apk";
    private JSONObject task() throws Exception {
        return new JSONObject().put("id","file-test").put("type","file_manage")
                .put("expires_at",2000).put("params",new JSONObject().put("action","list")
                        .put("path","/sdcard/引号' 空格$(false)"));
    }
    @Test public void fileTaskUsesDedicatedEntryAndQuotedJson() throws Exception {
        JSONObject task=task();
        JSONObject request=RemoteProtocol.commandRequest("test",task,1000,APK);
        String json=FileOperations.normalize(task.getJSONObject("params")).toString();
        assertTrue(request.getString("command").endsWith(RescueFiles.quote(json)));
        assertTrue(request.getString("command").contains("net.elfradio.d31bootstrap.RemoteFileCommand"));
        assertEquals(RemoteProtocol.localJobId("test","file-test"),request.getString("id"));
        assertEquals(120,request.getInt("timeout"));
    }
    @Test public void invalidPayloadExpiredAndCancelledAreRejected() throws Exception {
        for(String apk:new String[]{null,"/sdcard/test.apk",APK+":/data/other.apk"})
            assertThrows(Exception.class,()->RemoteProtocol.commandRequest("test",task(),1000,apk));
        assertThrows(Exception.class,()->RemoteProtocol.commandRequest("test",task(),2000,APK));
        assertThrows(Exception.class,()->RemoteProtocol.commandRequest("test",task().put("cancel_requested",true),1000,APK));
        JSONObject task=task(); task.getJSONObject("params").put("action","erase");
        assertThrows(Exception.class,()->RemoteProtocol.commandRequest("test",task,1000,APK));
    }
    @Test public void missingDirectoryIsAnErrorAndEmptyDirectoryIsAValidList() throws Exception {
        java.io.File root=java.nio.file.Files.createTempDirectory("d31-list").toFile();
        try {
            JSONObject p=new JSONObject().put("action","list").put("path",root.getAbsolutePath());
            JSONObject out=new JSONObject(FileOperations.run(root,p,new HostFileAccess()).getString("output"));
            assertEquals(0,out.getInt("total"));
            p.put("path",new java.io.File(root,"missing").getAbsolutePath());
            assertThrows(Exception.class,()->FileOperations.run(root,p,new HostFileAccess()));
        } finally { assertTrue(root.delete()); }
    }
    @Test public void deleteAndOverwriteSurviveCommandSerializationWithExistingTimeoutAndId()throws Exception {
        for(String action:new String[]{"delete","copy","move"}) {
            JSONObject task=task();task.getJSONObject("params").put("action",action).put("target","/sdcard/new ' $(false)").put("overwrite",true);
            JSONObject request=RemoteProtocol.commandRequest("test",task,1000,APK);
            assertEquals(120,request.getInt("timeout"));assertEquals(RemoteProtocol.localJobId("test","file-test"),request.getString("id"));
            JSONObject normalized=FileOperations.normalize(task.getJSONObject("params"));
            assertTrue(request.getString("command").endsWith(RescueFiles.quote(normalized.toString())));
            if(!"delete".equals(action))assertTrue(normalized.getBoolean("overwrite"));
        }
    }
    @Test public void duplicateDeleteCannotDeleteRecreatedFileAndFailedJobIsNotRetried()throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("d31-dedup");
        try {
            java.io.File jobs=root.resolve("jobs").toFile(),source=root.resolve("source").toFile();java.nio.file.Files.write(source.toPath(),new byte[]{1});
            String id=RemoteProtocol.localJobId("test","delete");JSONObject p=new JSONObject().put("action","delete").put("path",source.getPath());
            JSONObject result=RemoteFileCommand.execute(jobs,id,p,new HostFileAccess());assertEquals(source.getPath(),new JSONObject(result.getString("output")).getString("path"));
            java.nio.file.Files.write(source.toPath(),new byte[]{2});
            assertThrows(Exception.class,()->RemoteFileCommand.execute(jobs,id,p,new HostFileAccess()));assertArrayEquals(new byte[]{2},java.nio.file.Files.readAllBytes(source.toPath()));
            String failed=RemoteProtocol.localJobId("test","failed");p.put("path",root.resolve("missing").toString());
            assertThrows(Exception.class,()->RemoteFileCommand.execute(jobs,failed,p,new HostFileAccess()));
            JSONObject record=new JSONObject(RescueFiles.read(new java.io.File(new java.io.File(jobs,failed),"result.json"),4096));assertEquals("failed",record.getString("state"));assertEquals(1,record.getInt("exit_code"));
            java.nio.file.Files.write(root.resolve("missing"),new byte[]{3});assertThrows(Exception.class,()->RemoteFileCommand.execute(jobs,failed,p,new HostFileAccess()));assertTrue(java.nio.file.Files.exists(root.resolve("missing")));
        }finally{
            try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(root)){
                for(java.nio.file.Path file:files.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))java.nio.file.Files.delete(file);
            }
        }
    }
}
