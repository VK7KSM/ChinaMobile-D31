package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

/** 仿照Nexui读取实际已安装图标，只读诊断入口，不进入APK。 */
public final class RemoteIconCheck {
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
        PackageManager pm = context.getPackageManager();
        ActivityInfo activity = pm.getActivityInfo(new android.content.ComponentName(
                "net.elfradio.d31bootstrap", "net.elfradio.d31bootstrap.MainActivity"), 0);
        Drawable icon = activity.loadIcon(pm);
        TypedValue value = new TypedValue();
        pm.getResourcesForApplication(activity.applicationInfo).getValue(activity.getIconResource(), value, true);
        if (!(icon instanceof BitmapDrawable)) throw new IllegalStateException("非预期位图资源");
        Bitmap bitmap = ((BitmapDrawable) icon).getBitmap();
        System.out.println("label=" + activity.loadLabel(pm) + " density=" + context.getResources().getDisplayMetrics().densityDpi
                + " resourceDensity=" + value.density + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " intrinsic=" + icon.getIntrinsicWidth() + "x" + icon.getIntrinsicHeight());
        if (!"elfRemote".contentEquals(activity.loadLabel(pm)) || value.density != TypedValue.DENSITY_NONE
                || bitmap.getWidth() != 200 || bitmap.getHeight() != 166)
            throw new IllegalStateException("未加载原始分辨率图标");
        System.out.println("NATIVE_ICON_OK");
        System.exit(0);
    }
}
