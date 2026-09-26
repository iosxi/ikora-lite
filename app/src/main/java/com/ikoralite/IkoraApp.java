package com.ikoralite;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.os.Build;

/**
 * Registers a second, runtime receiver for the same broadcasts while the process lives.
 * The manifest receiver is the one that matters; this one exists to tell, on a device
 * where nothing arrives, whether the device blocks manifest receivers of background apps
 * (the runtime one would still hear it) or the player never sends at all (neither hears).
 */
public class IkoraApp extends Application {
    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // before Android 13 there is no such flag
    public void onCreate() {
        super.onCreate();
        Diag.note(this, "プロセス起動");
        // Before restoring: effects are then created with the current output's settings.
        Outputs.check(this);
        Outputs.watch(this);
        Eq.restore(this);
        // May be refused from the background (then the effect lives as long as the process).
        if (!Eq.effects.isEmpty()) EqService.sync(this);
        IntentFilter f = new IntentFilter();
        f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        f.addAction(SessionReceiver.SELF_TEST);
        SessionReceiver r = new SessionReceiver();
        r.runtime = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(r, f);
        }
    }
}
