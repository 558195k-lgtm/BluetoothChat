package com.btchat;

import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** לוגיקת נתונים משותפת ל-UI ולשירות. אינה תלויה ב-Activity. */
public class Repo {
    static final long TTL_MS = 48L * 3600 * 1000;
    static final int MAX_HOPS = 8, MAX_TEXT = 4000;
    // מצבי הודעה יוצאת
    static final int ST_WAIT = 0, ST_SENT = 1, ST_DELIVERED = 2, ST_FAILED = 3, ST_RELAYED = 4;

    static SQLiteDatabase db(Context c) { return Db.get(c).getWritableDatabase(); }

    // ---------- אנשי קשר ----------
    static void upsertContact(Context c, String id, String name, int color, String addr) {
        if (id == null || id.equals(Profile.id(c))) return;
        SQLiteDatabase d = db(c);
        Cursor cu = d.rawQuery("SELECT id FROM contacts WHERE id=?", new String[]{id});
        boolean ex = cu.moveToFirst();
        cu.close();
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("color", color);
        if (addr != null) v.put("addr", addr);
        if (ex) d.update("contacts", v, "id=?", new String[]{id});
        else { v.put("id", id); d.insert("contacts", null, v); }
        ContentValues t = new ContentValues();
        t.put("title", name);
        d.update("convs", t, "id=? AND type=0", new String[]{"p:" + id});
    }

    static String contactName(Context c, String id) {
        if (id.equals(Profile.id(c))) return Profile.name(c);
        Cursor cu = db(c).rawQuery("SELECT name FROM contacts WHERE id=?", new String[]{id});
        try { return cu.moveToFirst() ? cu.getString(0) : "?"; } finally { cu.close(); }
    }

    static int contactColor(Context c, String id) {
        if (id.equals(Profile.id(c))) return Profile.color(c);
        Cursor cu = db(c).rawQuery("SELECT color FROM contacts WHERE id=?", new String[]{id});
        try { return cu.moveToFirst() ? cu.getInt(0) : 0xFF607D8B; } finally { cu.close(); }
    }

    static boolean isBlocked(Context c, String id) {
        Cursor cu = db(c).rawQuery("SELECT blocked FROM contacts WHERE id=?", new String[]{id});
        try { return cu.moveToFirst() && cu.getInt(0) == 1; } finally { cu.close(); }
    }

    static void setBlocked(Context c, String id, boolean b) {
        ContentValues v = new ContentValues();
        v.put("blocked", b ? 1 : 0);
        db(c).update("contacts", v, "id=?", new String[]{id});
    }

    /** מחזיר רשימת {id, name, blocked} */
    static List<String[]> contacts(Context c) {
        List<String[]> r = new ArrayList<String[]>();
        Cursor cu = db(c).rawQuery("SELECT id,name,blocked FROM contacts ORDER BY name", null);
        while (cu.moveToNext()) r.add(new String[]{cu.getString(0), cu.getString(1), cu.getString(2)});
        cu.close();
        return r;
    }

    // ---------- שיחות ----------
    static void ensurePrivate(Context c, String peerId) {
        ContentValues v = new ContentValues();
        v.put("id", "p:" + peerId);
        v.put("type", 0);
        v.put("title", contactName(c, peerId));
        v.put("members", "[]");
        v.put("created", System.currentTimeMillis());
        db(c).insertWithOnConflict("convs", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    static String convTitle(Context c, String conv) { return convStr(c, conv, "title", ""); }
    static String owner(Context c, String conv) { return convStr(c, conv, "owner", ""); }

    private static String convStr(Context c, String conv, String col, String def) {
        Cursor cu = db(c).rawQuery("SELECT " + col + " FROM convs WHERE id=?", new String[]{conv});
        try { return cu.moveToFirst() && cu.getString(0) != null ? cu.getString(0) : def; } finally { cu.close(); }
    }

    static boolean exists(Context c, String conv) {
        Cursor cu = db(c).rawQuery("SELECT 1 FROM convs WHERE id=?", new String[]{conv});
        try { return cu.moveToFirst(); } finally { cu.close(); }
    }

    static boolean isMuted(Context c, String conv) {
        Cursor cu = db(c).rawQuery("SELECT muted FROM convs WHERE id=?", new String[]{conv});
        try { return cu.moveToFirst() && cu.getInt(0) == 1; } finally { cu.close(); }
    }

    static void toggleMute(Context c, String conv) {
        db(c).execSQL("UPDATE convs SET muted=1-muted WHERE id=?", new Object[]{conv});
    }

    static List<String> members(Context c, String conv) {
        List<String> r = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(convStr(c, conv, "members", "[]"));
            for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
        } catch (Exception ignore) { }
        return r;
    }

    static boolean isMember(Context c, String conv, String id) { return members(c, conv).contains(id); }

    static void markRead(Context c, String conv) {
        db(c).execSQL("UPDATE convs SET unread=0 WHERE id=?", new Object[]{conv});
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(conv.hashCode());
    }

    static void deleteConv(Context c, String conv) {
        db(c).delete("convs", "id=?", new String[]{conv});   // ההודעות נמחקות ב-CASCADE
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(conv.hashCode());
    }

    static void deleteMessage(Context c, String id) { db(c).delete("messages", "id=?", new String[]{id}); }

    // ---------- יצירת חבילה יוצאת ----------
    /**
     * יוצר חבילה, שומר אותה בתור ההעברה, ומעיר את השירות.
     * to != null = הודעה פרטית; אחרת הודעת קבוצה (convId). store=true שומר גם להצגה בשיחה.
     */
    static String originate(Context c, String t, String kind, String convId, String to, String body, boolean store) {
        Context a = c.getApplicationContext();
        try {
            String me = Profile.id(a);
            String id = UUID.randomUUID().toString();
            long ts = System.currentTimeMillis();
            JSONObject p = new JSONObject();
            p.put("v", 1).put("t", t).put("id", id).put("from", me)
                    .put("fname", Profile.name(a)).put("fcolor", Profile.color(a))
                    .put("ts", ts).put("hop", 0).put("ttl", MAX_HOPS)
                    .put("kind", kind).put("body", body);
            if (to != null) p.put("to", to);
            else if (convId != null) p.put("conv", convId);
            p.put("path", new JSONArray().put(me));

            SQLiteDatabase d = db(a);
            d.beginTransaction();
            try {
                Db.get(a).markSeen(id);
                if (store) {
                    ContentValues m = new ContentValues();
                    m.put("id", id); m.put("conv", convId); m.put("sender", me);
                    m.put("sname", Profile.name(a)); m.put("scolor", Profile.color(a));
                    m.put("ts", ts); m.put("kind", kind); m.put("body", body);
                    m.put("outgoing", 1); m.put("status", ST_WAIT); m.put("hops", 0);
                    d.insert("messages", null, m);
                    d.execSQL("UPDATE convs SET last_ts=?, last_text=? WHERE id=?", new Object[]{ts, body, convId});
                }
                ContentValues r = new ContentValues();
                r.put("id", id); r.put("json", p.toString()); r.put("expires", ts + TTL_MS);
                d.insert("relay", null, r);
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
            BtService.kick(a);
            return id;
        } catch (Exception e) {
            Db.get(a).log("שגיאה ביצירת חבילה: " + e);
            return null;
        }
    }

    static void sendText(Context c, String convId, String text) {
        text = text.trim();
        if (text.length() == 0) return;
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        if (convId.startsWith("p:")) originate(c, "MSG", "text", convId, convId.substring(2), text, true);
        else originate(c, "MSG", "text", convId, null, text, true);
    }

    // ---------- הודעות נכנסות / מערכת ----------
    static void storeIncoming(Context c, String id, String conv, String from, String fname, int fcolor,
                              long ts, String kind, String body, int hop) {
        SQLiteDatabase d = db(c);
        ContentValues m = new ContentValues();
        m.put("id", id); m.put("conv", conv); m.put("sender", from); m.put("sname", fname);
        m.put("scolor", fcolor); m.put("ts", ts); m.put("kind", kind); m.put("body", body);
        m.put("outgoing", 0); m.put("status", ST_DELIVERED); m.put("hops", hop);
        d.insertWithOnConflict("messages", null, m, SQLiteDatabase.CONFLICT_IGNORE);
        int inc = conv.equals(ChatActivity.active) ? 0 : 1;
        String preview = conv.startsWith("g:") ? fname + ": " + body : body;
        d.execSQL("UPDATE convs SET last_ts=?, last_text=?, unread=unread+? WHERE id=?",
                new Object[]{ts, preview, inc, conv});
    }

    static void addSystem(Context c, String conv, String text) {
        SQLiteDatabase d = db(c);
        long ts = System.currentTimeMillis();
        ContentValues m = new ContentValues();
        m.put("id", UUID.randomUUID().toString()); m.put("conv", conv); m.put("sender", "");
        m.put("sname", ""); m.put("ts", ts); m.put("kind", "sys"); m.put("body", text);
        m.put("outgoing", 0); m.put("status", ST_DELIVERED); m.put("hops", 0);
        d.insert("messages", null, m);
        d.execSQL("UPDATE convs SET last_ts=?, last_text=? WHERE id=?", new Object[]{ts, text, conv});
    }

    // ---------- קבוצות ----------
    static String newGroupId() { return "g:" + UUID.randomUUID().toString().substring(0, 12); }

    private static String buildGdef(Context c, String title, List<String> ids) throws Exception {
        JSONObject b = new JSONObject();
        b.put("title", title);
        b.put("owner", Profile.id(c));
        JSONArray ms = new JSONArray();
        for (String id : ids) {
            ms.put(new JSONObject().put("id", id).put("name", contactName(c, id)).put("color", contactColor(c, id)));
        }
        b.put("members", ms);
        return b.toString();
    }

    /** יצירה או עדכון של קבוצה (רק המנהל). הרשימה כוללת גם את המנהל עצמו. */
    static void updateGroup(Context c, String gid, String title, List<String> ids) {
        try {
            String me = Profile.id(c);
            if (!ids.contains(me)) ids.add(0, me);
            String body = buildGdef(c, title, ids);
            applyGroupDef(c, gid, me, System.currentTimeMillis(), body);
            originate(c, "MSG", "gdef", gid, null, body, false);
        } catch (Exception e) { Db.get(c).log("updateGroup: " + e); }
    }

    /** מחיל הגדרת קבוצה שהתקבלה. מחזיר true אם המשתמש נוסף כעת לקבוצה חדשה. */
    static boolean applyGroupDef(Context c, String gid, String from, long ver, String body) {
        try {
            JSONObject b = new JSONObject(body);
            String title = b.getString("title"), owner = b.getString("owner");
            JSONArray ms = b.getJSONArray("members");
            String me = Profile.id(c);
            boolean in = false;
            JSONArray ids = new JSONArray();
            for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                String id = m.getString("id");
                ids.put(id);
                if (id.equals(me)) in = true;
                else upsertContact(c, id, m.getString("name"), m.optInt("color", 0xFF607D8B), null);
            }
            SQLiteDatabase d = db(c);
            Cursor cu = d.rawQuery("SELECT owner,gver,title FROM convs WHERE id=?", new String[]{gid});
            boolean ex = cu.moveToFirst();
            String eo = ex ? cu.getString(0) : null, et = ex ? cu.getString(2) : null;
            long ev = ex ? cu.getLong(1) : 0;
            cu.close();
            // רק המנהל רשאי ליצור/לעדכן קבוצה
            if (ex) { if (!from.equals(eo) || ver <= ev) return false; }
            else if (!from.equals(owner)) return false;
            if (!in) {                       // הוסרתי מהקבוצה
                if (ex) deleteConv(c, gid);
                return false;
            }
            ContentValues v = new ContentValues();
            v.put("title", title); v.put("owner", owner); v.put("members", ids.toString()); v.put("gver", ver);
            if (ex) d.update("convs", v, "id=?", new String[]{gid});
            else { v.put("id", gid); v.put("type", 1); v.put("created", ver); v.put("last_ts", ver); d.insert("convs", null, v); }
            if (!ex) addSystem(c, gid, "הקבוצה \"" + title + "\" נוצרה");
            else if (!title.equals(et)) addSystem(c, gid, "שם הקבוצה שונה ל-\"" + title + "\"");
            return !ex;
        } catch (Exception e) { Db.get(c).log("applyGroupDef: " + e); return false; }
    }

    static void leaveGroup(Context c, String gid) {
        originate(c, "MSG", "leave", gid, null, "", false);
        deleteConv(c, gid);
    }

    /** המנהל מקבל הודעת עזיבה ומעדכן את הרשימה. */
    static void removeMemberAsOwner(Context c, String gid, String user) {
        if (!owner(c, gid).equals(Profile.id(c))) return;
        List<String> ms = members(c, gid);
        ms.remove(user);
        updateGroup(c, gid, convTitle(c, gid), ms);
    }
}
