package com.ikoralite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/** A player said it opened or closed an audio session. */
public class SessionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        String act = i.getAction() == null ? "" : i.getAction().replace("android.media.action.", "");
        Diag.note(c, "受信 " + act + " session=" + session + " pkg=" + pkg);
        if (session <= 0) return;
        Diag.received(c);

        if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
            Eq.open(c, session, pkg);
            EqService.sync(c);
        } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
            Eq.close(session);
            EqService.sync(c);
        }
    }
}
