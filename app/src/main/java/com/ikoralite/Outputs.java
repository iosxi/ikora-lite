package com.ikoralite;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Settings per output device. The same curve can be fine on the phone's speaker and distort
 * on one pair of headphones (reported on the Sony WH-1000XM5 over Bluetooth), so each output
 * keeps its own bands and BASS: changing them saves for the current output, and a change of
 * output loads what that output had. Bluetooth devices are told apart by name.
 */
final class Outputs {

    private Outputs() {}

    /** An output, as a stable key for the store and a name for the screen. */
    static final class Out {
        final String key;
        final String label;

        Out(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("outputs", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return Eq.prefs(c).getBoolean("perOutput", true);
    }

    static void setEnabled(Context c, boolean on) {
        Eq.prefs(c).edit().putBoolean("perOutput", on).apply();
        Diag.note(c, on ? "出力機器ごとに覚える: ON" : "出力機器ごとに覚える: OFF");
        // From now on the current values belong to the current output.
        if (on) check(c);
    }

    /** The output whose settings are in the faders now, or null before the first check. */
    static String activeKey(Context c) {
        return Eq.prefs(c).getString("output", null);
    }

    static String activeLabel(Context c) {
        return Eq.prefs(c).getString("outputLabel", "");
    }

    // --- Which output plays music -----------------------------------------------------------

    /** Where music goes now. */
    static Out current(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        AudioDeviceInfo best = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: the route the system has actually chosen for music.
            List<AudioDeviceInfo> route = am.getAudioDevicesForAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (!route.isEmpty()) best = route.get(0);
        }
        if (best == null) {
            // Older: the connected output that Android prefers for music.
            int bestRank = -1;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int r = rank(d.getType());
                if (r > bestRank) {
                    bestRank = r;
                    best = d;
                }
            }
        }
        return best == null ? new Out("speaker", "本体スピーカー") : describe(best);
    }

    private static int rank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return 4;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return 3;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 1;
            default:
                return -1;
        }
    }

    private static Out describe(AudioDeviceInfo d) {
        String name = d.getProductName() == null ? "" : d.getProductName().toString().trim();
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                // One pair of headphones may be A2DP one day and LE Audio the next: key by name.
                return name.isEmpty() ? new Out("bt", "Bluetooth") : new Out("bt:" + name, name);
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return name.isEmpty() ? new Out("usb", "USB") : new Out("usb:" + name, name + "（USB）");
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return new Out("wired", "有線イヤホン");
            default:
                return new Out("speaker", "本体スピーカー");
        }
    }

    // --- Following the output -----------------------------------------------------------------

    private static final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Watch outputs come and go for as long as the process lives. The route to a new pair of
     * headphones settles a moment after they appear, so look again a little later too.
     */
    static void watch(Context c) {
        Context app = c.getApplicationContext();
        Runnable look = () -> check(app);
        app.getSystemService(AudioManager.class).registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                later(look);
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                later(look);
            }
        }, main);
    }

    private static void later(Runnable look) {
        look.run();
        main.removeCallbacks(look);
        main.postDelayed(look, 1000);
        main.postDelayed(look, 3000);
    }

    /**
     * If music now goes somewhere else, save the values under the output they belonged to and
     * load the new output's. An output seen for the first time starts from the current values.
     */
    static void check(Context c) {
        if (!isEnabled(c)) return;
        Out now = current(c);
        String was = activeKey(c);
        if (now.key.equals(was)) return;
        Eq.prefs(c).edit().putString("output", now.key).putString("outputLabel", now.label).apply();
        int[] steps = new int[Eq.N];
        int[] bass = new int[1];
        if (load(c, now.key, steps, bass)) {
            Diag.note(c, "出力が「" + now.label + "」に: この機器の設定に切り替えた（BASS " + bass[0] + "）");
            Eq.setAll(c, steps, bass[0]);
        } else {
            Diag.note(c, "出力が「" + now.label + "」に: 初めての機器なので今の設定を引き継ぐ");
            remember(c);
        }
    }

    /** Save the current values for the current output. Called on every change of them. */
    static void remember(Context c) {
        String key = activeKey(c);
        if (key == null || !isEnabled(c)) return;
        int[] steps = new int[Eq.N];
        for (int i = 0; i < Eq.N; i++) steps[i] = Eq.step(c, i);
        save(c, key, steps, Eq.bass(c));
    }

    // --- Set up ahead of time (the 機器プリセット screen) --------------------------------------

    /** Every output that has settings saved, in no particular order. */
    static java.util.Set<String> savedKeys(Context c) {
        return prefs(c).getAll().keySet();
    }

    /** A Bluetooth device's key, as it will be seen once it plays: by name. */
    static String btKey(String name) {
        return "bt:" + name;
    }

    /** The screen's name for a stored key. */
    static String labelOf(String key) {
        if (key.equals("speaker")) return "本体スピーカー";
        if (key.equals("wired")) return "有線イヤホン";
        if (key.equals("bt")) return "Bluetooth（名前不明）";
        if (key.equals("usb")) return "USB";
        if (key.startsWith("bt:")) return key.substring(3);
        if (key.startsWith("usb:")) return key.substring(4) + "（USB）";
        return key;
    }

    /**
     * Settings for an output that may not be connected: used the next time it plays. If it is
     * the output playing now, they apply at once.
     */
    static void assign(Context c, String key, int[] steps, int bass) {
        save(c, key, steps, bass);
        Diag.note(c, "機器プリセット: 「" + labelOf(key) + "」に割り当て（BASS " + bass + "）");
        if (isEnabled(c) && key.equals(activeKey(c))) Eq.setAll(c, steps, bass);
    }

    /** Forget an output's settings: next time it starts from whatever is current then. */
    static void forget(Context c, String key) {
        prefs(c).edit().remove(key).apply();
    }

    private static void save(Context c, String key, int[] steps, int bass) {
        JSONArray s = new JSONArray();
        for (int v : steps) s.put(v);
        try {
            prefs(c).edit().putString(key, new JSONObject()
                    .put("steps", s).put("bass", bass).toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static boolean load(Context c, String key, int[] steps, int[] bass) {
        String saved = prefs(c).getString(key, null);
        if (saved == null) return false;
        try {
            JSONObject o = new JSONObject(saved);
            JSONArray s = o.getJSONArray("steps");
            // Saved under another band count (a future change): start afresh rather than misread.
            if (s.length() != Eq.N) return false;
            for (int i = 0; i < Eq.N; i++) steps[i] = s.getInt(i);
            bass[0] = o.optInt("bass", 0);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }
}
