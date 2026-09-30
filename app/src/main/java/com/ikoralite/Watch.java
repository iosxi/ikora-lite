package com.ikoralite;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Players that never announce their session (YouTube, the video app: seen on the XQ-FS44, it
 * plays with no OPEN broadcast at all). Their session is found instead in `dumpsys audio`,
 * which needs DUMP (granted once from adb, as for {@link Chain}). Without DUMP nothing here runs.
 *
 * No broadcast wakes ikora for these players, so while this is in use ikora stays resident
 * ({@link Eq#needsService}) and looks each time some player starts or stops.
 */
final class Watch {

    /** Packages looked for, with no broadcast of their own. */
    static final String[] SILENT = {
            "com.google.android.youtube",
    };

    private Watch() {}

    static boolean isEnabled(Context c) {
        return Eq.prefs(c).getBoolean("watch", true);
    }

    static void setEnabled(Context c, boolean on) {
        Eq.prefs(c).edit().putBoolean("watch", on).apply();
        Diag.note(c, on ? "知らせを出さないアプリ（YouTube）を探す: ON" : "知らせを出さないアプリ（YouTube）を探す: OFF");
        if (on) look(c);
    }

    static boolean canDump(Context c) {
        return c.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether ikora is looking for silent players now. Whole-output mode covers them already. */
    static boolean active(Context c) {
        return Eq.isOn(c) && !Eq.isGlobal(c) && isEnabled(c) && canDump(c);
    }

    private static boolean isSilent(String pkg) {
        for (String s : SILENT) if (s.equals(pkg)) return true;
        return false;
    }

    // --- Following playback ---------------------------------------------------------------

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Runnable pending;
    private static boolean looking;

    /**
     * Listen to players starting and stopping for as long as the process lives. The callback
     * says only that something changed (the session id is hidden from apps), so each change
     * is followed by one dumpsys run, off the main thread, a moment later: a start comes as a
     * burst of changes.
     */
    static void follow(Context c) {
        Context app = c.getApplicationContext();
        app.getSystemService(AudioManager.class).registerAudioPlaybackCallback(
                new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        if (!active(app)) return;
                        for (AudioPlaybackConfiguration p : configs) {
                            if (p.getAudioAttributes().getUsage() == AudioAttributes.USAGE_MEDIA) {
                                look(app);
                                return;
                            }
                        }
                    }
                }, main);
        look(app);
    }

    static void look(Context c) {
        Context app = c.getApplicationContext();
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            if (!active(app)) return;
            if (looking) {
                // A change during a run may not be in its dump: look again after it.
                look(app);
                return;
            }
            looking = true;
            new Thread(() -> {
                StringBuilder how = new StringBuilder();
                Found f = find(app, how);
                main.post(() -> {
                    looking = false;
                    // Only what went wrong, and only once: this runs on every start and stop of
                    // any player. A find is recorded by take().
                    if (how.length() > 0) Diag.noteIfChanged(app, "watch", "YouTube を探せない: " + how);
                    if (f != null) take(app, f);
                });
            }).start();
        };
        main.postDelayed(pending, 500);
    }

    private static final class Found {
        String pkg;
        int session;
        int piid;
    }

    /**
     * A playing silent player's session. One line per player in the "players:" part:
     *   AudioPlaybackConfiguration piid:1679 ... u/pid:10211/22804 state:started
     *   attr:AudioAttributes: usage=USAGE_MEDIA ... sessionId:1713 ...
     * YouTube makes a new player (piid) for every video but keeps one session (1713 for three
     * videos in a row); the newest playing one is taken.
     */
    private static final Pattern PLAYER = Pattern.compile(
            "piid:(\\d+) .*u/pid:(\\d+)/\\d+ state:started attr:AudioAttributes: usage=USAGE_MEDIA .*sessionId:(\\d+)");

    /** {@code how} gets why nothing could be looked at: the dump refused, or changed shape. */
    private static Found find(Context c, StringBuilder how) {
        Found best = null;
        int lines = 0;
        boolean section = false;
        String first = null;
        PackageManager pm = c.getPackageManager();
        try {
            Process p = new ProcessBuilder("dumpsys", "audio").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                boolean in = false;
                for (String l; (l = r.readLine()) != null; ) {
                    if (lines++ == 0) first = l;
                    if (l.startsWith("  players:")) {
                        in = section = true;
                        continue;
                    }
                    if (!in) continue;
                    if (l.trim().isEmpty()) break;
                    Matcher m = PLAYER.matcher(l);
                    if (!m.find()) continue;
                    int session = Integer.parseInt(m.group(3));
                    int piid = Integer.parseInt(m.group(1));
                    if (session <= 0 || (best != null && best.piid > piid)) continue;
                    String[] pkgs = pm.getPackagesForUid(Integer.parseInt(m.group(2)));
                    if (pkgs == null) continue;
                    for (String pkg : pkgs) {
                        if (!isSilent(pkg)) continue;
                        best = new Found();
                        best.pkg = pkg;
                        best.session = session;
                        best.piid = piid;
                    }
                }
            }
            p.destroy();
        } catch (Exception e) {
            how.append("dumpsys を実行できない: ").append(e);
            return null;
        }
        if (!section) how.append("再生の一覧が無い（").append(lines).append(" 行・").append(first).append("）");
        return best;
    }

    private static void take(Context c, Found f) {
        if (!active(c) || f.pkg.equals(Eq.sessions.get(f.session))) return;
        Diag.note(c, "知らせなしで見つけた: " + f.pkg + " session=" + f.session);
        Eq.open(c, f.session, f.pkg);
        EqService.sync(c);
    }
}
