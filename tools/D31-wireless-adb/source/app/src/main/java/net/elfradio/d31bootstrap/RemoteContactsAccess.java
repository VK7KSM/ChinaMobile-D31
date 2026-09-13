package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;

/** 通讯录APP桥复用核心维护租约，不创建第二套维护锁。 */
public final class RemoteContactsAccess {
    public static RemoteAppOperation beginLocal(android.content.Context context,String id,String digest)throws Exception{
        return RemoteAppOperation.begin(context,RemoteAppOperation.CONTACTS,id,digest,new JSONObject());
    }
    public static AutoCloseable acquire(String digest) throws Exception {
        if (android.os.Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)
                || digest == null || !digest.matches("[a-f0-9]{64}"))
            throw new IOException("CONTACTS_ACTIVE_IDENTITY_INVALID");
        RemoteMaintenance.Lease lease = RemoteMaintenance.acquire();
        if (lease == null) throw new IOException("CONTACTS_MAINTENANCE_BUSY");
        try {
            RemoteMaintenance.requireUnreserved();
            RemoteMaintenance.requireRepairReady();
            JSONObject active = new JSONObject(RescueFiles.read(RemoteUpdatePlatform.ACTIVE, 4096));
            if (!digest.equals(active.getString("sha256"))
                    || active.getInt("versionCode") != BuildConfig.VERSION_CODE
                    || !active.getString("path").equals(System.getenv("CLASSPATH")))
                throw new IOException("CONTACTS_ACTIVE_APK_MISMATCH");
            return lease;
        } catch (Exception failure) {
            try { lease.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private RemoteContactsAccess() { }
}
