package dev.octoshrimpy.quik.feature.phone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class SipMessageStore extends SQLiteOpenHelper {
    public static final int DIRECTION_IN = 0;
    public static final int DIRECTION_OUT = 1;
    public static final int STATE_RECEIVED = 0;
    public static final int STATE_PENDING = 1;
    public static final int STATE_SENT = 2;
    public static final int STATE_FAILED = 3;

    public static final class Message {
        public final long id;
        public final String peer;
        public final String body;
        public final int direction;
        public final int state;
        public final long timestamp;
        public final String detail;

        Message(long id, String peer, String body, int direction, int state, long timestamp, String detail) {
            this.id = id;
            this.peer = peer;
            this.body = body;
            this.direction = direction;
            this.state = state;
            this.timestamp = timestamp;
            this.detail = detail;
        }
    }

    public static final class Conversation {
        public final String peer;
        public final String body;
        public final long timestamp;

        Conversation(String peer, String body, long timestamp) {
            this.peer = peer;
            this.body = body;
            this.timestamp = timestamp;
        }
    }

    public SipMessageStore(Context context) {
        super(context.getApplicationContext(), "d31_sip_messages.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, body TEXT NOT NULL, direction INTEGER NOT NULL, state INTEGER NOT NULL, timestamp INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX messages_peer_time ON messages(peer, timestamp)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN detail TEXT NOT NULL DEFAULT ''");
    }

    public long add(String peer, String body, int direction, int state) {
        ContentValues values = new ContentValues();
        values.put("peer", peer);
        values.put("body", body);
        values.put("direction", direction);
        values.put("state", state);
        values.put("timestamp", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("messages", null, values);
    }

    public void setState(long id, int state) {
        setState(id, state, "");
    }

    public void setState(long id, int state, String detail) {
        ContentValues values = new ContentValues();
        values.put("state", state);
        values.put("detail", detail == null ? "" : detail);
        getWritableDatabase().update("messages", values, "id=?", new String[]{Long.toString(id)});
    }

    public void setLatestPendingState(String peer, String body, int state) {
        getWritableDatabase().execSQL(
            "UPDATE messages SET state=? WHERE id=(SELECT id FROM messages WHERE peer=? AND body=? AND direction=? AND state=? ORDER BY id DESC LIMIT 1)",
            new Object[]{state, peer, body, DIRECTION_OUT, STATE_PENDING});
    }

    public List<Conversation> conversations() {
        return conversations("");
    }

    public List<Conversation> conversations(String filter) {
        ArrayList<Conversation> result = new ArrayList<>();
        String sql = "SELECT m.peer,m.body,m.timestamp FROM messages m WHERE m.id=(SELECT x.id FROM messages x WHERE x.peer=m.peer ORDER BY x.timestamp DESC,x.id DESC LIMIT 1) AND (?='' OR EXISTS(SELECT 1 FROM messages s WHERE s.peer=m.peer AND (instr(lower(s.peer),lower(?))>0 OR instr(lower(s.body),lower(?))>0))) ORDER BY m.timestamp DESC,m.id DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{filter, filter, filter})) {
            while (cursor.moveToNext()) {
                result.add(new Conversation(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
            }
        }
        return result;
    }

    public List<Message> messages(String peer) {
        ArrayList<Message> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("messages",
                new String[]{"id", "peer", "body", "direction", "state", "timestamp", "detail"},
                "peer=?", new String[]{peer}, null, null, "timestamp ASC,id ASC")) {
            while (cursor.moveToNext()) {
                result.add(new Message(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3), cursor.getInt(4), cursor.getLong(5), cursor.getString(6)));
            }
        }
        return result;
    }
}
