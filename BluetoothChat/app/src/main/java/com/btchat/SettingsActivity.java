package com.btchat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** הגדרת פרופיל (בהפעלה ראשונה) והגדרות התראות. */
public class SettingsActivity extends BaseActivity {
    private EditText name;
    private int color;
    private final Button[] colorBtns = new Button[Profile.COLORS.length];
    private CheckBox cbNotif, cbSound, cbVib;
    private RadioGroup rg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(getIntent().getBooleanExtra("first", false) ? "הגדרת פרופיל" : "הגדרות");
        color = Profile.color(this);
        int pad = dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("שם משתמש"));
        name = new EditText(this);
        name.setSingleLine(true);
        name.setText(Profile.name(this));
        root.addView(name);

        root.addView(label("צבע אישי (מופיע בהודעות הקבוצה)"));
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < Profile.COLORS.length; i++) {
            final int ci = i;
            Button cb = new Button(this);
            cb.setBackgroundColor(Profile.COLORS[i]);
            cb.setTextColor(0xFFFFFFFF);
            cb.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { color = Profile.COLORS[ci]; refreshColors(); } });
            colorBtns[i] = cb;
            row.addView(cb, new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        }
        root.addView(row);
        refreshColors();

        root.addView(label("התראות"));
        cbNotif = check("הצג התראות על הודעות", Profile.notif(this));
        cbSound = check("צליל", Profile.sound(this));
        cbVib = check("רטט", Profile.vibrate(this));
        root.addView(cbNotif); root.addView(cbSound); root.addView(cbVib);

        rg = new RadioGroup(this);
        String[] opts = {"הצג תוכן מלא", "הצג שם שולח בלבד", "הסתר תוכן ההודעה"};
        for (int i = 0; i < opts.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(opts[i]);
            r.setId(100 + i);
            rg.addView(r);
        }
        rg.check(100 + Profile.contentMode(this));
        root.addView(rg);

        Button save = new Button(this);
        save.setText("שמור");
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { save(); } });
        root.addView(save);

        Button log = new Button(this);
        log.setText("יומן אבחון");
        log.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, LogActivity.class)); } });
        root.addView(log);

        TextView id = new TextView(this);
        id.setText("מזהה משתמש: " + Profile.id(this));
        id.setTextSize(11);
        root.addView(id);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, dp(this, 12), 0, dp(this, 4));
        return t;
    }

    private CheckBox check(String s, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(v);
        return c;
    }

    private void refreshColors() {
        for (int i = 0; i < colorBtns.length; i++) colorBtns[i].setText(Profile.COLORS[i] == color ? "✓" : "");
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.length() == 0) { Toast.makeText(this, "נא להזין שם", Toast.LENGTH_SHORT).show(); return; }
        Profile.id(this);   // יצירת מזהה קבוע אם עדיין לא קיים
        SharedPreferences.Editor e = Profile.sp(this).edit();
        e.putString("name", n).putInt("color", color)
                .putBoolean("notif", cbNotif.isChecked()).putBoolean("sound", cbSound.isChecked())
                .putBoolean("vibrate", cbVib.isChecked()).putInt("content", rg.getCheckedRadioButtonId() - 100);
        e.commit();
        BtService.kick(this);
        setResult(RESULT_OK);
        finish();
    }
}
