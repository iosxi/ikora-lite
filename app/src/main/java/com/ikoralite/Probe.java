package com.ikoralite;

import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Virtualizer;

import java.util.UUID;

/**
 * Finds other apps' effects on a session without any permission: attach one effect of each
 * kind at the lowest priority and release it at once. Effects of one kind share a single
 * engine per session, so a handle that does not get control, or that comes up already
 * enabled, has joined someone else's. The owner cannot be known this way, only the kind.
 *
 * Attaching briefly touches the live chain, so this runs only when asked, never on a timer.
 */
final class Probe {
    private Probe() {}

    private interface Maker {
        AudioEffect make(int session);
    }

    static final String DYNAMICS = "DynamicsProcessing（EQ・音量など）";

    private static final Object[][] KINDS = {
            {AudioEffect.EFFECT_TYPE_EQUALIZER, "イコライザ",
                    (Maker) s -> new Equalizer(Integer.MIN_VALUE, s)},
            {AudioEffect.EFFECT_TYPE_BASS_BOOST, "低音強調",
                    (Maker) s -> new BassBoost(Integer.MIN_VALUE, s)},
            {AudioEffect.EFFECT_TYPE_VIRTUALIZER, "バーチャライザ",
                    (Maker) s -> new Virtualizer(Integer.MIN_VALUE, s)},
            {AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER, "音量強調",
                    (Maker) LoudnessEnhancer::new},
            {AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING, DYNAMICS, null},
    };

    /**
     * One line per kind found on the session, "" if none.
     * @param ours    ikora has its own DynamicsProcessing on this session (a probe would
     *                only join it, so its control status is reported instead)
     * @param working ikora's own effect there holds control
     */
    static String run(int session, boolean ours, boolean working) {
        boolean[] present = presentKinds();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < KINDS.length; k++) {
            if (!present[k]) continue;
            String name = (String) KINDS[k][1];
            Maker maker = (Maker) KINDS[k][2];
            if (maker == null) {
                if (ours) {
                    if (!working) line(sb, name, "あり（ほかのアプリが優先。ikora は制御権なし）");
                    continue;
                }
                // A config cannot be set without control, so construction itself fails
                // when another app's DynamicsProcessing is already there.
                try {
                    new DynamicsProcessing(Integer.MIN_VALUE, session, null).release();
                } catch (RuntimeException e) {
                    line(sb, name, "あり（ほかのアプリが保持）");
                }
                continue;
            }
            AudioEffect fx = null;
            try {
                fx = maker.make(session);
                boolean enabled = fx.getEnabled();
                // Our fresh probe is disabled; an enabled one, or one we do not control,
                // belongs to someone else (or to the system, with no app holding it).
                if (!fx.hasControl() || enabled) line(sb, name, enabled ? "あり・有効" : "あり・無効");
            } catch (RuntimeException e) {
                line(sb, name, "調べられず（" + e.getMessage() + "）");
            } finally {
                if (fx != null) fx.release();
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, String what) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(name).append(": ").append(what);
    }

    private static boolean[] presentKinds() {
        boolean[] p = new boolean[KINDS.length];
        for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
            for (int k = 0; k < KINDS.length; k++) {
                if (((UUID) KINDS[k][0]).equals(d.type)) p[k] = true;
            }
        }
        return p;
    }
}
