package com.ikoralite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Built-in presets, and the user's own, saved by name. Steps are half dB per band. */
final class Presets {

    static final class Preset {
        final String name;
        final int[] steps;
        final boolean builtIn;

        Preset(String name, int[] steps, boolean builtIn) {
            this.name = name;
            this.steps = steps;
            this.builtIn = builtIn;
        }

        boolean matches(int[] current) {
            return Arrays.equals(steps, current);
        }
    }

    /** Bands: 31, 88, 250, 700, 2k, 5.6k, 16k Hz. */
    static final List<Preset> BUILT_IN = Arrays.asList(
            new Preset("フラット", new int[]{0, 0, 0, 0, 0, 0, 0}, true),
            new Preset("低音", new int[]{10, 7, 3, 0, 0, 0, 0}, true),
            new Preset("高音", new int[]{0, 0, 0, 0, 3, 7, 10}, true),
            new Preset("声", new int[]{-4, -2, 2, 5, 4, 0, -2}, true));

    private static final String KEY = "user";

    private Presets() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("presets", Context.MODE_PRIVATE);
    }

    /** The user's presets, oldest first. */
    static List<Preset> user(Context c) {
        List<Preset> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                JSONArray s = o.getJSONArray("steps");
                // Saved under another band count (a future change): skip rather than misread.
                if (s.length() != Eq.N) continue;
                int[] steps = new int[Eq.N];
                for (int b = 0; b < Eq.N; b++) steps[b] = s.getInt(b);
                out.add(new Preset(o.getString("name"), steps, false));
            }
        } catch (JSONException ignored) {
            // Unreadable store: behave as if empty rather than crash the screen.
        }
        return out;
    }

    static boolean isBuiltInName(String name) {
        for (Preset p : BUILT_IN) if (p.name.equals(name)) return true;
        return false;
    }

    static boolean exists(Context c, String name) {
        for (Preset p : user(c)) if (p.name.equals(name)) return true;
        return false;
    }

    /** Save under the name, replacing a user preset of the same name in place. */
    static void save(Context c, String name, int[] steps) {
        List<Preset> list = user(c);
        Preset p = new Preset(name, steps.clone(), false);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.set(i, p);
                replaced = true;
            }
        }
        if (!replaced) list.add(p);
        write(c, list);
    }

    static void delete(Context c, String name) {
        List<Preset> list = user(c);
        list.removeIf(p -> p.name.equals(name));
        write(c, list);
    }

    private static void write(Context c, List<Preset> list) {
        JSONArray a = new JSONArray();
        try {
            for (Preset p : list) {
                JSONArray s = new JSONArray();
                for (int v : p.steps) s.put(v);
                a.put(new JSONObject().put("name", p.name).put("steps", s));
            }
        } catch (JSONException e) {
            return;
        }
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }
}
