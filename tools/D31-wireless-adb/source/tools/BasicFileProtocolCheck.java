package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.json.JSONObject;

/** 验证基础制品的真实文件锁和硬链接提交，不安装应用或启动服务。 */
public final class BasicFileProtocolCheck {
    private static void require(boolean ok) throws IOException {
        if (!ok) throw new IOException("基础文件合同不符");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IOException("仅限D31文件验收");
        File root = new File(args[0]);
        if (!root.getCanonicalPath().equals(root.getAbsolutePath())
                || !root.getPath().matches("/data/local/tmp/d31-basic-protocol-[a-z0-9-]+") || !root.mkdir())
            throw new IOException("必须使用新的合成目录");
        try {
            File stage = new File(root, ".d31-basic-part-check"), target = new File(root, "result.bin");
            byte[] first = new byte[]{0, 1, 2}, second = new byte[]{3, 4};
            require(BasicFileTool.write(stage, 0, first, BasicFileTool.digest(first)) == 3);
            require(BasicFileTool.write(stage, 0, first, BasicFileTool.digest(first)) == 3);
            require(BasicFileTool.write(stage, 3, second, BasicFileTool.digest(second)) == 5);
            boolean refused = false;
            try { BasicFileTool.commit(stage, target, BasicFileTool.digest(first)); }
            catch (IOException expected) { refused = true; }
            require(refused && stage.isFile() && !target.exists());
            byte[] whole = new byte[]{0, 1, 2, 3, 4};
            BasicFileTool.commit(stage, target, BasicFileTool.digest(whole));
            require(!stage.exists() && Arrays.equals(whole, BasicFileTool.read(target, 0, 5)));
            File collision = new File(root, ".d31-basic-part-collision");
            BasicFileTool.write(collision, 0, first, BasicFileTool.digest(first));
            refused = false;
            try { BasicFileTool.commit(collision, target, BasicFileTool.digest(first)); }
            catch (IOException expected) { refused = true; }
            require(refused && collision.exists() && Arrays.equals(whole, BasicFileTool.read(target, 0, 5)));
            RescueFiles.write(new File(root, "result.json"), new JSONObject().put("passed", true)
                    .put("version_code", BuildConfig.VERSION_CODE).put("real_file_lock", true)
                    .put("real_link_commit", true).put("retry_no_duplicate", true)
                    .put("wrong_hash_rejected", true).put("existing_target_preserved", true).toString());
            System.out.println("BASIC_FILE_PROTOCOL_CHECK_OK");
        } catch (Exception error) {
            RescueFiles.write(new File(root, "failure.json"), new JSONObject().put("passed", false)
                    .put("error", error.toString()).toString());
            throw error;
        }
    }
}
