package com.btchat;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** מסד הנתונים המקומי. קבצים גדולים (שמע/קבצים) יישמרו בתיקיות ולא כאן. */
public class Db extends SQLiteOpenHelper {
    private static Db inst;

    static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private Db(Context c) { super(c, "btchat.db", null, 1); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT NOT NULL, color INTEGER, addr TEXT, blocked INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE convs(id TEXT PRIMARY KEY, type INTEGER NOT NULL, title TEXT, owner TEXT, members TEXT, "
                + "created INTEGER, muted INTEGER DEFAULT 0, last_ts INTEGER DEFAULT 0, last_text TEXT, unread INTEGER DEFAULT 0, gver INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, conv TEXT NOT NULL REFERENCES convs(id) ON DELETE CASCADE, "
                + "sender TEXT, sname TEXT, scolor INTEGER, ts INTEGER, kind TEXT, body TEXT, outgoing INTEGER, status INTEGER, hops INTEGER)");
        db.execSQL("CREATE INDEX idx_msg_conv ON messages(conv, ts)");
        // חבילות לשליחה/העברה (Store & Forward) - כולל הודעות שלי שממתינות לחיבור
        db.execSQL("CREATE TABLE relay(id TEXT PRIMARY KEY, json TEXT NOT NULL, expires INTEGER)");
        // מזהי הודעות שכבר התקבלו - מניעת כפילויות ולולאות
        db.execSQL("CREATE TABLE seen(id TEXT PRIMARY KEY, ts INTEGER)");
        db.execSQL("CREATE TABLE log(_id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, line TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { }

    /** מחזיר true אם ההודעה חדשה (לא נראתה קודם). */
    boolean markSeen(String id) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("ts", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("seen", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    void log(String line) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("ts", System.currentTimeMillis());
            v.put("line", line);
            db.insert("log", null, v);
            db.execSQL("DELETE FROM log WHERE _id < (SELECT MAX(_id) - 500 FROM log)");
        } catch (Exception ignore) { }
    }
}
