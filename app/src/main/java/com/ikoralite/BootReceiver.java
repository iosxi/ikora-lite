package com.ikoralite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Resident and whole-output modes have no player broadcast to wake them, so bring the
 * service back after a reboot or an update. Both are exempt from the background foreground-service start ban.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Eq.isOn(c) || !(Eq.isGlobal(c) || Eq.isResident(c))) return;
        Diag.note(c, "起動・更新を受けて常駐を再開: " + i.getAction());
        try {
            c.startForegroundService(new Intent(c, EqService.class));
        } catch (RuntimeException e) {
            Diag.note(c, "常駐の再開に失敗: " + e);
        }
    }
}
