package com.btchat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** מסך שיחה (פרטית או קבוצתית). */
public class ChatActivity extends BaseActivity {
    /** השיחה הפתוחה כרגע - למניעת התראה כפולה */
    static volatile String active;

    static class Msg { String id, sender, sname, kind, body; int scolor, status; long ts; boolean out; }

    private String conv;
    private boolean group;
    private ListView lv;
    private EditText input;
    private BaseAdapter ad;
    private final List<Msg> msgs = new ArrayList<Msg>();
    private String filter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        conv = getIntent().getStringExtra("conv");
        if (conv == null || !Repo.exists(this, conv)) { finish(); return; }
        group = conv.startsWith("g:");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        lv = new ListView(this);
        lv.setDivider(null);
        lv.setBackgroundColor(0xFFECE5DD);
        lv.setDrawSelectorOnTop(true);
        lv.setSelector(new ColorDrawable(0x22000000));    // סימון Focus ברור לניווט במקלדת
        ad = new MsgAdapter();
        lv.setAdapter(ad);
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) { msgMenu(msgs.get(pos)); return true; }
        });
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("הקלד הודעה");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                if (action == EditorInfo.IME_ACTION_SEND
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                    send();
                    return true;
                }
                return false;
            }
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button sb = new Button(this);
        sb.setText("שלח");
        sb.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { send(); } });
        bar.addView(sb, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String c = i.getStringExtra("conv");
        if (c != null && Repo.exists(this, c)) { conv = c; group = c.startsWith("g:"); filter = null; active = conv; Repo.markRead(this, conv); onUpdate(); }
    }

    @Override protected void onResume() {
        super.onResume();
        active = conv;
        if (conv != null) Repo.markRead(this, conv);
    }

    @Override protected void onPause() { super.onPause(); active = null; }

    private void send() {
        String t = input.getText().toString();
        if (t.trim().length() == 0) return;
        Repo.sendText(this, conv, t);
        input.setText("");
        onUpdate();
    }

    @Override protected void onUpdate() {
        if (conv == null) return;
        if (!Repo.exists(this, conv)) { finish(); return; }      // הוסרתי מהקבוצה / השיחה נמחקה
        setTitle(Repo.convTitle(this, conv));
        if (getActionBar() != null) {
            String sub;
            if (group) sub = Repo.members(this, conv).size() + " חברים";
            else sub = BtService.connectedNames.containsKey(conv.substring(2)) ? "מחובר" : "לא מחובר";
            getActionBar().setSubtitle(sub);
        }
        msgs.clear();
        String sql = "SELECT id,sender,sname,scolor,ts,kind,body,outgoing,status FROM messages WHERE conv=?"
                + (filter != null ? " AND body LIKE ?" : "") + " ORDER BY ts ASC";
        String[] args = filter != null ? new String[]{conv, "%" + filter + "%"} : new String[]{conv};
        Cursor cu = Repo.db(this).rawQuery(sql, args);
        while (cu.moveToNext()) {
            Msg m = new Msg();
            m.id = cu.getString(0); m.sender = cu.getString(1); m.sname = cu.getString(2); m.scolor = cu.getInt(3);
            m.ts = cu.getLong(4); m.kind = cu.getString(5); m.body = cu.getString(6);
            m.out = cu.getInt(7) == 1; m.status = cu.getInt(8);
            msgs.add(m);
        }
        cu.close();
        boolean atEnd = lv.getLastVisiblePosition() >= ad.getCount() - 2;
        ad.notifyDataSetChanged();
        if (atEnd || msgs.size() < 2) lv.setSelection(Math.max(0, msgs.size() - 1));
        Repo.markRead(this, conv);
    }

    private String statusLabel(Msg m) {
        switch (m.status) {
            case Repo.ST_WAIT: return "ממתינה לחיבור";
            case Repo.ST_SENT: return "✓ נשלחה למכשיר מחובר";
            case Repo.ST_RELAYED: return "✓ הועברה דרך מכשיר מתווך";
            case Repo.ST_DELIVERED: return "✓✓ התקבלה אצל הנמען";
            default: return "✗ נכשלה";
        }
    }

    private class MsgAdapter extends BaseAdapter {
        public int getCount() { return msgs.size(); }
        public Object getItem(int i) { return msgs.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            Context c = ChatActivity.this;
            Msg m = msgs.get(i);
            LinearLayout wrap = new LinearLayout(c);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
            if ("sys".equals(m.kind)) {
                TextView t = new TextView(c);
                t.setText(m.body); t.setGravity(Gravity.CENTER); t.setTextColor(0xFF555555); t.setTextSize(12);
                t.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
                wrap.addView(t, new LinearLayout.LayoutParams(-1, -2));
                return wrap;
            }
            LinearLayout bubble = new LinearLayout(c);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 4));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(c, 12));
            g.setColor(m.out ? 0xFFDCF8C6 : 0xFFFFFFFF);
            g.setStroke(1, 0xFFCCCCCC);
            bubble.setBackground(g);

            Msg prev = i > 0 ? msgs.get(i - 1) : null;
            boolean sameAsPrev = prev != null && !m.out && !prev.out && !"sys".equals(prev.kind) && m.sender.equals(prev.sender);
            if (group && !m.out && !sameAsPrev) {
                // שם השולח המקורי + אות ראשונה, בצבע אישי קבוע
                TextView n = new TextView(c);
                n.setText("(" + letter(m.sname) + ") " + m.sname);
                n.setTextColor(m.scolor); n.setTypeface(Typeface.DEFAULT_BOLD); n.setTextSize(13);
                bubble.addView(n);
            }
            TextView body = new TextView(c);
            body.setText(m.body); body.setTextColor(0xFF000000); body.setTextSize(16);
            body.setMaxWidth(dp(c, 280));
            bubble.addView(body);
            TextView foot = new TextView(c);
            foot.setText(fmt(m.ts) + (m.out ? "  " + statusLabel(m) : ""));
            foot.setTextSize(11); foot.setTextColor(m.status == Repo.ST_FAILED ? 0xFFC62828 : 0xFF777777);
            bubble.addView(foot);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            // המסך מכוון RTL: START = ימין. יוצא בצד ימין, נכנס בצד שמאל
            lp.gravity = m.out ? Gravity.START : Gravity.END;
            wrap.addView(bubble, lp);
            return wrap;
        }
    }

    private void msgMenu(final Msg m) {
        if ("sys".equals(m.kind)) return;
        new AlertDialog.Builder(this).setItems(new String[]{"העתק טקסט", "מחק הודעה (מקומית)"}, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("msg", m.body));
                } else { Repo.deleteMessage(ChatActivity.this, m.id); onUpdate(); }
            }
        }).show();
    }

    // ---------- תפריט ----------
    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "חיפוש בהודעות");
        m.add(0, 2, 0, "השתק / בטל השתקה");
        if (group) {
            m.add(0, 3, 0, "פרטי קבוצה");
            if (Repo.owner(this, conv).equals(Profile.id(this))) {
                m.add(0, 4, 0, "שנה שם קבוצה");
                m.add(0, 5, 0, "הוסף חברים");
                m.add(0, 6, 0, "הסר חברים");
            } else m.add(0, 7, 0, "יציאה מהקבוצה");
        }
        m.add(0, 8, 0, "מחק שיחה (מקומית)");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case 1: searchDialog(); break;
            case 2: Repo.toggleMute(this, conv);
                android.widget.Toast.makeText(this, Repo.isMuted(this, conv) ? "השיחה מושתקת" : "ההשתקה בוטלה", android.widget.Toast.LENGTH_SHORT).show(); break;
            case 3: infoDialog(); break;
            case 4: renameDialog(); break;
            case 5: addDialog(); break;
            case 6: removeDialog(); break;
            case 7: confirm("לצאת מהקבוצה?", new Runnable() { public void run() { Repo.leaveGroup(ChatActivity.this, conv); finish(); } }); break;
            case 8: confirm("למחוק את השיחה מהמכשיר?", new Runnable() { public void run() { Repo.deleteConv(ChatActivity.this, conv); finish(); } }); break;
        }
        return true;
    }

    private void confirm(String q, final Runnable ok) {
        new AlertDialog.Builder(this).setMessage(q).setNegativeButton("ביטול", null)
                .setPositiveButton("אישור", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { ok.run(); } }).show();
    }

    private void searchDialog() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(filter == null ? "" : filter);
        new AlertDialog.Builder(this).setTitle("חיפוש (ריק = ביטול)").setView(e).setNegativeButton("ביטול", null)
                .setPositiveButton("חפש", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        filter = s.length() == 0 ? null : s;
                        onUpdate();
                    }
                }).show();
    }

    private void infoDialog() {
        StringBuilder sb = new StringBuilder();
        String owner = Repo.owner(this, conv);
        for (String id : Repo.members(this, conv)) {
            sb.append(Repo.contactName(this, id));
            if (id.equals(owner)) sb.append(" (מנהל)");
            if (id.equals(Profile.id(this))) sb.append(" (אני)");
            else if (BtService.connectedNames.containsKey(id)) sb.append("  ● מחובר ישירות");
            sb.append("\n");
        }
        new AlertDialog.Builder(this).setTitle(Repo.convTitle(this, conv)).setMessage(sb.toString()).setPositiveButton("סגור", null).show();
    }

    private void renameDialog() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(Repo.convTitle(this, conv));
        new AlertDialog.Builder(this).setTitle("שם קבוצה").setView(e).setNegativeButton("ביטול", null)
                .setPositiveButton("שמור", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        if (s.length() > 0) Repo.updateGroup(ChatActivity.this, conv, s, Repo.members(ChatActivity.this, conv));
                        onUpdate();
                    }
                }).show();
    }

    private void addDialog() {
        final List<String> ids = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        List<String> cur = Repo.members(this, conv);
        for (String[] c : Repo.contacts(this)) if (!cur.contains(c[0])) { ids.add(c[0]); names.add(c[1]); }
        if (ids.isEmpty()) { android.widget.Toast.makeText(this, "אין אנשי קשר נוספים להוספה", android.widget.Toast.LENGTH_LONG).show(); return; }
        pick("הוסף חברים", ids, names, new PickCb() { public void done(List<String> sel) {
            List<String> all = Repo.members(ChatActivity.this, conv); all.addAll(sel);
            Repo.updateGroup(ChatActivity.this, conv, Repo.convTitle(ChatActivity.this, conv), all); onUpdate(); } });
    }

    private void removeDialog() {
        final List<String> ids = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        for (String id : Repo.members(this, conv)) if (!id.equals(Profile.id(this))) { ids.add(id); names.add(Repo.contactName(this, id)); }
        if (ids.isEmpty()) return;
        pick("הסר חברים", ids, names, new PickCb() { public void done(List<String> sel) {
            List<String> all = Repo.members(ChatActivity.this, conv); all.removeAll(sel);
            Repo.updateGroup(ChatActivity.this, conv, Repo.convTitle(ChatActivity.this, conv), all); onUpdate(); } });
    }

    interface PickCb { void done(List<String> selected); }

    private void pick(String title, final List<String> ids, List<String> names, final PickCb cb) {
        final boolean[] on = new boolean[ids.size()];
        new AlertDialog.Builder(this).setTitle(title)
                .setMultiChoiceItems(names.toArray(new String[0]), on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) { on[w] = c; } })
                .setNegativeButton("ביטול", null)
                .setPositiveButton("אישור", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<String> sel = new ArrayList<String>();
                        for (int i = 0; i < on.length; i++) if (on[i]) sel.add(ids.get(i));
                        if (!sel.isEmpty()) cb.done(sel);
                    } }).show();
    }
}
