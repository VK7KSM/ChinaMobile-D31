package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;

/** 独立root核心只操作本机文件，不承担公网下载。 */
final class FileCommit {
    static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    static JSONObject run(File folder, JSONObject p) throws Exception {
        File source=new File(p.getString("source")), target=new File(p.getString("path")).getCanonicalFile();
        long size=p.getLong("size");String sha=p.getString("sha256");
        if(size<0||size>MAX_BYTES||!sha.matches("[a-f0-9]{64}")||!source.isFile()||source.length()!=size)throw new IOException("文件长度无效");
        if(!target.getParentFile().isDirectory() || target.isDirectory())throw new IOException("目标文件夹不存在或目标为文件夹");
        File temp=new File(target.getPath()+".elfremote-"+folder.getName()+".part");
        File backup=new File(target.getPath()+".elfremote-"+folder.getName()+".bak");
        if(temp.exists()||backup.exists())throw new IOException("本次文件原件或临时文件已存在，请先检查");
        if(target.exists()&&!p.optBoolean("overwrite"))throw new IOException("目标文件已存在，未替换");
        if(target.getParentFile().getUsableSpace()<size+32L*1024*1024)throw new IOException("目标位置剩余空间不足");
        JSONObject journal=new JSONObject().put("target",target.getPath()).put("temp",temp.getPath()).put("backup",backup.getPath())
                .put("size",size).put("sha256",sha).put("original",target.exists()).put("stage","copying");
        File jf=new File(folder,"file-commit.json");RescueFiles.write(jf,journal.toString());
        boolean moved=false;
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long copied=0,end=System.nanoTime()+1800L*1000000000;
            try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temp)){
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1){
                    if(Thread.currentThread().isInterrupted()||new File(folder,"cancel").exists())throw new IOException("文件接收已停止");
                    if(System.nanoTime()>end)throw new IOException("写入目标位置超时");
                    copied+=n;if(copied>size)throw new IOException("文件长度变化");out.write(buf,0,n);hash.update(buf,0,n);
                }
                out.getFD().sync();
            }
            if(copied!=size||!hex(hash.digest()).equals(sha))throw new IOException("文件校验失败，目标未修改");
            if("Dalvik".equals(System.getProperty("java.vm.name"))&&!target.getPath().startsWith("/storage/")) {
                if(target.isFile()) {
                    android.system.StructStat stat=android.system.Os.stat(target.getPath());
                    android.system.Os.chown(temp.getPath(),stat.st_uid,stat.st_gid);
                    android.system.Os.chmod(temp.getPath(),stat.st_mode&0777);
                }else android.system.Os.chmod(temp.getPath(),0644);
            }
            if(Thread.currentThread().isInterrupted()||new File(folder,"cancel").exists())throw new IOException("文件接收已停止");
            if(target.exists()&&!p.optBoolean("overwrite"))throw new IOException("目标文件已存在，未替换");
            journal.put("stage","prepared");RescueFiles.write(jf,journal.toString());
            if(target.exists()){if(!target.isFile()||!target.renameTo(backup))throw new IOException("无法保留原文件");moved=true;}
            if(!temp.renameTo(target))throw new IOException("无法提交目标文件");
            journal.put("stage","committed");RescueFiles.write(jf,journal.toString());
            return success(journal);
        }catch(Exception error){
            // 新文件已经提交后，结果保存失败不能覆盖它；启动恢复通过完整校验辨认。
            if(!target.exists()&&moved&&!backup.renameTo(target))throw new IOException("文件写入失败，原件保留在备份路径",error);
            if(temp.exists())temp.delete();throw error;
        }
    }
    static JSONObject recover(File folder) throws Exception {
        File jf=new File(folder,"file-commit.json");if(!jf.isFile())return null;
        JSONObject j=new JSONObject(RescueFiles.read(jf,8192));
        File target=new File(j.getString("target")),backup=new File(j.getString("backup")),temp=new File(j.getString("temp"));
        if(!"copying".equals(j.optString("stage"))&&target.isFile()&&target.length()==j.getLong("size")&&RescueFiles.sha256(target).equals(j.getString("sha256")))return success(j);
        if(!target.exists()&&backup.isFile()&&!backup.renameTo(target))throw new IOException("原文件恢复失败");
        if(temp.isFile())temp.delete();return null;
    }
    private static JSONObject success(JSONObject j)throws Exception{
        boolean backed=new File(j.getString("backup")).isFile();
        return new JSONObject().put("state","completed").put("exit_code",0).put("action","committed")
                .put("sha256",j.getString("sha256")).put("bytes",j.getLong("size"))
                .put("output","文件已保存到 "+j.getString("target")+(backed?"\n原件保留在 "+j.getString("backup"):""));
    }
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
}
