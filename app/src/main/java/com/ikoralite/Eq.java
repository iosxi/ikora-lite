package com.ikoralite;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whole equalizer: settings, and one effect per audio session a player has opened.
 * The sound itself is processed by the platform effect engine inside audioserver;
 * this process only holds the handle, so it does no audio work of its own.
 */
final class Eq {
    static final String TAG = "ikora";

    /** Band centres in Hz. Gains are stored in half-dB steps, -24..+24 (±12 dB). */
    static final int[] FREQ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final int N = FREQ.length;
    static final int STEPS = 24;

    /** Open sessions → the package that opened them. Kept even while the EQ is off. */
    static final Map<Integer, String> sessions = new LinkedHashMap<>();
    /** Open sessions → the effect attached to them. Empty while the EQ is off. */
    static final Map<Integer, AudioEffect> effects = new LinkedHashMap<>();

    /** Called whenever sessions or effects change, so a visible screen can refresh. */
    static Runnable listener;

    private Eq() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("eq", Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean("on", true);
    }

    static int step(Context c, int band) {
        return prefs(c).getInt("b" + band, 0);
    }

    static float[] gainsDb(Context c) {
        SharedPreferences p = prefs(c);
        float[] g = new float[N];
        for (int i = 0; i < N; i++) g[i] = p.getInt("b" + i, 0) / 2f;
        return g;
    }

    static void open(Context c, int session, String pkg) {
        if (pkg == null) pkg = "";
        // A player that is killed never closes its session; its next one replaces it.
        if (!pkg.isEmpty()) {
            for (Integer old : sessions.keySet().toArray(new Integer[0])) {
                if (old != session && pkg.equals(sessions.get(old))) close(old);
            }
        }
        sessions.put(session, pkg);
        if (isOn(c) && !effects.containsKey(session)) {
            AudioEffect fx = create(session, gainsDb(c));
            if (fx != null) effects.put(session, fx);
        }
        changed();
    }

    static void close(int session) {
        sessions.remove(session);
        AudioEffect fx = effects.remove(session);
        if (fx != null) fx.release();
        changed();
    }

    static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) {
            float[] g = gainsDb(c);
            for (int s : sessions.keySet()) {
                if (effects.containsKey(s)) continue;
                AudioEffect fx = create(s, g);
                if (fx != null) effects.put(s, fx);
            }
        } else {
            for (AudioEffect fx : effects.values()) fx.release();
            effects.clear();
        }
        changed();
    }

    static void setSteps(Context c, int[] steps) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < N; i++) e.putInt("b" + i, steps[i]);
        e.apply();
        float[] g = gainsDb(c);
        for (AudioEffect fx : effects.values()) apply(fx, g);
    }

    static void setStep(Context c, int band, int step) {
        prefs(c).edit().putInt("b" + band, step).apply();
        float[] g = gainsDb(c);
        for (AudioEffect fx : effects.values()) apply(fx, g);
    }

    /** Whether any attached effect has lost control to another app's effect. */
    static boolean anyOverridden() {
        for (AudioEffect fx : effects.values()) {
            try {
                if (!fx.hasControl()) return true;
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private static void changed() {
        if (listener != null) listener.run();
    }

    // --- Effect engines -----------------------------------------------------------------

    /** Upper edge of each band: halfway (geometrically) to the next centre. */
    private static float cutoff(int i) {
        return i == N - 1 ? 20000f : (float) (FREQ[i] * Math.sqrt(2));
    }

    private static AudioEffect create(int session, float[] g) {
        // DynamicsProcessing (API 28+) gives the same 10 bands on every device.
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, N, false, 0, false, 0, false).build();
            cfg.setPreEqAllChannelsTo(new DynamicsProcessing.Eq(true, true, N));
            for (int i = 0; i < N; i++) {
                cfg.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoff(i), g[i]));
            }
            DynamicsProcessing dp = new DynamicsProcessing(0, session, cfg);
            dp.setEnabled(true);
            Log.i(TAG, "session " + session + ": DynamicsProcessing attached");
            return dp;
        } catch (RuntimeException e) {
            Log.w(TAG, "session " + session + ": DynamicsProcessing failed, falling back", e);
        }
        // Fallback: the device's own Equalizer, fed with our curve at its band centres.
        try {
            Equalizer eq = new Equalizer(0, session);
            apply(eq, g);
            eq.setEnabled(true);
            Log.i(TAG, "session " + session + ": Equalizer attached (" + eq.getNumberOfBands() + " bands)");
            return eq;
        } catch (RuntimeException e) {
            Log.w(TAG, "session " + session + ": Equalizer failed", e);
            return null;
        }
    }

    private static void apply(AudioEffect fx, float[] g) {
        try {
            if (fx instanceof DynamicsProcessing) {
                DynamicsProcessing dp = (DynamicsProcessing) fx;
                for (int i = 0; i < N; i++) {
                    dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoff(i), g[i]));
                }
            } else if (fx instanceof Equalizer) {
                Equalizer eq = (Equalizer) fx;
                short[] range = eq.getBandLevelRange();
                for (short b = 0; b < eq.getNumberOfBands(); b++) {
                    int mb = Math.round(curveAt(g, eq.getCenterFreq(b) / 1000f) * 100);
                    eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
                }
            }
        } catch (RuntimeException e) {
            // Another app's effect has control of this session; ours stays attached but idle.
            Log.w(TAG, "apply failed", e);
        }
    }

    /** Our curve at an arbitrary frequency, interpolated on a log-frequency axis. */
    private static float curveAt(float[] g, float hz) {
        if (hz <= FREQ[0]) return g[0];
        for (int i = 1; i < N; i++) {
            if (hz <= FREQ[i]) {
                double t = Math.log(hz / FREQ[i - 1]) / Math.log((double) FREQ[i] / FREQ[i - 1]);
                return (float) (g[i - 1] + (g[i] - g[i - 1]) * t);
            }
        }
        return g[N - 1];
    }
}
