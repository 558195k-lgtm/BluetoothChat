package com.btchat;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** בסיס לכל המסכים: RTL, קבלת עדכונים מהשירות, כלי עזר. */
public abstract class BaseActivity extends Activity {
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String m = i.getStringExtra("msg");
            if (m != null) Toast.makeText(BaseActivity.this, m, Toast.LENGTH_LONG).show();
            onUpdate();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(rx, new IntentFilter(BtService.BROADCAST_UPDATE));
        onUpdate();
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(rx); } catch (Exception ignore) { }
    }

    /** נקרא בכל שינוי נתונים/חיבור. */
    protected void onUpdate() { }

    @Override public void setContentView(View v) {
        super.setContentView(v);
        if (Build.VERSION.SDK_INT >= 17) getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static String fmt(long ts) {
        Date d = new Date(ts), now = new Date();
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        boolean today = day.format(d).equals(day.format(now));
        return new SimpleDateFormat(today ? "HH:mm" : "dd/MM HH:mm", Locale.getDefault()).format(d);
    }

    static String letter(String s) { return s == null || s.length() == 0 ? "?" : s.substring(0, 1); }
}
