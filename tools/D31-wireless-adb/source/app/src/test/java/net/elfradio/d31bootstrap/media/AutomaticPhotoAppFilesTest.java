package net.elfradio.d31bootstrap.media;

import java.io.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AutomaticPhotoAppFilesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void trustedContextAliasesResolveBeforeStrictMediaDirectoryCheck()throws Exception {
        File data=temp.newFolder("app"),files=new File(data,"files");assertTrue(files.mkdir());
        File alias=new File(data,"../app/files");
        assertNotEquals(alias.getAbsoluteFile(),alias.getCanonicalFile());
        try{MediaFiles.directory(new File(alias,"automatic-photo"));fail();}
        catch(IOException expected){assertEquals("MEDIA_LINK_PATH_REJECTED",expected.getMessage());}
        File canonical=AutomaticPhotoRunner.applicationFiles(new File(data,"../app"),alias);
        assertEquals(files.getCanonicalFile(),canonical);
        assertTrue(MediaFiles.directory(new File(canonical,"automatic-photo")).isDirectory());
    }
    @Test public void canonicalFilesMustBelongToSameApplicationDataDirectory()throws Exception {
        File data=temp.newFolder("owner"),other=temp.newFolder("other"),outside=new File(other,"files");assertTrue(outside.mkdir());
        try{AutomaticPhotoRunner.applicationFiles(data,outside);fail();}
        catch(IOException expected){assertEquals("AUTO_PHOTO_PRIVATE_DIRECTORY_INVALID",expected.getMessage());}
        try{AutomaticPhotoRunner.applicationFiles(data,new File(data,"cache"));fail();}
        catch(IOException expected){assertEquals("AUTO_PHOTO_PRIVATE_DIRECTORY_INVALID",expected.getMessage());}
    }
    @Test public void resolvedAliasAndCanonicalMediaRootsStillShareOneLease()throws Exception {
        File data=temp.newFolder("locked"),files=new File(data,"files");assertTrue(files.mkdir());
        File canonical=AutomaticPhotoRunner.applicationFiles(data,new File(data,"../locked/files"));
        try(MediaFiles.Lease lease=MediaFiles.lease(new File(canonical,"media"))){
            try{MediaFiles.lease(new File(files.getCanonicalFile(),"media"));fail();}
            catch(IOException expected){assertEquals("MEDIA_BUSY",expected.getMessage());}
        }
    }
    @Test public void descendantTraversalIsStillRejectedAfterTrustedRootResolution()throws Exception {
        File data=temp.newFolder("strict"),files=new File(data,"files");assertTrue(files.mkdir());
        File canonical=AutomaticPhotoRunner.applicationFiles(data,files);
        try{MediaFiles.plain(new File(canonical,"automatic-photo/../../outside"));fail();}
        catch(IOException expected){assertEquals("MEDIA_LINK_PATH_REJECTED",expected.getMessage());}
    }
    @Test public void realFilesystemAliasIsAcceptedOnlyAtTrustedRoot()throws Exception {
        File data=temp.newFolder("physical"),files=new File(data,"files");assertTrue(files.mkdir());
        File alias=new File(temp.getRoot(),"context-alias");directoryLink(alias,data);alias=androidCanonical(alias);
        assertEquals(data.getCanonicalFile(),alias.getCanonicalFile());
        File canonical=AutomaticPhotoRunner.applicationFiles(alias,androidCanonical(new File(alias,"files")));
        assertEquals(files.getCanonicalFile(),canonical);
        File outside=temp.newFolder("outside"),child=new File(canonical,"automatic-photo");directoryLink(child,outside);
        assertTrue(new File(outside,"result.json").createNewFile());
        try{MediaFiles.directory(androidCanonical(child));fail();}catch(IOException expected){assertEquals("MEDIA_LINK_PATH_REJECTED",expected.getMessage());}
        try{MediaFiles.plain(androidCanonical(new File(child,"result.json")));fail();}catch(IOException expected){assertEquals("MEDIA_LINK_PATH_REJECTED",expected.getMessage());}
    }
    private static File androidCanonical(File file){
        if(File.separatorChar!='\\')return file;
        // Windows File canonical不解析junction；用实际realpath模拟Android的同一返回合同。
        return new File(file.getPath()){@Override public File getCanonicalFile()throws IOException{return toPath().toRealPath().toFile();}};
    }
    private static void directoryLink(File link,File target)throws Exception {
        if(File.separatorChar=='\\'){
            Process process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.getAbsolutePath(),target.getAbsolutePath()).redirectErrorStream(true).start();
            try(InputStream in=process.getInputStream()){while(in.read()!=-1){}}
            assertEquals("离线夹具junction创建失败",0,process.waitFor());
        }else java.nio.file.Files.createSymbolicLink(link.toPath(),target.toPath());
    }
}
