package net.elfradio.d31bootstrap.media;

import android.os.Build;
import java.io.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.webrtc.NativeLibraryLoader;

/** 宿主提供已验证的当前core APK，不从系统Context推断自身APK。 */
public final class ApkMediaLibrary implements NativeLibraryLoader {
    private final File apk, cache;
    private final String sha256;
    private static String loadedHash;
    /** 只读APK及ZIP目录，不创建缓存、不加载库；包签名由宿主先核验。 */
    public static JSONObject inspect(File apk,String expectedSha256,String abi)throws Exception {
        if(expectedSha256==null||!expectedSha256.matches("[a-f0-9]{64}"))throw new IOException("MEDIA_APK_HASH_REQUIRED");
        if(!"armeabi-v7a".equals(abi)&&!"arm64-v8a".equals(abi))throw new IOException("MEDIA_ABI_UNSUPPORTED");
        if(!apk.isFile()||apk.length()>96L*1024*1024||!MediaFiles.hash(apk).equals(expectedSha256))throw new IOException("MEDIA_APK_CHANGED");
        try(ZipFile zip=new ZipFile(apk)){
            ZipEntry entry=zip.getEntry("lib/"+abi+"/libjingle_peerconnection_so.so");
            if(entry==null||entry.getSize()<1||entry.getSize()>32L*1024*1024)throw new IOException("MEDIA_NATIVE_ENTRY_INVALID");
            return new JSONObject().put("operation","media_native_readiness").put("state","ARCHIVE_ENTRY_OBSERVED")
                    .put("apk_hash_match",true).put("abi",abi).put("native_bytes",entry.getSize())
                    .put("jni_loaded","NOT_CHECKED").put("linker_dependencies","NOT_CHECKED_ON_DEVICE")
                    .put("audio_record_identity","NOT_VERIFIED");
        }
    }
    public ApkMediaLibrary(File verifiedCoreApk, String expectedSha256, File privateCache) throws Exception {
        if(expectedSha256==null || !expectedSha256.matches("[a-f0-9]{64}"))throw new IOException("MEDIA_APK_HASH_REQUIRED");
        apk=MediaFiles.plain(verifiedCoreApk.getAbsoluteFile());
        cache=MediaFiles.directory(privateCache.getAbsoluteFile());sha256=expectedSha256;
    }
    public boolean load(String name) {
        synchronized(ApkMediaLibrary.class){
            if(!"jingle_peerconnection_so".equals(name))return false;
            if(loadedHash!=null)return loadedHash.equals(sha256);
            try {
                // D31目标为API23；不使用API21/22上无法可靠判断进程位宽的推测。
                if(Build.VERSION.SDK_INT<23)return false;
                String abi=android.os.Process.is64Bit()?"arm64-v8a":"armeabi-v7a";
                File library=extract(apk,sha256,cache,abi);
                System.load(library.getAbsolutePath());loadedHash=sha256;return true;
            } catch(Exception failure){return false;} catch(LinkageError failure){return false;}
        }
    }
    static File extract(File apk, String expected, File cache, String abi) throws Exception {
        if(!"armeabi-v7a".equals(abi)&&!"arm64-v8a".equals(abi))throw new IOException("MEDIA_ABI_UNSUPPORTED");
        if(!apk.isFile()||apk.length()>96L*1024*1024||!MediaFiles.hash(apk).equals(expected))throw new IOException("MEDIA_APK_CHANGED");
        File directory=MediaFiles.directory(new File(MediaFiles.directory(cache),expected+"/"+abi));
        try(MediaFiles.Lease ignored=MediaFiles.lease(directory);ZipFile zip=new ZipFile(apk)){
            ZipEntry entry=zip.getEntry("lib/"+abi+"/libjingle_peerconnection_so.so");
            if(entry==null||entry.getSize()<1||entry.getSize()>32L*1024*1024)throw new IOException("MEDIA_NATIVE_ENTRY_INVALID");
            File output=MediaFiles.plain(new File(directory,"libjingle_peerconnection_so.so"));
            if(output.exists()){
                if(!matches(output,entry))throw new IOException("MEDIA_NATIVE_CACHE_CHANGED");
                return output;
            }
            File partial=MediaFiles.plain(new File(directory,"library.part"));
            if(!partial.createNewFile())throw new IOException("MEDIA_NATIVE_PENDING_WRITE");
            try {
                try(InputStream in=zip.getInputStream(entry);FileOutputStream out=new FileOutputStream(partial)){
                    byte[] bytes=new byte[32768];int count;long total=0;
                    while((count=in.read(bytes))!=-1){total+=count;if(total>entry.getSize())throw new IOException("MEDIA_NATIVE_SIZE_MISMATCH");out.write(bytes,0,count);}
                    out.getFD().sync();
                }
                if(!matches(partial,entry)||!partial.setReadable(true,true)||!partial.setExecutable(true,true)
                        ||!partial.renameTo(output))throw new IOException("MEDIA_NATIVE_COMMIT_FAILED");
                return output;
            } finally {if(partial.exists())partial.delete();}
        }
    }
    private static boolean matches(File file, ZipEntry entry) throws Exception {
        if(!file.isFile()||file.length()!=entry.getSize())return false;
        CRC32 crc=new CRC32();try(InputStream in=new FileInputStream(MediaFiles.plain(file))){
            byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)crc.update(buffer,0,n);
        }
        return crc.getValue()==entry.getCrc();
    }
}
