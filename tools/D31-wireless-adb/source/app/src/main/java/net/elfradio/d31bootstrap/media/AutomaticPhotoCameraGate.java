package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.*;

/** availability未知不当作空闲；等待只供自动工作线程调用。 */
final class AutomaticPhotoCameraGate {
    private final Map<String,Boolean> available=new HashMap<>();
    private boolean closed;
    synchronized void update(String id,boolean value){if(!closed){available.put(id,value);notifyAll();}}
    synchronized void requireAvailable(String id,long waitMs)throws Exception {
        long until=System.nanoTime()+waitMs*1000000L;
        while(!closed&&!available.containsKey(id)){
            long left=(until-System.nanoTime())/1000000L;if(left<=0)break;wait(Math.max(1,left));
        }
        if(closed||!available.containsKey(id))throw new IOException("AUTO_PHOTO_CAMERA_UNKNOWN");
        if(!Boolean.TRUE.equals(available.get(id)))throw new IOException("AUTO_PHOTO_CAMERA_BUSY");
    }
    synchronized void close(){closed=true;available.clear();notifyAll();}
}
