package com.btchat;

import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * שירות הרקע: מאזין לחיבורי Bluetooth Classic (RFCOMM), מנהל חיבורים,
 * מקבל הודעות, מעביר הלאה (flood עם dedupe/TTL/hop) ומפעיל התראות.
 * הפרוטוקול: מסגרת = int אורך (עד 64KB) + JSON ב-UTF-8.
 * סוגי חבילות ב-v1: HELLO, MSG, ACK, PING.
 */
public class BtService extends Service {
    static final UUID APP_UUID = UUID.fromString("8e2a6f10-3c4b-4d5e-9a71-b7c1d2e3f405");
    static final String ACT_KICK = "com.btchat.KICK", ACT_CONNECT = "com.btchat.CONNECT", ACT_DISCONNECT = "com.btchat.DISCONNECT";
    static final String BROADCAST_UPDATE = "com.btchat.UPDATE";

    /** מצב חיבורים לקריאה מה-UI */
    static final Map<String, String> connectedNames = new ConcurrentHashMap<String, String>(); // userId -> name
    static final Map<String, String> connectedAddrs = new ConcurrentHashMap<String, String>(); // MAC -> userId

    private final ConcurrentHashMap<String, Conn> conns = new ConcurrentHashMap<String, Conn>();
    private final List<Conn> allConns = new CopyOnWriteArrayList<Conn>();
    private final Set<String> sentKeys = new HashSet<String>();
    private final Set<String> connecting = new HashSet<String>();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<String, Integer>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private BluetoothAdapter adapter;
    private BluetoothServerSocket server;
    private volatile boolean running;
    private Db db;

    // ---------- פקודות מה-UI ----------
    static void kick(Context c) { start(c, new Intent(c, BtService.class).setAction(ACT_KICK)); }
    static void connect(Context c, String addr) {
        start(c, new Intent(c, BtService.class).setAction(ACT_CONNECT).putExtra("addr", addr));
    }
    static void disconnect(Context c, String userId) {
        start(c, new Intent(c, BtService.class).setAction(ACT_DISCONNECT).putExtra("user", userId));
    }
    private static void start(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { Db.get(c).log("הפעלת שירות נכשלה: " + e); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        db = Db.get(this);
        adapter = BluetoothAdapter.getDefaultAdapter();
        Notifier.ensureChannels(this);
        startForeground(1, Notifier.service(this, 0));
        running = true;
        new Thread(acceptRun, "bt-accept").start();
        handler.postDelayed(tick, 30000);
        db.log("השירות הופעל");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && i.getAction() != null) {
            String a = i.getAction();
            if (a.equals(ACT_CONNECT)) {
                String addr = i.getStringExtra("addr");
                attempts.remove(addr);
                connectAsync(addr, false);
            } else if (a.equals(ACT_DISCONNECT)) {
                Conn c = conns.get(i.getStringExtra("user"));
                if (c != null) { c.userClosed = true; c.close(); }
            }
        }
        work.execute(new Runnable() { public void run() { flush(); } });
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        for (Conn c : allConns) { c.userClosed = true; c.close(); }
        closeServer();
        work.shutdownNow();
        db.log("השירות נעצר");
        super.onDestroy();
    }

    // ---------- האזנה לחיבורים נכנסים ----------
    private final Runnable acceptRun = new Runnable() {
        public void run() {
            while (running) {
                try {
                    if (adapter == null || !adapter.isEnabled()) { Thread.sleep(4000); continue; }
                    if (server == null) server = adapter.listenUsingRfcommWithServiceRecord("BTChat", APP_UUID);
                    BluetoothSocket s = server.accept();
                    handleSocket(s, false);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    closeServer();
                    if (running) {
                        db.log("האזנה: " + e.getMessage());
                        try { Thread.sleep(3000); } catch (InterruptedException x) { return; }
                    }
                }
            }
        }
    };

    private void closeServer() {
        try { if (server != null) server.close(); } catch (IOException ignore) { }
        server = null;
    }

    // ---------- חיבור יוצא ----------
    private void connectAsync(final String addr, final boolean auto) {
        if (addr == null) return;
        synchronized (connecting) {
            if (connecting.contains(addr) || connectedAddrs.containsKey(addr)) return;
            connecting.add(addr);
        }
        new Thread("bt-connect") {
            public void run() {
                BluetoothSocket s = null;
                try {
                    if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth כבוי");
                    adapter.cancelDiscovery();
                    BluetoothDevice d = adapter.getRemoteDevice(addr);
                    s = d.createRfcommSocketToServiceRecord(APP_UUID);
                    s.connect();
                    handleSocket(s, true);
                } catch (Exception e) {
                    try { if (s != null) s.close(); } catch (IOException ignore) { }
                    db.log("חיבור נכשל (" + addr + "): " + e.getMessage());
                    if (auto) handler.post(new Runnable() { public void run() { scheduleReconnect(addr); } });
                    else sendUpdate("החיבור נכשל. ודא שהמכשיר השני מופעל, מותקנת בו האפליקציה, והמכשירים מותאמים (Pair).");
                } finally {
                    synchronized (connecting) { connecting.remove(addr); }
                }
            }
        }.start();
    }

    /** ניסיון התחברות מחדש עם backoff (15 שנ' ... 5 דק'), מקסימום 8 ניסיונות - ללא לולאה אינסופית. */
    private void scheduleReconnect(final String addr) {
        Integer n = attempts.get(addr);
        if (n == null) n = 0;
        if (n >= 8) { db.log("ויתרתי על חיבור מחדש ל-" + addr); return; }
        attempts.put(addr, n + 1);
        long delay = Math.min(15000L << n, 300000L);
        handler.postDelayed(new Runnable() {
            public void run() { if (running && !connectedAddrs.containsKey(addr)) connectAsync(addr, true); }
        }, delay);
    }

    private void handleSocket(BluetoothSocket s, boolean outgoing) {
        Conn c = new Conn(s, outgoing);
        allConns.add(c);
        c.start();
    }

    // ---------- חיבור בודד ----------
    private class Conn {
        final BluetoothSocket sock;
        final boolean outgoing;
        final String addr;
        volatile String peerId, peerName;
        volatile boolean closed, userClosed;
        volatile long lastRx = System.currentTimeMillis();
        DataInputStream in;
        DataOutputStream out;
        final LinkedBlockingQueue<String> q = new LinkedBlockingQueue<String>();

        Conn(BluetoothSocket s, boolean o) {
            sock = s; outgoing = o; addr = s.getRemoteDevice().getAddress();
        }

        void start() {
            try {
                in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
            } catch (IOException e) { close(); return; }
            try {
                q.offer(new JSONObject().put("v", 1).put("t", "HELLO").put("from", Profile.id(BtService.this))
                        .put("fname", Profile.name(BtService.this)).put("fcolor", Profile.color(BtService.this)).toString());
            } catch (JSONException e) { close(); return; }
            new Thread(new Runnable() { public void run() { writeLoop(); } }, "bt-w").start();
            new Thread(new Runnable() { public void run() { readLoop(); } }, "bt-r").start();
        }

        void send(String s) { if (!closed) q.offer(s); }

        void writeLoop() {
            try {
                while (!closed) {
                    String s = q.poll(20, TimeUnit.SECONDS);
                    if (closed) break;
                    if (s == null) s = "{\"v\":1,\"t\":\"PING\"}";   // keep-alive
                    byte[] b = s.getBytes("UTF-8");
                    out.writeInt(b.length);
                    out.write(b);
                    out.flush();
                }
            } catch (Exception ignore) {
            } finally { close(); }
        }

        void readLoop() {
            int bad = 0;
            try {
                while (!closed) {
                    int len = in.readInt();
                    if (len <= 0 || len > 65536) throw new IOException("גודל מסגרת לא תקין: " + len);
                    byte[] b = new byte[len];
                    in.readFully(b);
                    lastRx = System.currentTimeMillis();
                    JSONObject p;
                    try { p = new JSONObject(new String(b, "UTF-8")); bad = 0; }
                    catch (JSONException e) { if (++bad > 5) throw new IOException("מנות פגומות"); continue; }
                    String t = p.optString("t");
                    if (t.equals("PING")) continue;
                    if (t.equals("HELLO")) { if (peerId == null) onHello(this, p); continue; }
                    if (peerId == null) throw new IOException("חבילה לפני HELLO");
                    final JSONObject fp = p;
                    final Conn self = this;
                    work.execute(new Runnable() { public void run() { handlePacket(self, fp); } });
                }
            } catch (Exception e) {
                if (!closed) db.log("חיבור הסתיים: " + e.getMessage());
            } finally { close(); }
        }

        void close() {
            synchronized (this) { if (closed) return; closed = true; }
            try { sock.close(); } catch (IOException ignore) { }
            q.offer("");
            final Conn self = this;
            handler.post(new Runnable() { public void run() { onClosed(self); } });
        }
    }

    // ---------- לחיצת יד ----------
    private void onHello(Conn c, JSONObject p) throws IOException {
        String pid = p.optString("from"), me = Profile.id(this);
        if (pid.length() == 0 || pid.equals(me)) throw new IOException("HELLO לא תקין");
        if (Repo.isBlocked(this, pid)) throw new IOException("מכשיר חסום");
        if (p.optInt("v", 1) > 1) db.log("הצד השני בגרסת פרוטוקול חדשה יותר; ממשיך ב-v1");
        synchronized (conns) {
            Conn ex = conns.get(pid);
            if (ex != null && ex != c) {
                // שני צדדים התחברו במקביל: נשמר החיבור שיזם המשתמש עם המזהה הקטן יותר (שני הצדדים מחשבים אותו דבר)
                boolean preferNew = ex.outgoing == c.outgoing || c.outgoing == (me.compareTo(pid) < 0);
                if (!preferNew) throw new IOException("חיבור כפול");
                ex.userClosed = true;
                ex.close();
            }
            c.peerId = pid;
            c.peerName = p.optString("fname", "?");
            conns.put(pid, c);
        }
        connectedNames.put(pid, c.peerName);
        connectedAddrs.put(c.addr, pid);
        Repo.upsertContact(this, pid, c.peerName, p.optInt("fcolor", 0xFF607D8B), c.addr);
        attempts.remove(c.addr);
        clearSent(pid);
        db.log("מחובר: " + c.peerName);
        handler.post(new Runnable() { public void run() { updateFg(); } });
        sendUpdate(null);
        work.execute(new Runnable() { public void run() { flush(); } });
    }

    private void onClosed(Conn c) {
        allConns.remove(c);
        String pid = c.peerId;
        if (pid != null && conns.remove(pid, c)) {
            connectedNames.remove(pid);
            connectedAddrs.remove(c.addr);
            clearSent(pid);
            db.log("נותק: " + c.peerName);
            if (!c.userClosed && running) scheduleReconnect(c.addr);
        }
        updateFg();
        sendUpdate(null);
    }

    private void clearSent(String pid) {
        synchronized (sentKeys) {
            Iterator<String> it = sentKeys.iterator();
            while (it.hasNext()) if (it.next().endsWith("|" + pid)) it.remove();
        }
    }

    // ---------- עיבוד חבילה נכנסת (על ת'רד העבודה היחיד) ----------
    private void handlePacket(Conn c, JSONObject p) {
        try {
            String t = p.optString("t"), id = p.optString("id"), from = p.optString("from");
            long ts = p.optLong("ts"), now = System.currentTimeMillis();
            int hop = p.optInt("hop"), ttl = Math.min(p.optInt("ttl", Repo.MAX_HOPS), Repo.MAX_HOPS);
            String me = Profile.id(this);
            if (id.length() == 0 || from.length() == 0 || from.equals(me) || now - ts > Repo.TTL_MS || hop < 0) return;
            if (!t.equals("MSG") && !t.equals("ACK")) return;
            if (Repo.isBlocked(this, from)) return;
            if (!db.markSeen(id)) return;                       // כבר התקבלה - מניעת כפילויות ולולאות

            String body = p.optString("body"), fname = p.optString("fname", "?"), kind = p.optString("kind", "text");
            int fcolor = p.optInt("fcolor", 0xFF607D8B);
            String to = p.has("to") ? p.getString("to") : null;
            Repo.upsertContact(this, from, fname, fcolor, null);

            boolean terminal = false;   // true = ההודעה הגיעה ליעדה ואין להעביר הלאה
            if (t.equals("ACK")) {
                if (me.equals(to)) {
                    terminal = true;
                    ContentValues v = new ContentValues();
                    v.put("status", Repo.ST_DELIVERED);
                    Repo.db(this).update("messages", v, "id=? AND outgoing=1", new String[]{body});
                }
            } else if (to != null) {                            // הודעה פרטית
                if (to.equals(me)) {
                    terminal = true;
                    if (kind.equals("text") && body.length() <= Repo.MAX_TEXT) {
                        Repo.ensurePrivate(this, from);
                        String conv = "p:" + from;
                        Repo.storeIncoming(this, id, conv, from, fname, fcolor, ts, kind, body, hop);
                        notifyIncoming(conv, fname, body, ts);
                        Repo.originate(this, "ACK", "ack", null, from, id, false);   // אישור מהנמען הסופי בלבד
                    }
                }
            } else {                                            // הודעת קבוצה / עדכון קבוצה
                String conv = p.optString("conv");
                if (kind.equals("gdef")) {
                    if (Repo.applyGroupDef(this, conv, from, ts, body)) notifyIncoming(conv, fname, "נוספת לקבוצה", ts);
                } else if (Repo.isMember(this, conv, me) && Repo.isMember(this, conv, from)) {
                    if (kind.equals("leave")) {
                        Repo.addSystem(this, conv, fname + " עזב/ה את הקבוצה");
                        Repo.removeMemberAsOwner(this, conv, from);
                    } else if (kind.equals("text") && body.length() <= Repo.MAX_TEXT) {
                        Repo.storeIncoming(this, id, conv, from, fname, fcolor, ts, kind, body, hop);
                        notifyIncoming(conv, fname, body, ts);
                    }
                }
            }

            // Store & Forward: שומרים להעברה עם hop+1 ומוסיפים אותי לנתיב. שולח מקורי לא משתנה.
            if (!terminal && hop + 1 < ttl) {
                p.put("hop", hop + 1);
                JSONArray path = p.optJSONArray("path");
                if (path == null) path = new JSONArray();
                path.put(me);
                p.put("path", path);
                ContentValues r = new ContentValues();
                r.put("id", id); r.put("json", p.toString()); r.put("expires", ts + Repo.TTL_MS);
                Repo.db(this).insertWithOnConflict("relay", null, r, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
            }
            sendUpdate(null);
            flush();
        } catch (Exception e) {
            db.log("שגיאת עיבוד חבילה: " + e);
        }
    }

    private void notifyIncoming(String conv, String sender, String body, long ts) {
        if (conv.equals(ChatActivity.active) || Repo.isMuted(this, conv)) return;
        Notifier.message(this, conv, Repo.convTitle(this, conv), sender, body, ts, conv.startsWith("g:"));
    }

    // ---------- שליחה/העברה ----------
    private boolean inPath(JSONArray path, String id) {
        if (path == null) return false;
        for (int i = 0; i < path.length(); i++) if (id.equals(path.optString(i))) return true;
        return false;
    }

    /** עובר על תור ההעברה ושולח לכל חיבור פעיל שעוד לא קיבל (ושלא ברשימת הנתיב). */
    private void flush() {
        try {
            String me = Profile.id(this);
            List<String> jsons = new ArrayList<String>();
            Cursor cu = Repo.db(this).rawQuery("SELECT json FROM relay WHERE expires>? ORDER BY rowid",
                    new String[]{String.valueOf(System.currentTimeMillis())});
            try { while (cu.moveToNext()) jsons.add(cu.getString(0)); } finally { cu.close(); }
            boolean changed = false;
            for (String js : jsons) {
                JSONObject p = new JSONObject(js);
                String id = p.getString("id"), from = p.optString("from");
                String to = p.has("to") ? p.getString("to") : null;
                JSONArray path = p.optJSONArray("path");
                Conn direct = to != null ? conns.get(to) : null;   // אם היעד מחובר ישירות - שולחים רק אליו
                for (Conn c : conns.values()) {
                    String pid = c.peerId;
                    if (pid == null || c.closed || inPath(path, pid)) continue;
                    if (direct != null && c != direct) continue;
                    synchronized (sentKeys) { if (!sentKeys.add(id + "|" + pid)) continue; }
                    c.send(js);
                    if (from.equals(me) && p.optString("t").equals("MSG") && p.optString("kind").equals("text")) {
                        boolean dir = to == null || to.equals(pid);
                        if (dir) Repo.db(this).execSQL("UPDATE messages SET status=1 WHERE id=? AND status IN (0,4)", new Object[]{id});
                        else Repo.db(this).execSQL("UPDATE messages SET status=4 WHERE id=? AND status=0", new Object[]{id});
                        changed = true;
                    }
                }
            }
            if (changed) sendUpdate(null);
        } catch (Exception e) { db.log("flush: " + e); }
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            work.execute(new Runnable() {
                public void run() {
                    try {
                        long now = System.currentTimeMillis();
                        Repo.db(BtService.this).execSQL("DELETE FROM relay WHERE expires<=?", new Object[]{now});
                        Repo.db(BtService.this).execSQL("DELETE FROM seen WHERE ts<?", new Object[]{now - Repo.TTL_MS - 3600000L});
                        Repo.db(BtService.this).execSQL("UPDATE messages SET status=3 WHERE outgoing=1 AND status IN (0,4) AND ts<?",
                                new Object[]{now - Repo.TTL_MS});
                        flush();
                    } catch (Exception e) { db.log("housekeeping: " + e); }
                }
            });
            long now = System.currentTimeMillis();
            for (Conn c : allConns) if (now - c.lastRx > 70000) c.close();   // חיבור "מת"
            handler.postDelayed(this, 30000);
        }
    };

    private void updateFg() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, Notifier.service(this, conns.size()));
    }

    private void sendUpdate(String msg) {
        Intent i = new Intent(BROADCAST_UPDATE).setPackage(getPackageName());
        if (msg != null) i.putExtra("msg", msg);
        sendBroadcast(i);
    }
}
