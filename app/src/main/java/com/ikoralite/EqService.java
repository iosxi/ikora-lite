package com.ikoralite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Does nothing but stay in the foreground while an effect is attached. Without it the
 * idle process may be killed, and the effect dies with it. Stops itself when idle.
 */
public class EqService extends Service {

    /** Start the service if effects are attached, stop it if none are. */
    static void sync(Context c) {
        Intent i = new Intent(c, EqService.class);
        if (!Eq.needsService(c)) {
            c.stopService(i);
            return;
        }
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            // Android 12+ may refuse a start from the background; the effect still works
            // for as long as the process happens to live.
            Diag.note(c, "常駐サービスを起動できない（電池の最適化のため）: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                "run", getString(R.string.channel), NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "run")
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(getString(R.string.running))
                .setContentIntent(open)
                .build();
        startForeground(1, n);
        // Restarted by the system or after boot: the effect died with the old process.
        if (Eq.isGlobal(this)) Eq.attachMissing(this);
        if (!Eq.needsService(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Resident and whole-output modes have no broadcast to bring them back: ask to be restarted.
        return Eq.isOn(this) && (Eq.isGlobal(this) || Eq.isResident(this)) ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
