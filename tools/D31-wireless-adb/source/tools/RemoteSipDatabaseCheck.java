package net.elfradio.d31bootstrap;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** 仅从原库读取表结构；所有测试记录均写入独立内存数据库，不注册测试账号。 */
public final class RemoteSipDatabaseCheck {
    private static JSONObject rows(SQLiteDatabase db)throws Exception{
        JSONObject result=new JSONObject();try(Cursor c=db.query("sipaccount",null,null,null,null,null,"_id")){
            while(c.moveToNext()){
                ContentValues v=new ContentValues();DatabaseUtils.cursorRowToContentValues(c,v);JSONObject row=new JSONObject();
                for(String k:v.keySet())row.put(k,v.get(k)==null?JSONObject.NULL:v.get(k));result.put(row.getString("_id"),row);
            }
        }return result;
    }
    private static ContentValues values(JSONObject row)throws Exception{
        ContentValues result=new ContentValues();java.util.Iterator<String> keys=row.keys();while(keys.hasNext()){
            String k=keys.next();Object v=row.get(k);if(v==JSONObject.NULL)result.putNull(k);else result.put(k,String.valueOf(v));
        }return result;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=0||android.system.Os.getuid()!=0)throw new IllegalArgumentException();
        String schema;
        try(SQLiteDatabase source=SQLiteDatabase.openDatabase("/data/data/com.starnet.nexui/databases/sipaccount.db",null,SQLiteDatabase.OPEN_READONLY);
            Cursor c=source.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='sipaccount'",null)){
            if(!c.moveToFirst())throw new IllegalStateException();schema=c.getString(0);
        }
        try(SQLiteDatabase db=SQLiteDatabase.create(null)){
            db.execSQL(schema);
            for(int n=1;n<=4;n++){
                JSONObject p=new JSONObject().put("username","fixture"+n).put("password","fixture-secret").put("server","fixture.invalid").put("port",5061).put("transport","tls");
                JSONObject row=RemoteSip.configuredRow(db,rows(db),new JSONObject(),p);
                db.insertOrThrow("sipaccount",null,values(row));
                JSONObject actual=rows(db).getJSONObject(String.valueOf(n));
                if(!RemoteSip.same(row,actual)||!RemoteSip.configHash(row).equals(RemoteSip.configHash(actual)))throw new IllegalStateException("新增线路读回不一致");
            }
            JSONObject before=rows(db),original=before.getJSONObject("2");
            JSONObject p=new JSONObject().put("username","changed").put("password","fixture-other").put("server","other.invalid").put("port",5060).put("transport","tcp");
            JSONObject changed=RemoteSip.configuredRow(db,before,original,p);
            db.beginTransaction();try{db.update("sipaccount",values(changed),"_id=?",new String[]{"2"});}finally{db.endTransaction();}
            if(!RemoteSip.same(before,rows(db)))throw new IllegalStateException("事务回滚不完整");
            db.update("sipaccount",values(changed),"_id=?",new String[]{"2"});JSONObject after=rows(db);
            for(String id:new String[]{"1","3","4"})if(!RemoteSip.same(before.getJSONObject(id),after.getJSONObject(id)))throw new IllegalStateException("非目标线路被修改");
            if(!RemoteSip.same(changed,after.getJSONObject("2")))throw new IllegalStateException("目标线路更新不一致");
        }
        System.out.println("FOUR_LINES_INSERT_READBACK_ROLLBACK_ISOLATION_OK");System.exit(0);
    }
}
