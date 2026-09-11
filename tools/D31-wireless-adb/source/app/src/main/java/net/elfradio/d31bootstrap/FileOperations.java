package net.elfradio.d31bootstrap;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** 独立核心的文件操作；路径作为数据处理，不拼接shell命令。 */
final class FileOperations {
    // 平台边界集中在此，桌面测试使用真实本机文件系统，不依赖Android空桩。
    static class Access {
        Info stat(File file) throws IOException {
            try {
                StructStat s=Os.lstat(file.getPath());
                return new Info(OsConstants.S_ISDIR(s.st_mode),OsConstants.S_ISREG(s.st_mode),
                        OsConstants.S_ISLNK(s.st_mode),s.st_size,s.st_mtime*1000,
                        s.st_dev+":"+s.st_ino,s.st_mode&07777,s.st_uid,s.st_gid);
            } catch(ErrnoException error) {
                if(error.errno==OsConstants.ENOENT)return null;
                throw new IOException("读取文件属性失败："+file.getPath(),error);
            }
        }
        InputStream read(File file) throws IOException {
            try {
                FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
                try {
                    if(!OsConstants.S_ISREG(Os.fstat(fd).st_mode))throw new IOException("仅支持普通文件");
                    return new FileInputStream(fd);
                } catch(Exception error) { Os.close(fd);throw error; }
            } catch(ErrnoException error) { throw new IOException("打开源文件失败",error); }
        }
        FileOutputStream create(File file) throws IOException {
            try {
                return new FileOutputStream(Os.open(file.getPath(),OsConstants.O_WRONLY|OsConstants.O_CREAT
                        |OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600));
            } catch(ErrnoException error) { throw new IOException("创建临时文件失败，未覆盖已有路径",error); }
        }
        void hardLink(File source,File target)throws IOException {
            try { Os.link(source.getPath(),target.getPath()); }
            catch(ErrnoException error) {
                if(error.errno==OsConstants.EPERM||error.errno==OsConstants.EOPNOTSUPP||error.errno==OsConstants.ENOSYS)
                    throw new HardLinkUnavailableException(error);
                throw new IOException("原子创建目标失败，未覆盖："+target.getPath()+"；"+error.getMessage(),error);
            }
        }
        void unlink(File file)throws IOException {
            if(!file.delete())throw new IOException("移除旧名称失败："+file.getPath());
        }
        boolean rename(File source,File target)throws IOException {
            Info original=stat(source);
            if(original!=null&&original.regular&&!original.link) {
                try { hardLink(source,target); }
                catch(HardLinkUnavailableException unavailable) {
                    // FAT/exFAT沿用同卷rename；仅此分支并发保证有限，EEXIST不得进入。
                    return stat(target)==null&&source.renameTo(target);
                }
                try {
                    stableParent(source);
                    if(!original.same(stat(source)))throw new IOException("源路径已变化，保留两个名称");
                    unlink(source);
                }catch(IOException cleanup) { throw new PublishedException(source,target,cleanup); }
                return true;
            }
            // 目录和符号链接仍限于无外部并发改目录的既有合同。
            return source.renameTo(target);
        }
        long now() { return System.nanoTime(); }
        void protect(File file,int uid) throws IOException {
            try { Os.chown(file.getPath(),uid,uid);Os.chmod(file.getPath(),0600); }
            catch(ErrnoException error) { throw new IOException("设置快照权限失败",error); }
        }
    }
    static final class HardLinkUnavailableException extends IOException {
        HardLinkUnavailableException(Throwable cause) { super("本存储或权限不支持硬链接，使用兼容重命名",cause); }
    }
    static final class PublishedException extends IOException {
        final File target;
        PublishedException(File source,File target,IOException cause) {
            super("目标已发布，但旧名称未清理；目标："+target.getPath()+"；旧名称："+source.getPath(),cause);
            this.target=target;
        }
    }
    static final class Info {
        final boolean directory,regular,link;
        final long size,modified;
        final Object key;
        final Integer mode,uid,gid;
        Info(boolean directory,boolean regular,boolean link,long size,long modified,Object key,Integer mode,Integer uid,Integer gid) {
            this.directory=directory;this.regular=regular;this.link=link;this.size=size;this.modified=modified;
            this.key=key;this.mode=mode;this.uid=uid;this.gid=gid;
        }
        boolean same(Info other) {
            return other!=null&&key!=null&&key.equals(other.key)&&directory==other.directory&&regular==other.regular&&link==other.link;
        }
    }
    static JSONObject normalize(JSONObject value) throws Exception {
        String action=value.getString("action"),path=value.getString("path");
        if(!Arrays.asList("list","mkdir","copy","move","trash","delete").contains(action))throw new IOException("不支持的文件操作");
        validatePath(path);
        JSONObject result=new JSONObject().put("action",action).put("path",path);
        if("copy".equals(action)||"move".equals(action)) {
            String target=value.getString("target");validatePath(target);result.put("target",target);
            if(value.has("overwrite")&&!(value.get("overwrite") instanceof Boolean))throw new IOException("覆盖参数必须为布尔值");
            result.put("overwrite",value.optBoolean("overwrite",false));
        }
        int offset=value.optInt("offset",0);
        if(offset<0||offset>1000000)throw new IOException("列表页码无效");
        return result.put("offset",offset);
    }
    static void validatePath(String path)throws IOException {
        if(!(path.startsWith("/")||(File.separatorChar=='\\'&&new File(path).isAbsolute()))||path.length()>1024||path.indexOf('\0')>=0)throw new IOException("需要完整绝对路径");
        for(String part:path.replace('\\','/').split("/"))if("..".equals(part)||".".equals(part))throw new IOException("路径不能包含相对目录");
    }
    // 只解析父目录别名（例如/sdcard），保留末级链接本身，包括坏链接。
    static File entry(File file)throws IOException {
        File parent=file.getAbsoluteFile().getParentFile();
        return parent==null?file.getAbsoluteFile():new File(parent.getCanonicalFile(),file.getName());
    }
    static void stableParent(File file)throws IOException {
        File parent=file.getParentFile();
        if(parent!=null&&!parent.getAbsoluteFile().equals(parent.getCanonicalFile()))throw new IOException("操作期间父目录变成符号链接");
    }
    static JSONObject run(File job,JSONObject value)throws Exception { return run(job,value,new Access()); }
    static JSONObject run(File job,JSONObject value,Access fs)throws Exception {
        long started=fs.now();File cancel=new File(job,"cancel");
        JSONObject p=normalize(value),out=new JSONObject();
        String action=p.getString("action");File source=entry(new File(p.getString("path")));
        check(cancel,started,fs);
        if("list".equals(action))out=list(source,p.getInt("offset"),cancel,started,fs);
        else {
            File parent=source.getParentFile();
            if(parent==null)throw new IOException("不能操作根目录本身");
            if(!parent.isDirectory())throw new IOException("父目录不存在");
            stableParent(source);
            if("mkdir".equals(action)) {
                if(fs.stat(source)!=null||!source.mkdir())throw new IOException("新建目录失败或同名路径已存在");
                out.put("path",source.getPath());
            }else {
                if(fs.stat(source)==null)throw new IOException("源路径不存在");
                if("delete".equals(action)) {
                    remove(source,cancel,started,0,fs);
                    out.put("path",source.getPath());
                }else {
                    File target="trash".equals(action)?new File(parent,".elfremote-trash-"+job.getName()+"-"+source.getName())
                            :entry(new File(p.getString("target")));
                    transfer(source,target,job,p.optBoolean("overwrite"),"copy".equals(action),started,fs,out);
                    if("trash".equals(action))out.put("restore_to",source.getPath());
                }
            }
        }
        return new JSONObject().put("state","completed").put("exit_code",0).put("action",action)
                .put("elapsed_ms",(fs.now()-started)/1000000).put("output",out.toString()).put("truncated",false);
    }
    private static void transfer(File source,File target,File job,boolean overwrite,boolean copying,long start,Access fs,JSONObject out)throws Exception {
        if(target.getParentFile()==null||!target.getParentFile().isDirectory())throw new IOException("目标父目录不存在");
        String src=source.getAbsolutePath(),dst=target.getAbsolutePath();
        Info original=fs.stat(target),sourceInfo=fs.stat(source);
        if(sourceInfo==null)throw new IOException("源路径不存在");
        if(src.equals(dst)||dst.startsWith(src+File.separator)||src.startsWith(dst+File.separator)
                ||sourceInfo.same(original))throw new IOException("源与目标不能相同或互相包含");
        if(original!=null&&!overwrite)throw new IOException("目标已存在，请使用其他名称");
        if(copying&&sourceInfo.link)throw new IOException("复制遇到符号链接，请单独处理该链接");
        File cancel=new File(job,"cancel"),backup=new File(target.getParentFile(),".elfremote-replaced-"+job.getName()+"-"+target.getName());
        File stage=new File(target.getParentFile(),".elfremote-copy-"+job.getName());
        if(copying&&fs.stat(stage)!=null)throw new IOException("发现此前复制现场，未覆盖");
        if(original!=null&&fs.stat(backup)!=null)throw new IOException("同名目标备份已存在，未覆盖");
        boolean backed=false,staged=false;
        try {
            check(cancel,start,fs);stableParent(source);stableParent(target);
            if(original!=null) {
                if(!original.same(fs.stat(target))||!fs.rename(target,backup))throw new IOException("同名目标备份失败，未覆盖");
                backed=true;
            }
            check(cancel,start,fs);
            if(copying) { copy(source,stage,cancel,start,0,fs);staged=true; }
            check(cancel,start,fs);stableParent(source);stableParent(target);
            if(fs.stat(target)!=null||!fs.rename(copying?stage:source,target))throw new IOException(copying?
                    "复制完成后提交目标失败":"移动失败；跨存储请先复制，核对后再移除原件");
            staged=false;
        } catch(Exception error) {
            // 已建立目标硬链接后只报告清理失败，不能回滚覆盖已经发布的内容。
            if(error instanceof PublishedException&&((PublishedException)error).target.equals(target))
                throw new IOException(error.getMessage()+(backed?"；原目标备份："+backup.getPath():""),error);
            String extra="";
            if(staged)try { remove(stage,null,0,0,fs); }catch(Exception cleanup) { extra+="；临时复制现场保留于："+stage.getPath();error.addSuppressed(cleanup); }
            if(backed) {
                try {
                    stableParent(target);
                    if(fs.stat(target)!=null||!fs.rename(backup,target))throw new IOException("目标已被占用或恢复重命名失败");
                } catch(Exception restore) { extra+="；原目标保留于："+backup.getPath();error.addSuppressed(restore); }
            }
            if(!extra.isEmpty())throw new IOException(error.getMessage()+extra,error);
            throw error;
        }
        out.put("path",target.getPath());
        if(backed)out.put("backup_path",backup.getPath());
    }
    private static JSONObject list(File dir,int offset,File cancel,long start,Access fs)throws Exception {
        File[] files=dir.listFiles();if(files==null)throw new IOException("目录不存在或不可读取");
        Arrays.sort(files,(a,b)->a.isDirectory()==b.isDirectory()?a.getName().compareTo(b.getName()):a.isDirectory()?-1:1);
        JSONArray entries=new JSONArray();int next=offset,bytes=0;
        for(;next<files.length&&entries.length()<12;next++) {
            check(cancel,start,fs);File f=files[next];Info info=null;
            try { info=fs.stat(f); }catch(IOException unavailable) { /* 属性不可读时不猜测权限和链接状态。 */ }
            JSONObject item=new JSONObject().put("name",f.getName()).put("directory",info!=null?info.directory:f.isDirectory())
                    .put("link",info!=null?info.link:JSONObject.NULL).put("bytes",info!=null?(info.regular?info.size:0):(f.isFile()?f.length():0))
                    .put("modified_ms",info!=null&&info.link?info.modified:f.lastModified());
            if(info!=null&&info.mode!=null)item.put("mode",info.mode).put("uid",info.uid).put("gid",info.gid);
            int size=item.toString().getBytes("UTF-8").length;
            if(entries.length()>0&&bytes+size>11000)break;
            bytes+=size;entries.put(item);
        }
        return new JSONObject().put("path",dir.getCanonicalPath()).put("entries",entries).put("total",files.length).put("next",next<files.length?next:-1);
    }
    private static void copy(File from,File to,File cancel,long start,int depth,Access fs)throws Exception {
        check(cancel,start,fs);stableParent(from);stableParent(to);
        if(depth>64)throw new IOException("目录层级过深");
        Info before=fs.stat(from);
        if(before==null||before.link)throw new IOException("源路径不存在或复制遇到符号链接");
        boolean created=false;
        try {
            if(before.directory) {
                if(!to.mkdir())throw new IOException("创建复制目录失败");created=true;
                File[] children=from.listFiles();if(children==null)throw new IOException("源目录不可读取");
                for(File child:children) {
                    if(!before.same(fs.stat(from)))throw new IOException("复制期间源目录变化");
                    copy(child,new File(to,child.getName()),cancel,start,depth+1,fs);
                }
            }else if(before.regular) {
                long modified=from.lastModified();
                try(InputStream input=fs.read(from);FileOutputStream output=fs.create(to)) {
                    created=true;byte[] buffer=new byte[65536];int n;long copied=0;
                    while((n=input.read(buffer))!=-1) {
                        check(cancel,start,fs);copied+=n;if(copied>before.size)throw new IOException("复制期间源文件增长");output.write(buffer,0,n);
                    }
                    output.getFD().sync();
                    if(copied!=before.size)throw new IOException("复制期间源文件长度变化");
                }
                Info after=fs.stat(from);
                if(!before.same(after)||after.size!=before.size||from.lastModified()!=modified
                        ||!hash(from,cancel,start,fs).equals(hash(to,cancel,start,fs)))throw new IOException("复制期间源文件变化或完整校验失败");
                to.setLastModified(modified);
            }else throw new IOException("仅复制普通文件或目录");
            check(cancel,start,fs);
        } catch(Exception error) {
            if(created)try { remove(to,null,0,0,fs); }catch(Exception cleanup) {
                error.addSuppressed(cleanup);throw new IOException(error.getMessage()+"；临时复制文件待清理："+to.getPath(),error);
            }
            throw error;
        }
    }
    static String hash(File file,File cancel,long start,Access fs)throws Exception {
        stableParent(file);MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=fs.read(file)) {
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1) { check(cancel,start,fs);hash.update(buffer,0,n); }
        }
        check(cancel,start,fs);return FileCommit.hex(hash.digest());
    }
    static void check(File cancel,long start,Access fs)throws IOException {
        if(Thread.currentThread().isInterrupted()||cancel.exists())throw new IOException("文件操作已停止");
        if(fs.now()-start>120000000000L)throw new IOException("文件操作超时，可能尚未全部完成");
    }
    private static void remove(File file,File cancel,long start,int depth,Access fs)throws IOException {
        if(cancel!=null)check(cancel,start,fs);
        stableParent(file);
        if(depth>64)throw new IOException("目录层级过深，删除未全部完成");
        Info before=fs.stat(file);if(before==null)throw new IOException("待删除路径已不存在");
        if(before.directory&&!before.link) {
            File[] children=file.listFiles();if(children==null)throw new IOException("目录不可读取，删除未全部完成");
            for(File child:children) {
                if(!before.same(fs.stat(file)))throw new IOException("删除期间目录发生变化");
                remove(child,cancel,start,depth+1,fs);
            }
        }
        if(cancel!=null)check(cancel,start,fs);
        stableParent(file);
        if(!before.same(fs.stat(file))||!file.delete())throw new IOException("无法删除或路径已变化："+file.getName());
    }
    private FileOperations(){}
}
