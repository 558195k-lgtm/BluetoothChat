package com.btchat;

import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** יומן אבחון מקומי. */
public class LogActivity extends BaseActivity {
    private TextView tv;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("יומן אבחון");
        tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setPadding(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    @Override protected void onUpdate() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());
        Cursor c = Repo.db(this).rawQuery("SELECT ts,line FROM log ORDER BY _id DESC LIMIT 300", null);
        while (c.moveToNext()) sb.append(f.format(new Date(c.getLong(0)))).append("  ").append(c.getString(1)).append("\n");
        c.close();
        tv.setText(sb.length() == 0 ? "(ריק)" : sb.toString());
    }

    @Override public boolean onCreateOptionsMenu(Menu m) { m.add(0, 1, 0, "נקה יומן"); return true; }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        Repo.db(this).execSQL("DELETE FROM log");
        onUpdate();
        return true;
    }
}
