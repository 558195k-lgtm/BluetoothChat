package com.btchat;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** איתור מכשירים, התחברות וניתוק. */
public class DevicesActivity extends BaseActivity {
    private BluetoothAdapter bt;
    private final List<BluetoothDevice> devs = new ArrayList<BluetoothDevice>();
    private ArrayAdapter<BluetoothDevice> ad;
    private TextView status;
    private Button scanBtn;

    private final BroadcastReceiver scanRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                add((BluetoothDevice) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) {
                scanBtn.setText("סרוק");
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("מכשירים");
        bt = BluetoothAdapter.getDefaultAdapter();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        scanBtn = new Button(this);
        scanBtn.setText("סרוק");
        scanBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { scan(); } });
        Button disc = new Button(this);
        disc.setText("הפוך לגלוי");
        disc.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)); } });
        Button en = new Button(this);
        en.setText("הפעל BT");
        en.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception e) { toast("אין הרשאה"); } } });
        bar.addView(scanBtn, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(disc, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(en, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ListView lv = new ListView(this);
        ad = new ArrayAdapter<BluetoothDevice>(this, android.R.layout.simple_list_item_2, android.R.id.text1, devs) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                BluetoothDevice d = getItem(pos);
                String uid = BtService.connectedAddrs.get(d.getAddress());
                String n = nameOf(d);
                ((TextView) v.findViewById(android.R.id.text1)).setText(n != null ? n : "(ללא שם)");
                ((TextView) v.findViewById(android.R.id.text2)).setText(d.getAddress()
                        + (uid != null ? "   ● מחובר: " + BtService.connectedNames.get(uid) : ""));
                return v;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                BluetoothDevice d = devs.get(pos);
                final String uid = BtService.connectedAddrs.get(d.getAddress());
                if (uid != null) {
                    new AlertDialog.Builder(DevicesActivity.this).setMessage("לנתק את " + BtService.connectedNames.get(uid) + "?")
                            .setNegativeButton("ביטול", null)
                            .setPositiveButton("נתק", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface x, int w) { BtService.disconnect(DevicesActivity.this, uid); } }).show();
                } else {
                    toast("מתחבר... (ייתכן שתופיע בקשת התאמה - Pair - בשני המכשירים)");
                    BtService.connect(DevicesActivity.this, d.getAddress());
                }
            }
        });
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private String nameOf(BluetoothDevice d) {
        try { return d.getName(); } catch (SecurityException e) { return null; }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void add(BluetoothDevice d) {
        if (d == null) return;
        for (BluetoothDevice x : devs) if (x.getAddress().equals(d.getAddress())) return;
        devs.add(d);
        ad.notifyDataSetChanged();
    }

    private void loadPaired() {
        if (bt == null) return;
        try { for (BluetoothDevice d : bt.getBondedDevices()) add(d); }
        catch (SecurityException e) { toast("חסרה הרשאת Bluetooth - אשר אותה בהגדרות המכשיר"); }
    }

    private void scan() {
        if (bt == null || !bt.isEnabled()) { toast("Bluetooth כבוי"); return; }
        try {
            if (bt.isDiscovering()) { bt.cancelDiscovery(); scanBtn.setText("סרוק"); return; }
            if (bt.startDiscovery()) scanBtn.setText("עצור סריקה");
            else toast("הסריקה נכשלה. בדוק שהרשאת המיקום אושרה (נדרשת לסריקה ב-Android 6+)");
        } catch (SecurityException e) { toast("חסרות הרשאות לסריקה"); }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(scanRx, f);
        loadPaired();
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(scanRx); if (bt != null) bt.cancelDiscovery(); } catch (Exception ignore) { }
    }

    @Override protected void onUpdate() {
        ad.notifyDataSetChanged();
        status.setText(BtService.connectedNames.isEmpty() ? "אין חיבורים פעילים"
                : "מחובר ל: " + TextUtils.join(", ", BtService.connectedNames.values()));
    }
}
