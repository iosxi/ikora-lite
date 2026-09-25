package com.ikoralite;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A short, persistent record of what happened: broadcasts received, attaches that worked
 * or failed and why, service starts. Kept so a tester can send it without adb or DUMP;
 * it survives the process being killed, which is exactly when it is needed.
 */
final class Diag {
    private static final int KEEP = 40;
    private static final String KEY = "events";

    private Diag() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("diag", Context.MODE_PRIVATE);
    }

    static void note(Context c, String what) {
        Log.i(Eq.TAG, what);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + what;
        List<String> ev = events(c);
        ev.add(line);
        while (ev.size() > KEEP) ev.remove(0);
        prefs(c).edit().putString(KEY, String.join("\n", ev)).apply();
    }

    /** Like {@link #note}, but skipped when the same key last noted the same text. */
    static void noteIfChanged(Context c, String key, String what) {
        if (what.equals(prefs(c).getString("last_" + key, null))) return;
        prefs(c).edit().putString("last_" + key, what).apply();
        note(c, what);
    }

    static List<String> events(Context c) {
        String s = prefs(c).getString(KEY, "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    /** Whether any player has ever told us about a session. */
    static boolean everReceived(Context c) {
        return prefs(c).getBoolean("received", false);
    }

    static void received(Context c) {
        if (!everReceived(c)) prefs(c).edit().putBoolean("received", true).apply();
    }

    // --- Delivery self-test -----------------------------------------------------------------

    static void selfTestStart(Context c) {
        prefs(c).edit().putLong("testAt", System.currentTimeMillis())
                .putBoolean("testManifest", false).putBoolean("testRuntime", false).apply();
    }

    static void selfTestArrived(Context c, boolean runtime) {
        prefs(c).edit().putBoolean(runtime ? "testRuntime" : "testManifest", true).apply();
    }

    /** Result of the last self-test in words, or null if none has run. */
    static String selfTestResult(Context c) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("testAt", 0);
        if (at == 0) return null;
        String when = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(at));
        boolean m = p.getBoolean("testManifest", false), r = p.getBoolean("testRuntime", false);
        return when + " 登録の受け口: " + (m ? "届いた" : "届かない")
                + " / 動的な受け口: " + (r ? "届いた" : "届かない");
    }

    /** Version of each installed player we know sends session broadcasts, for comparison. */
    private static final String[][] PLAYERS = {
            {"com.google.android.apps.youtube.music", "YouTube Music"},
            {"com.spotify.music", "Spotify"},
            {"com.amazon.mp3", "Amazon Music"},
    };

    static String players(Context c) {
        StringBuilder sb = new StringBuilder();
        for (String[] p : PLAYERS) {
            try {
                PackageInfo pi = c.getPackageManager().getPackageInfo(p[0], 0);
                sb.append("- ").append(p[1]).append(' ').append(pi.versionName)
                        .append(" (targetSdk ").append(pi.applicationInfo.targetSdkVersion).append(")\n");
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return sb.length() == 0 ? "（見つからない）\n" : sb.toString();
    }

    /**
     * Sounds playing right now, as any app may see them (no permission): usage and device only.
     * The session id is not public, so this can tell "music is playing but no player told us",
     * not which session to attach to.
     */
    static List<String> playing(Context c) {
        List<String> out = new ArrayList<>();
        AudioManager am = c.getSystemService(AudioManager.class);
        for (AudioPlaybackConfiguration p : am.getActivePlaybackConfigurations()) {
            AudioAttributes a = p.getAudioAttributes();
            String dev = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && p.getAudioDeviceInfo() != null) {
                dev = " → " + p.getAudioDeviceInfo().getProductName();
            }
            out.add(usage(a.getUsage()) + dev);
        }
        return out;
    }

    /** Whether music or other media is playing now. */
    static boolean mediaPlaying(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        for (AudioPlaybackConfiguration p : am.getActivePlaybackConfigurations()) {
            if (p.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA) return true;
        }
        return false;
    }

    private static String usage(int u) {
        switch (u) {
            case AudioAttributes.USAGE_MEDIA: return "メディア（音楽・動画）";
            case AudioAttributes.USAGE_GAME: return "ゲーム";
            case AudioAttributes.USAGE_NOTIFICATION: return "通知";
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE: return "ナビ音声";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION: return "通話";
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION: return "操作音";
            default: return "その他 (usage " + u + ")";
        }
    }

    /** Everything a tester can paste back: device, settings, platform effects, events. */
    static String report(Context c, String state, String probe) {
        StringBuilder sb = new StringBuilder();
        sb.append("ikora 診断情報\n");
        try {
            sb.append("版: ").append(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName).append('\n');
        } catch (Exception ignored) {
        }
        sb.append("端末: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("\n[状態]\n").append(state).append('\n');
        String test = selfTestResult(c);
        sb.append("\n[受信テスト]\n").append(test == null ? "（未実施）" : test).append('\n');
        sb.append("\n[音楽アプリの版]\n").append(players(c));
        sb.append("\n[いま鳴っている音]\n");
        List<String> now = playing(c);
        if (now.isEmpty()) sb.append("（なし）\n");
        for (String l : now) sb.append("- ").append(l).append('\n');
        sb.append("\n[他の効果の調査]\n").append(probe == null || probe.isEmpty() ? "（未実施）" : probe).append('\n');
        sb.append("\n[端末が持つ音響効果]\n");
        try {
            for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
                sb.append("- ").append(d.name).append(" / ").append(d.implementor).append('\n');
            }
        } catch (RuntimeException e) {
            sb.append("（取得できず: ").append(e.getMessage()).append(")\n");
        }
        sb.append("\n[出来事（古い順）]\n");
        List<String> ev = events(c);
        if (ev.isEmpty()) sb.append("（まだ何も起きていません）\n");
        for (String l : ev) sb.append(l).append('\n');
        return sb.toString();
    }
}
