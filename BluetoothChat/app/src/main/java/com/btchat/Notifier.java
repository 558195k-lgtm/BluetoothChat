package com.btchat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** התראות. ב-Android 4.4 משתמשים ב-Notification.Builder רגיל; ערוצים נוצרים רק ב-API 26+. */
public class Notifier {
    static final String CH_MSG = "messages", CH_SVC = "service";

    static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH_MSG, "הודעות", NotificationManager.IMPORTANCE_DEFAULT));
            nm.createNotificationChannel(new NotificationChannel(CH_SVC, "שירות רקע", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
    }

    private static int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /** התראת הקדמה קבועה של שירות הרקע. */
    static Notification service(Context c, int connected) {
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), piFlags());
        return builder(c, CH_SVC).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("צ׳אט Bluetooth פעיל")
                .setContentText(connected == 0 ? "אין חיבורים פעילים" : "מחובר ל-" + connected + " מכשירים")
                .setOngoing(true).setContentIntent(pi).build();
    }

    static void message(Context c, String conv, String convTitle, String sender, String body, long ts, boolean group) {
        if (!Profile.notif(c)) return;
        int mode = Profile.contentMode(c);
        String title, text;
        if (mode == 0) { title = convTitle; text = group ? sender + ": " + body : body; }
        else if (mode == 1) { title = convTitle; text = sender + " שלח/ה הודעה"; }
        else { title = "צ׳אט Bluetooth"; text = "הודעה חדשה"; }

        Intent i = new Intent(c, ChatActivity.class).putExtra("conv", conv)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, conv.hashCode(), i, piFlags());
        int defaults = 0;
        if (Profile.sound(c)) defaults |= Notification.DEFAULT_SOUND;
        if (Profile.vibrate(c)) defaults |= Notification.DEFAULT_VIBRATE;
        Notification.Builder b = builder(c, CH_MSG).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text).setWhen(ts).setAutoCancel(true)
                .setContentIntent(pi).setDefaults(defaults).setPriority(Notification.PRIORITY_HIGH)
                .setStyle(new Notification.BigTextStyle().bigText(text));
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(conv.hashCode(), b.build());
    }
}
