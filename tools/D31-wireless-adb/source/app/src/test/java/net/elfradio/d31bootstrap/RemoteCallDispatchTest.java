package net.elfradio.d31bootstrap;

import java.lang.reflect.*;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** 调用真实分派方法；仅跳过会启动文件和Android服务的构造器，无生产同名替身。 */
public class RemoteCallDispatchTest {
    private static Object empty(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field access=unsafe.getDeclaredField("theUnsafe");access.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(access.get(null),type);
    }
    private static void field(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    @Test public void daemonRoutesOldCallOnlyWhenVisualSlotIsIdle()throws Exception{
        for(boolean busy:new boolean[]{false,true}){
            RemoteMediaSessionsTest.Wire wire=new RemoteMediaSessionsTest.Wire();
            RemoteMediaSessions sessions=new RemoteMediaSessions(()->wire,RemoteMediaSessionsTest.HASH,
                    java.net.URI.create("https://v.elfradio.net"),new RemoteMediaSessionsTest.Time());
            sessions.setAvailable(true);sessions.setPttAvailable(true);
            RemoteDaemon daemon=(RemoteDaemon)empty(RemoteDaemon.class);RemoteVisualMedia visual=(RemoteVisualMedia)empty(RemoteVisualMedia.class);
            field(visual,"active",busy);field(daemon,"media",sessions);field(daemon,"visual",visual);
            Method consume=RemoteDaemon.class.getDeclaredMethod("consumeMedia",JSONObject.class);consume.setAccessible(true);
            consume.invoke(daemon,RemoteMediaSessionsTest.offer().put("mode","call"));assertEquals(busy?0:1,wire.starts);
            consume.invoke(daemon,RemoteMediaSessionsTest.offer().put("mode","managed_media_prepare_v1"));assertEquals(busy?0:1,wire.starts);
            sessions.close();
        }
    }
}
