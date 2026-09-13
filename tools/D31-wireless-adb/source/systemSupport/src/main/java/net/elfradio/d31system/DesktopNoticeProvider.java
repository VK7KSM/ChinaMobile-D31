package net.elfradio.d31system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class DesktopNoticeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private void checkCaller() {
        int uid=Binder.getCallingUid();
        if(uid==Process.myUid() || uid==Process.SYSTEM_UID || uid==0 || uid==2000) return;
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        if(packages!=null) for(String name:packages) if("com.starnet.nexui".equals(name)) return;
        throw new SecurityException("仅桌面可读取通知显示状态");
    }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        checkCaller();
        if(!"snapshot".equals(method)) throw new IllegalArgumentException("未知只读操作");
        long token=Binder.clearCallingIdentity();
        try { return NoticeRepository.get(getContext()).snapshot(); }
        finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        checkCaller();
        Bundle state=NoticeRepository.get(getContext()).snapshot();
        MatrixCursor cursor=new MatrixCursor(new String[]{"sms_unread","telegram_pending"});
        cursor.addRow(new Object[]{state.getInt("sms_unread"),state.getBoolean("telegram_pending")?1:0});
        cursor.setNotificationUri(getContext().getContentResolver(),NoticeRepository.URI);
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.elfradio.notification-state"; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
