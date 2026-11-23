package com.btchat;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** זהות המשתמש והגדרות התראות. נשמר ב-SharedPreferences. */
public class Profile {
    static final int[] COLORS = {0xFFD32F2F, 0xFF1976D2, 0xFF388E3C, 0xFFF57C00,
            0xFF7B1FA2, 0xFF00796B, 0xFF5D4037, 0xFFC2185B};

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("profile", Context.MODE_PRIVATE);
    }
    static boolean isSetup(Context c) { return sp(c).getString("name", "").length() > 0; }

    /** מזהה משתמש קבוע - נוצר פעם אחת ונשמר. */
    static synchronized String id(Context c) {
        SharedPreferences p = sp(c);
        String id = p.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            p.edit().putString("id", id).commit();
        }
        return id;
    }
    static String name(Context c) { return sp(c).getString("name", ""); }
    static int color(Context c) { return sp(c).getInt("color", COLORS[0]); }
    static boolean notif(Context c) { return sp(c).getBoolean("notif", true); }
    static boolean sound(Context c) { return sp(c).getBoolean("sound", true); }
    static boolean vibrate(Context c) { return sp(c).getBoolean("vibrate", true); }
    /** 0 = תוכן מלא, 1 = שם שולח בלבד, 2 = הסתרת תוכן */
    static int contentMode(Context c) { return sp(c).getInt("content", 0); }
}
