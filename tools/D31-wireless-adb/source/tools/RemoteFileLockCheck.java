package net.elfradio.d31bootstrap;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
public final class RemoteFileLockCheck {
    public static void main(String[] args)throws Exception{
        if(args.length!=0 || android.system.Os.getuid()!=0)throw new IllegalArgumentException("需要本地root只读核验");
        try(RandomAccessFile f=new RandomAccessFile("/data/local/d31-remote/runtime/updates/supervisor.lock","rw");
            FileLock lock=f.getChannel().tryLock()) {
            System.out.println("LOCK_AVAILABLE="+(lock!=null));
        }catch(Exception error){System.out.println("LOCK_ERROR="+error.getClass().getName()+":"+error.getMessage());}
        System.exit(0);
    }
}
