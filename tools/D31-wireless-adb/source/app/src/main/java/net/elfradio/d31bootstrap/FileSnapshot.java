package net.elfradio.d31bootstrap;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;

/** 生成稳定快照后上传，源文件始终保留。 */
final class FileSnapshot {
    static JSONObject run(File folder,JSONObject p)throws Exception{
        return run(folder,p,new FileOperations.Access());
    }
    static JSONObject run(File folder,JSONObject p,FileOperations.Access fs)throws Exception{
        FileOperations.validatePath(p.getString("source"));FileOperations.validatePath(p.getString("target"));
        File source=FileOperations.entry(new File(p.getString("source"))),target=FileOperations.entry(new File(p.getString("target")));
        long end=fs.now()+1800L*1000000000;
        check(folder,end,fs);
        FileOperations.Info before=fs.stat(source);
        if(before==null||!before.regular||before.link||target.getParentFile()==null
                ||!target.getParentFile().isDirectory()||fs.stat(target)!=null)throw new IOException("源文件不存在、为链接或快照路径不可用");
        long size=before.size,modified=source.lastModified();if(size<0||size>FileCommit.MAX_BYTES)throw new IOException("文件最大支持4 GB");
        if(target.getParentFile().getUsableSpace()<size+32L*1024*1024)throw new IOException("快照空间不足");
        File temp=new File(target.getPath()+".tmp");if(fs.stat(temp)!=null)throw new IOException("已有未完成快照，请检查");
        boolean created=false;
        try{
            FileOperations.stableParent(source);FileOperations.stableParent(temp);
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long copied=0;
            try(InputStream in=fs.read(source);FileOutputStream out=fs.create(temp)){
                created=true;
                byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){
                    check(folder,end,fs);copied+=n;if(copied>size)throw new IOException("源文件正在变化");out.write(buf,0,n);hash.update(buf,0,n);
                }out.getFD().sync();
            }
            FileOperations.Info after=fs.stat(source);
            if(copied!=size||!before.same(after)||after.size!=size||source.lastModified()!=modified)throw new IOException("源文件正在变化，请稍后取回");
            check(folder,end,fs);FileOperations.stableParent(temp);
            fs.protect(temp,p.getInt("uid"));
            JSONObject result=new JSONObject().put("state","completed").put("action","snapshot").put("bytes",size).put("sha256",FileCommit.hex(hash.digest())).put("path",target.getPath());
            RescueFiles.write(new File(folder,"snapshot.json"),result.toString());
            check(folder,end,fs);FileOperations.stableParent(target);
            try {
                if(fs.stat(target)!=null||!fs.rename(temp,target))throw new IOException("提交文件快照失败");
            }catch(FileOperations.PublishedException cleanup) {
                // 已发布快照仍可由元数据和完整哈希恢复；保留残留名称供清理，不撤销目标。
                created=false;throw cleanup;
            }
            created=false;return result;
        }finally{
            if(created){FileOperations.stableParent(temp);if(fs.stat(temp)!=null&&!temp.delete())throw new IOException("快照临时文件待清理："+temp.getPath());}
        }
    }
    static JSONObject recover(File folder)throws Exception{
        return recover(folder,new FileOperations.Access());
    }
    static JSONObject recover(File folder,FileOperations.Access fs)throws Exception{
        File meta=new File(folder,"snapshot.json");if(!meta.isFile())return null;JSONObject p=new JSONObject(RescueFiles.read(meta,4096));
        FileOperations.validatePath(p.getString("path"));File snapshot=FileOperations.entry(new File(p.getString("path")));
        FileOperations.Info info=fs.stat(snapshot);
        if(info==null||!info.regular||info.link||info.size!=p.getLong("bytes"))return null;
        long end=fs.now()+1800L*1000000000;MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=fs.read(snapshot)){
            byte[] bytes=new byte[65536];int n;
            while((n=in.read(bytes))!=-1){check(folder,end,fs);hash.update(bytes,0,n);}
        }
        check(folder,end,fs);
        return info.same(fs.stat(snapshot))&&FileCommit.hex(hash.digest()).equals(p.getString("sha256"))?p:null;
    }
    private static void check(File folder,long end,FileOperations.Access fs)throws IOException{
        if(Thread.currentThread().isInterrupted()||new File(folder,"cancel").exists())throw new IOException("文件取回已停止");
        if(fs.now()>end)throw new IOException("文件快照超时");
    }
}
