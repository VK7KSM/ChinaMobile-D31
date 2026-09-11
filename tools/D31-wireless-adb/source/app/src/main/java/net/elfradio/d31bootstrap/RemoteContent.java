package net.elfradio.d31bootstrap;

import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import java.io.IOException;

/** 与Android 6的content命令一致，独立root进程使用外部引用取得Provider。 */
final class RemoteContent implements AutoCloseable {
    private final Object manager,provider;
    private final Class<?> managerType,providerType;
    private final IBinder token=new Binder();
    private final String authority;

    RemoteContent(Uri uri)throws Exception{
        authority=uri.getAuthority();
        managerType=Class.forName("android.app.IActivityManager");
        providerType=Class.forName("android.content.IContentProvider");
        manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        Object holder=managerType.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class)
                .invoke(manager,authority,0,token);
        if(holder==null)throw new IOException("配置接口不存在");
        provider=holder.getClass().getField("provider").get(holder);
        if(provider==null){close();throw new IOException("配置接口未就绪");}
    }
    Bundle call(String method,Bundle extras)throws Exception{
        return (Bundle)providerType.getMethod("call",String.class,String.class,String.class,Bundle.class)
                .invoke(provider,null,method,null,extras);
    }
    Cursor query(Uri uri,String[] projection)throws Exception{
        return (Cursor)providerType.getMethod("query",String.class,Uri.class,String[].class,String.class,
                String[].class,String.class,Class.forName("android.os.ICancellationSignal"))
                .invoke(provider,null,uri,projection,null,null,null,null);
    }
    @Override public void close()throws Exception{
        managerType.getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(manager,authority,token);
    }
}
