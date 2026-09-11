package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import android.system.ErrnoException;
import android.system.OsConstants;

final class RemoteFileLocks {
    private RemoteFileLocks(){}
    static FileLock tryExclusive(FileChannel channel)throws IOException{
        try{return channel.tryLock();}
        catch(IOException error){
            // 本机Android 6把其他进程持锁时的EAGAIN抛成IOException，桌面JDK则返回null。
            Throwable cause=error.getCause();
            if(cause instanceof ErrnoException){
                int errno=((ErrnoException)cause).errno;
                if(errno==OsConstants.EAGAIN || errno==OsConstants.EACCES)return null;
            }
            throw error;
        }
    }
}
