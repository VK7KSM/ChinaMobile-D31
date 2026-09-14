package net.elfradio.d31bootstrap;

import java.io.File;
import java.security.PublicKey;
import org.json.JSONObject;

/** 独立真机只读校验入口，不进入应用制品、不执行下载或安装。 */
public final class RemoteUpdateContractCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || android.system.Os.getuid() != 0) throw new IllegalArgumentException("需要清单与APK路径");
        JSONObject offer = RemoteUpdateFiles.read(new File(args[0]));
        PublicKey key = RemoteUpdatePolicy.trustedKey();
        JSONObject manifest = RemoteUpdatePolicy.validate(offer, key, "local-validation-only", System.currentTimeMillis(), false);
        JSONObject archive = new RemoteUpdatePlatform().inspect(new File(args[1]));
        if (!RemoteUpdatePolicy.matches(manifest, archive)) throw new IllegalStateException("实际APK不匹配");
        JSONObject changed = new JSONObject(offer.toString()); changed.put("manifest_raw", changed.getString("manifest_raw") + " ");
        boolean tamperRejected = false, deviceRejected = false;
        try { RemoteUpdatePolicy.validate(changed, key, "local-validation-only", System.currentTimeMillis(), false); }
        catch (Exception expected) { tamperRejected = true; }
        try { RemoteUpdatePolicy.validate(offer, key, "different-device", System.currentTimeMillis(), false); }
        catch (Exception expected) { deviceRejected = true; }
        if (!tamperRejected || !deviceRejected) throw new IllegalStateException("反例未拒绝");
        System.out.println("SIGNED_MANIFEST_OK APK_METADATA_OK TAMPER_REJECTED WRONG_DEVICE_REJECTED version=" + archive.getInt("versionCode"));
        System.exit(0);
    }
}
