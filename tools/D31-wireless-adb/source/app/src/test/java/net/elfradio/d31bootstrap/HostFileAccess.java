package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.Assume;

/** 桌面真实文件系统适配；不将Windows权限猜成Unix权限。 */
class HostFileAccess extends FileOperations.Access {
    @Override FileOperations.Info stat(File file)throws IOException {
        try {
            BasicFileAttributes s=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            Integer mode=null,uid=null,gid=null;
            if(file.toPath().getFileSystem().supportedFileAttributeViews().contains("unix")) {
                Map<String,Object> unix=Files.readAttributes(file.toPath(),"unix:mode,uid,gid",LinkOption.NOFOLLOW_LINKS);
                mode=((Number)unix.get("mode")).intValue()&07777;uid=((Number)unix.get("uid")).intValue();gid=((Number)unix.get("gid")).intValue();
            }
            Object key=s.fileKey();
            // Windows的公开NIO接口不提供fileKey；用真实创建时间和isSameFile作桌面身份比较。
            if(key==null)key=new HostKey(file,s.creationTime(),s.isSymbolicLink());
            return new FileOperations.Info(s.isDirectory(),s.isRegularFile(),s.isSymbolicLink(),s.size(),s.lastModifiedTime().toMillis(),key,mode,uid,gid);
        }catch(NoSuchFileException missing){return null;}
    }
    @Override InputStream read(File file)throws IOException {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("仅支持普通文件");
        return Files.newInputStream(file.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
    }
    @Override FileOutputStream create(File file)throws IOException {
        Files.createFile(file.toPath());return new FileOutputStream(file);
    }
    @Override void protect(File file,int uid) { }
    @Override void hardLink(File source,File target)throws IOException { Files.createLink(target.toPath(),source.toPath()); }
    @Override void unlink(File file)throws IOException { Files.delete(file.toPath()); }
    private static final class HostKey {
        final File file;final FileTime created;final boolean link;
        HostKey(File file,FileTime created,boolean link){this.file=file;this.created=created;this.link=link;}
        @Override public boolean equals(Object other){
            if(!(other instanceof HostKey))return false;HostKey key=(HostKey)other;
            if(!created.equals(key.created)||link!=key.link)return false;
            try{return link?file.equals(key.file):Files.isSameFile(file.toPath(),key.file.toPath());}
            catch(IOException error){return false;}
        }
        @Override public int hashCode(){return created.hashCode();}
    }
    static void link(File link,File target)throws IOException {
        try { Files.createSymbolicLink(link.toPath(),target.toPath()); }
        catch(UnsupportedOperationException|FileSystemException|SecurityException unavailable) {
            Assume.assumeNoException("本机不能创建符号链接，须由Android真机补验",unavailable);
        }
    }
}
