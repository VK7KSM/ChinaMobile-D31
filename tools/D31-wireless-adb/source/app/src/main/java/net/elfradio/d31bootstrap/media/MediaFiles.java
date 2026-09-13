package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import org.json.JSONObject;

final class MediaFiles {
    static final long MAX_BYTES=8L*1024*1024;
    static File directory(File path) throws IOException {
        if (!path.getCanonicalFile().equals(path.getAbsoluteFile())) throw new IOException("MEDIA_LINK_PATH_REJECTED");
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("MEDIA_DIRECTORY_UNAVAILABLE");
        return path;
    }
    static File plain(File path) throws IOException {
        if (!path.getCanonicalFile().equals(path.getAbsoluteFile())) throw new IOException("MEDIA_LINK_PATH_REJECTED");
        return path;
    }
    static String hash(File path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(plain(path))) {
            byte[] bytes=new byte[8192]; int count;
            while((count=in.read(bytes))!=-1) digest.update(bytes,0,count);
        }
        StringBuilder out=new StringBuilder(); for(byte b:digest.digest()) out.append(String.format(java.util.Locale.US,"%02x",b&255));
        return out.toString();
    }
    static JSONObject read(File path) throws Exception {
        if (path.length()>32768) throw new IOException("MEDIA_RECEIPT_TOO_LARGE");
        try(InputStream in=new FileInputStream(plain(path)); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[2048]; int count;
            while((count=in.read(buffer))!=-1) { if(out.size()+count>32768) throw new IOException("MEDIA_RECEIPT_TOO_LARGE"); out.write(buffer,0,count); }
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    static void writeNew(File file, JSONObject value) throws Exception {
        plain(file); if(file.exists()) throw new IOException("MEDIA_NO_OVERWRITE");
        File temporary=plain(new File(file.getPath()+".tmp"));
        if(!temporary.createNewFile()) throw new IOException("MEDIA_PENDING_WRITE");
        try(FileOutputStream out=new FileOutputStream(temporary)) { out.write(value.toString().getBytes("UTF-8")); out.getFD().sync(); }
        if(!temporary.renameTo(file)) throw new IOException("MEDIA_ATOMIC_SAVE_FAILED");
    }
    static Lease lease(File root) throws Exception { return new Lease(plain(new File(directory(root),"media.lock"))); }
    static final class Lease implements AutoCloseable {
        private final RandomAccessFile file;
        private final FileLock lock;
        Lease(File path) throws Exception {
            file=new RandomAccessFile(path,"rw"); FileLock acquired=null;
            try { acquired=file.getChannel().tryLock(); if(acquired==null) throw new IOException("MEDIA_BUSY"); }
            catch(OverlappingFileLockException busy) { file.close(); throw new IOException("MEDIA_BUSY"); }
            catch(Exception failure) { file.close(); throw failure; }
            lock=acquired;
        }
        public void close() throws IOException { try {lock.release();} finally {file.close();} }
    }
}
