package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import net.elfradio.d31bootstrap.qr.QrCode;
import org.json.JSONObject;

/**
 * 设备自助生成本机管理链接：站在机器跟前的人按一下按钮，向服务端申请，本地画成二维码。
 *
 * 与 D22 同一条通路（`POST /api/devices/share-link`），链接由服务端生成，设备只负责请求与显示。
 * D31 的差别只有一处：设备身份存在核心的私有目录里（root 0700），应用进程要经 root 通道读，
 * 和本界面其他按钮用的是同一个 RootTransport。
 *
 * 这条与服务端下发的 show_share_link 任务是两个入口，互不影响：任务那条留给管理员远程触发。
 */
final class DeviceShareLink {
    static final String PATH = "/api/devices/share-link";
    private static final String IDENTITY = "/data/local/d31-remote/runtime/state/identity.json";
    /** 本次点击的请求编号；同一个编号重试返回同一条链接，不会每按一次多开一条。 */
    private static final String REQUEST_ID = "[A-Za-z0-9_.:-]{1,96}";

    static final class Result {
        final String url, qrText;
        final long expiresAt;
        final boolean duplicate;
        Result(String url, String qrText, long expiresAt, boolean duplicate) {
            this.url = url; this.qrText = qrText; this.expiresAt = expiresAt; this.duplicate = duplicate;
        }
    }

    static JSONObject request(String deviceId, String token, String requestId) throws Exception {
        if (!requestId.matches(REQUEST_ID)) throw new IOException("SHARE_LINK_REQUEST_ID_INVALID");
        return new JSONObject().put("device_id", deviceId).put("token", token).put("request_id", requestId);
    }

    /**
     * 校验服务端回体。主机与「二维码文本是网址的大写形式」这两条要复核：
     * 屏幕上显示的是网址、二维码里编码的是 qr_text，两者不一致就是钓鱼的形状，
     * 而扫码的人看的是码不是字。这是服务端保证的合同，钉死它不会把自己弄哑。
     */
    static Result parse(JSONObject reply) throws Exception {
        String url = reply.optString("url");
        if (url.isEmpty() || !url.startsWith("https://")) throw new IOException("SHARE_LINK_URL_INVALID");
        if (!new java.net.URI(url).getHost().equals(new java.net.URI(RemoteProtocol.BASE).getHost()))
            throw new IOException("SHARE_LINK_URL_HOST_INVALID");
        String qrText = reply.optString("qr_text", url.toUpperCase(Locale.US));
        if (!qrText.equals(url.toUpperCase(Locale.US))) throw new IOException("SHARE_LINK_QR_TEXT_MISMATCH");
        return new Result(url, qrText, reply.optLong("expires_at", 0), reply.optBoolean("duplicate"));
    }

    /** 设备身份在核心私有目录里，应用进程没有权限，只能经 root 读。 */
    static JSONObject identity() throws Exception {
        RootTransport.Result read = RootTransport.execute("cat " + IDENTITY, 8000);
        if (read.exit != 0) throw new IOException("读取设备身份失败");
        JSONObject saved = new JSONObject(read.output.trim());
        String deviceId = saved.optString("device_id"), token = saved.optString("token");
        if (deviceId.isEmpty() || token.isEmpty()) throw new IOException("设备尚未配对");
        return new JSONObject().put("device_id", deviceId).put("token", token);
    }

    /** 全大写网址走二维码的字母数字模式，纠错 M；1280x800 屏上留足扫码余量。 */
    static Bitmap qrBitmap(String text, int sizePx) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int quiet = 2, modules = code.size + quiet * 2;
        int cell = Math.max(1, sizePx / modules), side = modules * cell;
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.RGB_565);
        int[] row = new int[side];
        for (int y = 0; y < side; y++) {
            int my = y / cell - quiet;
            for (int x = 0; x < side; x++) row[x] = code.getModule(x / cell - quiet, my) ? Color.BLACK : Color.WHITE;
            output.setPixels(row, 0, side, 0, y, side, 1);
        }
        return output;
    }

    static void generate(Activity activity) {
        Toast.makeText(activity, "正在生成管理链接…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                JSONObject who = identity();
                JSONObject reply = RemoteHttp.cloud(PATH, request(who.getString("device_id"),
                        who.getString("token"), UUID.randomUUID().toString()));
                Result result = parse(reply);
                main.post(() -> show(activity, result));
            } catch (Exception failure) {
                String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                main.post(() -> Toast.makeText(activity, "生成失败：" + detail, Toast.LENGTH_LONG).show());
            }
        }, "d31-share-link").start();
    }

    static String remaining(long expiresAt, long now) {
        if (expiresAt <= 0) return "";
        long minutes = Math.max(0, (expiresAt - now + 59999) / 60000);
        return minutes >= 60 ? "有效期约 " + (minutes / 60) + " 小时" : "有效期约 " + minutes + " 分钟";
    }

    private static void show(Activity activity, Result result) {
        if (activity.isFinishing()) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int shorter = Math.min(activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels);
        int pad = (int) (8 * density);
        int qrPx = Math.max(160, Math.min(shorter - (int) (96 * density), (int) (320 * density)));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(Color.WHITE);

        ImageView image = new ImageView(activity);
        image.setImageBitmap(qrBitmap(result.qrText, qrPx));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(qrPx, qrPx);
        size.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(image, size);

        TextView address = new TextView(activity);
        address.setText(result.url);
        address.setTextColor(Color.BLACK);
        address.setTextSize(13);
        address.setPadding(0, pad, 0, 0);
        address.setTextIsSelectable(true);
        box.addView(address);

        TextView hint = new TextView(activity);
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(11);
        String note = remaining(result.expiresAt, System.currentTimeMillis());
        hint.setText(result.duplicate ? "沿用刚才那条链接" + (note.isEmpty() ? "" : "，" + note) : note);
        box.addView(hint);

        new AlertDialog.Builder(activity).setTitle("扫码管理这台设备").setView(box)
                .setPositiveButton("复制链接", (dialog, which) -> {
                    ClipboardManager clip = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clip == null) return;
                    clip.setPrimaryClip(ClipData.newPlainText("elfRemote 管理链接", result.url));
                    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null).show();
    }

    private DeviceShareLink() {}
}
