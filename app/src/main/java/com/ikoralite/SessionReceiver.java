package com.ikoralite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;

/** A player said it opened or closed an audio session. */
public class SessionReceiver extends BroadcastReceiver {

    /**
     * ikora's own test broadcast (see {@link #sendSelfTest}). An action of its own, so no
     * other equalizer receives a session-less OPEN from us.
     */
    static final String SELF_TEST = "com.ikoralite.SELF_TEST";

    /**
     * Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND (hidden). YT Music sends its session broadcasts
     * with it, which is how an implicit broadcast still reaches manifest receivers.
     */
    private static final int INCLUDE_BACKGROUND = 0x01000000;

    /** True for the runtime-registered copy in {@link IkoraApp}; false for the manifest one. */
    boolean runtime;

    @Override
    public void onReceive(Context c, Intent i) {
        String via = runtime ? "（動的）" : "（登録）";
        if (SELF_TEST.equals(i.getAction())) {
            Diag.selfTestArrived(c, runtime);
            Diag.note(c, "受信テストの知らせが届いた" + via);
            return;
        }
        int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        String act = i.getAction() == null ? "" : i.getAction().replace("android.media.action.", "");
        Diag.note(c, "受信" + via + " " + act + " session=" + session + " pkg=" + pkg);
        if (session <= 0) return;
        Diag.received(c);

        // Both receivers hear the same broadcast; open and close are idempotent.
        if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
            Eq.open(c, session, pkg);
            EqService.sync(c);
        } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
            Eq.close(session);
            EqService.sync(c);
        }
    }

    /** Send ourselves a broadcast delivered the way a player's is: implicit, same flags. */
    static void sendSelfTest(Context c) {
        Diag.selfTestStart(c);
        Intent i = new Intent(SELF_TEST)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | INCLUDE_BACKGROUND);
        c.sendBroadcast(i);
    }
}
