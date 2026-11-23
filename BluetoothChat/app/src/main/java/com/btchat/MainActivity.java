package com.btchat;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** מסך ראשי: רשימת שיחות. */
public class MainActivity extends BaseActivity {
    static class Conv { String id, title, last; int type, unread, color; long ts; }

    private ListView list;
    private TextView status, empty;
    private final List<Conv> convs = new ArrayList<Conv>();
    private BaseAdapter ad;
    private boolean btAsked;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        status = new TextView(this);
        status.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        status.setBackgroundColor(0xFFE0E0E0);
        status.setTextColor(0xFF000000);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.addView(btn("שיחה חדשה", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(MainActivity.this, ContactsActivity.class).putExtra("mode", "private")); } }), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(btn("קבוצה חדשה", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(MainActivity.this, ContactsActivity.class).putExtra("mode", "group_new")); } }), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(btn("מכשירים", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(MainActivity.this, DevicesActivity.class)); } }), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(btn("הגדרות", new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class)); } }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        empty = new TextView(this);
        empty.setText("אין שיחות עדיין.\nפתח 'מכשירים', התחבר למכשיר אחר, ואז התחל 'שיחה חדשה'.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(this, 24), dp(this, 48), dp(this, 24), 0);
        root.addView(empty, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        ad = new ConvAdapter();
        list.setAdapter(ad);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                startActivity(new Intent(MainActivity.this, ChatActivity.class).putExtra("conv", convs.get(pos).id));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                final Conv c = convs.get(pos);
                new AlertDialog.Builder(MainActivity.this).setTitle(c.title)
                        .setItems(new String[]{"מחק שיחה (מקומית)"}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { Repo.deleteConv(MainActivity.this, c.id); onUpdate(); }
                        }).show();
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        askPerms();
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setOnClickListener(l);
        return b;
    }

    private void askPerms() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<String>();
        List<String> all = new ArrayList<String>();
        all.add(Manifest.permission.ACCESS_FINE_LOCATION);            // נדרש לסריקת Bluetooth ב-Android 6+
        if (Build.VERSION.SDK_INT >= 31) { all.add("android.permission.BLUETOOTH_CONNECT"); all.add("android.permission.BLUETOOTH_SCAN"); }
        if (Build.VERSION.SDK_INT >= 33) all.add("android.permission.POST_NOTIFICATIONS");
        for (String p : all) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        BtService.kick(this);
    }

    @Override protected void onResume() {
        if (!Profile.isSetup(this)) {
            startActivityForResult(new Intent(this, SettingsActivity.class).putExtra("first", true), 7);
        } else {
            BtService.kick(this);
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (!btAsked && a != null && !a.isEnabled()) {
                btAsked = true;
                try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 8); } catch (Exception ignore) { }
            }
        }
        super.onResume();
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == 7 && !Profile.isSetup(this)) finish();
    }

    @Override protected void onUpdate() {
        convs.clear();
        Cursor cu = Repo.db(this).rawQuery("SELECT id,type,title,last_text,last_ts,unread FROM convs ORDER BY last_ts DESC", null);
        while (cu.moveToNext()) {
            Conv c = new Conv();
            c.id = cu.getString(0); c.type = cu.getInt(1); c.title = cu.getString(2);
            c.last = cu.getString(3); c.ts = cu.getLong(4); c.unread = cu.getInt(5);
            c.color = c.type == 0 ? Repo.contactColor(this, c.id.substring(2))
                    : Profile.COLORS[Math.abs(c.id.hashCode()) % Profile.COLORS.length];
            convs.add(c);
        }
        cu.close();
        ad.notifyDataSetChanged();
        empty.setVisibility(convs.isEmpty() ? View.VISIBLE : View.GONE);
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) status.setText("במכשיר אין Bluetooth");
        else if (!a.isEnabled()) status.setText("Bluetooth כבוי");
        else if (BtService.connectedNames.isEmpty()) status.setText("אין חיבורים פעילים");
        else status.setText("מחובר ל: " + TextUtils.join(", ", BtService.connectedNames.values()));
    }

    private class ConvAdapter extends BaseAdapter {
        public int getCount() { return convs.size(); }
        public Object getItem(int i) { return convs.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View cv, ViewGroup parent) {
            Conv c = convs.get(i);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(MainActivity.this, 12), dp(MainActivity.this, 8), dp(MainActivity.this, 12), dp(MainActivity.this, 8));

            TextView av = new TextView(MainActivity.this);
            av.setText(letter(c.title));
            av.setTextColor(0xFFFFFFFF); av.setTextSize(20); av.setGravity(Gravity.CENTER);
            av.setBackground(circle(c.color));
            row.addView(av, new LinearLayout.LayoutParams(dp(MainActivity.this, 46), dp(MainActivity.this, 46)));

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(MainActivity.this, 12), 0, dp(MainActivity.this, 12), 0);
            TextView t = new TextView(MainActivity.this);
            t.setText((c.type == 1 ? "[קבוצה] " : "") + c.title);
            t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextSize(16); t.setTextColor(0xFF000000); t.setSingleLine();
            t.setEllipsize(TextUtils.TruncateAt.END);
            TextView l = new TextView(MainActivity.this);
            l.setText(c.last == null ? "" : c.last); l.setTextColor(0xFF666666); l.setSingleLine();
            l.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(t); mid.addView(l);
            row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout right = new LinearLayout(MainActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView tm = new TextView(MainActivity.this);
            tm.setText(c.ts > 0 ? fmt(c.ts) : ""); tm.setTextSize(11); tm.setTextColor(0xFF888888);
            right.addView(tm);
            if (c.unread > 0) {
                TextView u = new TextView(MainActivity.this);
                u.setText(String.valueOf(c.unread)); u.setTextColor(0xFFFFFFFF); u.setTextSize(12);
                u.setGravity(Gravity.CENTER); u.setBackground(circle(0xFF25A244));
                right.addView(u, new LinearLayout.LayoutParams(dp(MainActivity.this, 22), dp(MainActivity.this, 22)));
            }
            row.addView(right);
            return row;
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "יומן אבחון");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == 1) startActivity(new Intent(this, LogActivity.class));
        return true;
    }
}
