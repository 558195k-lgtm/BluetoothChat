package com.btchat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** מפעיל את שירות הרקע לאחר אתחול המכשיר (אם הפרופיל הוגדר). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Profile.isSetup(c)) BtService.kick(c);
    }
}
