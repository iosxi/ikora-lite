package com.ikoralite;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What audioserver says is attached to each session, read from `dumpsys media.audio_flinger`.
 * That needs android.permission.DUMP, which only adb can grant (once):
 *   adb shell pm grant com.ikoralite android.permission.DUMP
 * Without it, {@link #read()} returns null and the screen shows only what ikora knows itself.
 */
final class Chain {

    static final class Effect {
        String name;
        boolean enabled;
        /** Package of the app in control, "" when no app holds it (created by the system). */
        String owner = "";
        int ownerPid = -1;
    }

    static final class Session {
        int id;
        /** Mixer thread name, and the device it plays to (e.g. AUDIO_DEVICE_OUT_BLUETOOTH_A2DP). */
        String thread = "";
        String device = "";
        final List<Effect> effects = new ArrayList<>();
    }

    /** Session id → chain, for every output thread. Session 0 appears once per output. */
    final List<Session> sessions = new ArrayList<>();

    private Chain() {}

    static final String GRANT = "adb shell pm grant com.ikoralite android.permission.DUMP";

    private static final Pattern OUTPUT = Pattern.compile("^Output thread \\S+, name (\\S+)");
    private static final Pattern DEVICE = Pattern.compile("^  Output devices: \\S+ \\((.*)\\)");
    private static final Pattern CLIENT = Pattern.compile("^\\s+(\\d+)\\s+\\d+\\s+(\\S+)$");
    private static final Pattern CHAIN = Pattern.compile("^\\s+\\d+ effects for session (-?\\d+)");
    private static final Pattern EFFECT = Pattern.compile("^\\s*Effect ID \\d+:$");
    private static final Pattern STATE = Pattern.compile("^\\s+-?\\d+\\s+\\d+\\s+([yn])\\s+([yn])\\s+([yn])\\s+([yn])");
    private static final Pattern NAME = Pattern.compile("^\\s+- name: (.*)");
    private static final Pattern HOLDER = Pattern.compile("^\\s+(\\d+)\\s+-?\\d+\\s+(yes|no)\\s+(yes|no)");

    /** Null when the dump is not allowed (no DUMP permission) or cannot be run. */
    static Chain read() {
        List<String> lines = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("dumpsys", "media.audio_flinger")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) lines.add(l);
            }
            p.waitFor();
        } catch (Exception e) {
            return null;
        }
        if (lines.isEmpty() || lines.get(0).startsWith("Permission Denial")) return null;
        return parse(lines);
    }

    static Chain parse(List<String> lines) {
        Chain c = new Chain();
        Map<Integer, String> pids = new HashMap<>();
        boolean inClients = false;
        String thread = "", device = "";
        Session s = null;
        Effect e = null;
        boolean inHolders = false;

        for (String l : lines) {
            // "Notification Clients:" maps each audio client pid to its package.
            if (l.startsWith("Notification Clients:")) {
                inClients = true;
                continue;
            }
            if (inClients) {
                Matcher m = CLIENT.matcher(l);
                if (m.find()) {
                    pids.put(Integer.parseInt(m.group(1)), m.group(2));
                    continue;
                }
                if (!l.trim().startsWith("pid")) inClients = false;
            }

            Matcher m = OUTPUT.matcher(l);
            if (m.find()) {
                thread = m.group(1);
                device = "";
                s = null;
                continue;
            }
            m = DEVICE.matcher(l);
            if (m.find()) {
                device = m.group(1);
                continue;
            }
            m = CHAIN.matcher(l);
            if (m.find()) {
                s = new Session();
                s.id = Integer.parseInt(m.group(1));
                s.thread = thread;
                s.device = device;
                c.sessions.add(s);
                e = null;
                continue;
            }
            if (s == null) continue;
            if (EFFECT.matcher(l).find()) {
                e = new Effect();
                s.effects.add(e);
                inHolders = false;
                continue;
            }
            if (e == null) continue;
            m = STATE.matcher(l);
            if (m.find() && e.name == null) {
                e.enabled = "y".equals(m.group(3));
                continue;
            }
            m = NAME.matcher(l);
            if (m.find() && e.name == null) {
                e.name = m.group(1).trim();
                continue;
            }
            if (l.contains("Pid Priority Ctrl")) {
                inHolders = true;
                continue;
            }
            if (inHolders) {
                m = HOLDER.matcher(l);
                if (m.find()) {
                    int pid = Integer.parseInt(m.group(1));
                    if ("yes".equals(m.group(2)) || e.ownerPid < 0) e.ownerPid = pid;
                } else {
                    inHolders = false;
                }
            }
        }
        for (Session x : c.sessions) {
            for (Effect f : x.effects) {
                if (f.ownerPid >= 0) {
                    String p = pids.get(f.ownerPid);
                    f.owner = p == null ? "pid " + f.ownerPid : p;
                }
                if (f.name == null) f.name = "?";
            }
        }
        return c;
    }

    /** The chain of one session, or null if nothing is attached to it. */
    Session find(int id) {
        for (Session s : sessions) if (s.id == id) return s;
        return null;
    }

    /** Session 0 (whole-output effects) on the output that plays the given session. */
    Session mixOf(Session played) {
        for (Session s : sessions) if (s.id == 0 && s.thread.equals(played.thread)) return s;
        return null;
    }
}
