package com.ikoralite;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The whole equalizer: settings, and one effect per audio session a player has opened.
 * The sound itself is processed by the platform effect engine inside audioserver;
 * this process only holds the handle, so it does no audio work of its own.
 */
final class Eq {
    static final String TAG = "ikora";

    /**
     * Band centres in Hz: 31 Hz to 16 kHz in equal steps on a log scale, so the middle
     * band (700 Hz) is the log-centre. Gains are stored in half-dB steps, -24..+24 (±12 dB).
     */
    static final int[] FREQ = {31, 88, 250, 700, 2000, 5600, 16000};
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

    /** The whole-output session: every app's sound, mixed. */
    static final int GLOBAL = 0;

    /**
     * Whole-output mode: one effect on session 0 instead of one per player session. For players
     * that never announce their sessions (YT Music on AQUOS R8 does not); it then shapes every
     * sound on the device, and needs the service running all the time.
     */
    static boolean isGlobal(Context c) {
        return prefs(c).getBoolean("global", false);
    }

    static void setGlobal(Context c, boolean global) {
        prefs(c).edit().putBoolean("global", global).apply();
        Diag.note(c, global ? "全体モードにした" : "再生ごとのモードにした");
        releaseAll();
        attachMissing(c);
        changed();
    }

    private static void releaseAll() {
        for (Map.Entry<Integer, AudioEffect> e : effects.entrySet()) dispose(e.getKey(), e.getValue());
        effects.clear();
        control.clear();
        errors.clear();
        blocked.clear();
        globalDpLost = false;
    }

    /**
     * Keep the process (and its runtime receiver) alive all the time, as Wavelet does. For
     * devices that do not start a dead process for a player's broadcast.
     */
    static boolean isResident(Context c) {
        return prefs(c).getBoolean("resident", false);
    }

    static void setResident(Context c, boolean resident) {
        prefs(c).edit().putBoolean("resident", resident).apply();
        Diag.note(c, resident ? "常駐して待つ: ON" : "常駐して待つ: OFF");
    }

    /** Whether the service should be running at all. */
    static boolean needsService(Context c) {
        return !effects.isEmpty() || (isOn(c) && (isResident(c) || isGlobal(c)));
    }

    /** Keys are "g0".. so the 10-band values of v1–v2 ("b0"..) are not misread. */
    static int step(Context c, int band) {
        return prefs(c).getInt("g" + band, 0);
    }

    static float[] gainsDb(Context c) {
        float[] g = new float[N];
        for (int i = 0; i < N; i++) g[i] = step(c, i) / 2f;
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
        attachMissing(c);
        changed();
    }

    static void close(int session) {
        sessions.remove(session);
        if (session == GLOBAL) return;
        errors.remove(session);
        AudioEffect fx = effects.remove(session);
        if (fx != null) dispose(session, fx);
        control.remove(session);
        changed();
    }

    static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) {
            attachMissing(c);
        } else {
            releaseAll();
        }
        changed();
    }

    /**
     * Attach to every open session that has no effect yet. An attach fails while another
     * app's DynamicsProcessing holds the session, so this is retried while the screen is open.
     * Returns whether anything new got attached.
     */
    static boolean attachMissing(Context c) {
        if (!isOn(c)) return false;
        if (isGlobal(c)) {
            // Per-session effects would shape those players twice.
            if (effects.containsKey(GLOBAL)) return false;
            AudioEffect fx = globalDpLost ? createEqualizer(c, GLOBAL, gainsDb(c)) : create(c, GLOBAL, gainsDb(c));
            if (fx == null) return false;
            effects.put(GLOBAL, fx);
            return true;
        }
        boolean any = false;
        float[] g = null;
        for (int s : sessions.keySet()) {
            if (effects.containsKey(s)) continue;
            if (g == null) g = gainsDb(c);
            AudioEffect fx = create(c, s, g);
            if (fx != null) {
                effects.put(s, fx);
                any = true;
            }
        }
        return any;
    }

    static void setSteps(Context c, int[] steps) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < N; i++) e.putInt("g" + i, steps[i]);
        e.apply();
        applyAll(c);
    }

    static void setStep(Context c, int band, int step) {
        prefs(c).edit().putInt("g" + band, step).apply();
        applyAll(c);
    }

    private static void applyAll(Context c) {
        float[] g = gainsDb(c);
        for (AudioEffect fx : effects.values()) apply(fx, g);
    }

    /**
     * Control per session, as last reported to us. Kept ourselves: on the Xperia, hasControl()
     * still said true after the listener had reported the loss (volzz took session 0 over).
     */
    private static final Map<Integer, Boolean> control = new LinkedHashMap<>();

    /** Whether ikora's effect on this session is attached and actually in control. */
    static boolean working(int session) {
        return effects.containsKey(session) && Boolean.TRUE.equals(control.get(session));
    }

    /** Type of ikora's effect on the session, or null. */
    static java.util.UUID typeOn(int session) {
        AudioEffect fx = effects.get(session);
        return fx == null ? null : fx.getDescriptor().type;
    }

    /** How ikora's effect on the session works, for the screen. */
    static String engineOn(int session) {
        AudioEffect fx = effects.get(session);
        if (fx instanceof Equalizer) {
            try {
                return "端末標準のイコライザ・" + ((Equalizer) fx).getNumberOfBands() + " バンドで近似";
            } catch (RuntimeException e) {
                return "端末標準のイコライザで近似";
            }
        }
        return fx == null ? "" : "7 バンド";
    }

    private static void changed() {
        if (listener != null) listener.run();
    }

    // --- Effect engines -----------------------------------------------------------------

    /** Upper edge of each band: halfway (geometrically) to the next centre. */
    private static float cutoff(int i) {
        return i == N - 1 ? 20000f : (float) Math.sqrt((double) FREQ[i] * FREQ[i + 1]);
    }

    private static Boolean hasDp;
    /** Session 0's DynamicsProcessing was taken over by another app: use the Equalizer there. */
    private static boolean globalDpLost;

    static void collectOrphans() {
        System.gc();
        System.runFinalization();
    }
    private static final Set<Integer> blocked = new HashSet<>();

    /** Whether this device has DynamicsProcessing at all (every Android 9+ build should). */
    static boolean deviceHasDp() {
        if (hasDp == null) {
            hasDp = false;
            for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
                if (AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING.equals(d.type)) hasDp = true;
            }
        }
        return hasDp;
    }

    /** Why the last attach to each session failed, for the screen and the report. */
    static final Map<Integer, String> errors = new LinkedHashMap<>();

    /**
     * Release an effect. A DynamicsProcessing engine is shared by every app on the session and
     * keeps the last settings it was given: flatten ours first, so no curve of ikora's stays
     * on in someone else's engine (on session 0 that was volzz's).
     */
    private static void dispose(int session, AudioEffect fx) {
        if (fx instanceof DynamicsProcessing && Boolean.TRUE.equals(control.get(session))) {
            try {
                for (int i = 0; i < N; i++) {
                    ((DynamicsProcessing) fx).setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, cutoff(i), 0f));
                }
            } catch (RuntimeException ignored) {
                // Lost control meanwhile: nothing of ours is being applied then.
            }
        }
        fx.release();
    }

    /**
     * Whether another app already has a DynamicsProcessing on the session. Found by trying one
     * at the lowest priority: without control its config cannot be set, so it throws.
     */
    private static boolean dpTaken(int session) {
        try {
            new DynamicsProcessing(Integer.MIN_VALUE, session, null).release();
            return false;
        } catch (RuntimeException e) {
            collectOrphans();
            return true;
        }
    }

    private static AudioEffect create(Context c, int session, float[] g) {
        // On the whole output a DynamicsProcessing is usually someone's volume tool (volzz)
        // or the vendor's. Joining it would overwrite their settings with ours, or theirs
        // ours: do not touch it, use the Equalizer there instead.
        if (session == GLOBAL && deviceHasDp() && dpTaken(GLOBAL)) {
            globalDpLost = true;
            Diag.note(c, "全体: ほかのアプリの DynamicsProcessing があるため、端末標準のイコライザを使う");
            return createEqualizer(c, session, g);
        }
        if (deviceHasDp()) {
            // The same bands on every device. If another app's DynamicsProcessing already
            // holds this session with a higher priority, setting our config fails: report
            // that instead of stacking a second equalizer on top.
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
                control.put(session, dp.hasControl());
                watchControl(c, session, dp);
                errors.remove(session);
                Diag.note(c, "session " + session + ": DynamicsProcessing を付けた（制御権 "
                        + (dp.hasControl() ? "あり" : "なし") + "）");
                return dp;
            } catch (RuntimeException e) {
                errors.put(session, String.valueOf(e.getMessage()));
                // The constructor failed after audioserver had made our handle: let the
                // finalizer release it now rather than leave it attached until some later GC.
                collectOrphans();
                // Retried every few seconds while the screen is open: say it once.
                if (blocked.add(session)) {
                    Diag.note(c, "session " + session + ": DynamicsProcessing を付けられない: " + e);
                }
                // On a player's session, a DynamicsProcessing we cannot control means another
                // equalizer shapes it: stacking a second one is wrong. On the whole output it
                // is usually a volume tool (volzz) or the vendor's; taking it over would break
                // theirs, so fall back to a different kind of effect instead.
                if (session != GLOBAL) return null;
            }
        }
        return createEqualizer(c, session, g);
    }

    /** The device's own Equalizer, fed with our curve. */
    private static AudioEffect createEqualizer(Context c, int session, float[] g) {
        try {
            Equalizer eq = new Equalizer(0, session);
            apply(eq, g);
            eq.setEnabled(true);
            control.put(session, eq.hasControl());
            watchControl(c, session, eq);
            errors.remove(session);
            Diag.note(c, "session " + session + ": Equalizer を付けた（" + eq.getNumberOfBands()
                    + " バンドで近似・制御権 " + (eq.hasControl() ? "あり" : "なし") + "）");
            return eq;
        } catch (RuntimeException e) {
            errors.put(session, String.valueOf(e.getMessage()));
            if (blocked.add(session)) Diag.note(c, "session " + session + ": Equalizer を付けられない: " + e);
            return null;
        }
    }

    /**
     * Another app attaching the same kind of effect later with a higher priority takes the
     * shared engine over (Poweramp Equalizer uses 1337); ours stays attached but its settings
     * no longer apply. Record both directions, and re-apply our curve on getting it back.
     */
    private static void watchControl(Context c, int session, AudioEffect fx) {
        Context app = c.getApplicationContext();
        fx.setControlStatusListener((effect, granted) -> {
            Diag.note(app, "session " + session + ": 制御権を" + (granted ? "取り戻した" : "失った（ほかのアプリが優先）"));
            control.put(session, granted);
            if (granted) {
                apply(effect, gainsDb(app));
            } else if (session == GLOBAL && effect instanceof DynamicsProcessing && effects.get(GLOBAL) == effect) {
                // On the whole output the engine is shared with whoever took it (volzz, a
                // vendor tool): keep out of their settings and move to a different kind.
                globalDpLost = true;
                effects.remove(GLOBAL);
                control.remove(GLOBAL);
                effect.release();
                Diag.note(app, "全体: DynamicsProcessing を手放し、端末標準のイコライザに切り替える");
                attachMissing(app);
            }
            changed();
        });
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
            // Another app's effect has taken control of this session.
            Log.w(TAG, "apply failed: " + e.getMessage());
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
