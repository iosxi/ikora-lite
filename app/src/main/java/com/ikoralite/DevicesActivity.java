package com.ikoralite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 機器プリセット: choose each output's preset and BASS ahead of time, whether or not it is
 * connected, so tomorrow's headphones need only be put on. Lists the outputs ikora has seen
 * and, with the nearby-devices permission, the phone's paired Bluetooth audio devices.
 */
public class DevicesActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("機器プリセット");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.devices_hint);
        hint.setTextSize(12);
        hint.setPadding(0, dp(4), 0, dp(8));
        col.addView(hint);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        if (!canReadPaired()) {
            Button paired = new Button(this);
            paired.setText("ペアリング済みの Bluetooth 機器も表示する");
            paired.setAllCaps(false);
            paired.setOnClickListener(v -> requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1));
            paired.setTag("paired");
            col.addView(paired);
            TextView ph = new TextView(this);
            ph.setText(R.string.paired_hint);
            ph.setTextSize(12);
            ph.setTag("pairedHint");
            col.addView(ph);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                    in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
        setContentView(scroll);
        fill();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (canReadPaired()) {
            View b = getWindow().getDecorView().findViewWithTag("paired");
            View h = getWindow().getDecorView().findViewWithTag("pairedHint");
            if (b != null) b.setVisibility(View.GONE);
            if (h != null) h.setVisibility(View.GONE);
        }
        fill();
    }

    /** Before Android 12 the paired list needs only an install-time permission. */
    private boolean canReadPaired() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** The speaker and wired first, then every named device, by name. */
    private List<String> keys() {
        Set<String> out = new LinkedHashSet<>(Arrays.asList("speaker", "wired"));
        Set<String> named = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        named.addAll(Outputs.savedKeys(this));
        named.addAll(pairedAudio());
        String active = Outputs.activeKey(this);
        if (active != null) named.add(active);
        out.addAll(named);
        return new ArrayList<>(out);
    }

    /**
     * Paired Bluetooth devices of the audio kind (headphones, speakers, car audio), keyed by
     * name as their output will be. Empty without the permission.
     */
    private Set<String> pairedAudio() {
        Set<String> keys = new TreeSet<>();
        if (!canReadPaired()) return keys;
        try {
            BluetoothAdapter a = getSystemService(BluetoothManager.class).getAdapter();
            if (a == null) return keys;
            for (BluetoothDevice d : a.getBondedDevices()) {
                BluetoothClass k = d.getBluetoothClass();
                if (k == null || k.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;
                String name = d.getName();
                if (name != null && !name.trim().isEmpty()) keys.add(Outputs.btKey(name.trim()));
            }
        } catch (SecurityException e) {
            // Permission taken back meanwhile: show what ikora has seen itself.
        }
        return keys;
    }

    private void fill() {
        list.removeAllViews();
        String active = Outputs.isEnabled(this) ? Outputs.activeKey(this) : null;
        for (String key : keys()) list.addView(row(key, key.equals(active)));
    }

    private View row(String key, boolean active) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackgroundResource(ripple());
        TextView name = new TextView(this);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append(Outputs.labelOf(key));
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (active) sb.append("（いま使用中）");
        name.setText(sb);
        name.setTextSize(16);
        r.addView(name);
        TextView what = new TextView(this);
        what.setText(summary(key));
        r.addView(what);
        r.setOnClickListener(v -> choose(key));
        r.setOnLongClickListener(v -> {
            askForget(key);
            return true;
        });
        View rule = new View(this);
        rule.setBackgroundColor(0x33808080);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(r);
        box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return box;
    }

    private int ripple() {
        android.util.TypedValue t = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, t, true);
        return t.resourceId;
    }

    private String summary(String key) {
        int[] steps = new int[Eq.N];
        int[] bass = new int[1];
        if (!Outputs.load(this, key, steps, bass)) return "未設定（初めて鳴らしたときの設定を引き継ぎます）";
        return presetName(steps) + " ・ BASS " + (bass[0] == 0 ? "OFF" : String.valueOf(bass[0]));
    }

    private String presetName(int[] steps) {
        for (Presets.Preset p : allPresets()) if (p.matches(steps)) return p.name;
        return "カスタム";
    }

    private List<Presets.Preset> allPresets() {
        List<Presets.Preset> all = new ArrayList<>(Presets.BUILT_IN);
        all.addAll(Presets.user(this));
        return all;
    }

    /** A preset and a BASS level for the output. */
    private void choose(String key) {
        int[] steps = new int[Eq.N];
        int[] bass = new int[1];
        boolean has = Outputs.load(this, key, steps, bass);
        if (!has) {
            // Nothing yet: offer what it would start from, the current values.
            for (int i = 0; i < Eq.N; i++) steps[i] = Eq.step(this, i);
            bass[0] = Eq.bass(this);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        RadioGroup presets = new RadioGroup(this);
        List<Presets.Preset> all = allPresets();
        boolean matched = false;
        for (Presets.Preset p : all) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(p.name);
            b.setTag(p.steps);
            presets.addView(b);
            if (!matched && p.matches(steps)) {
                presets.check(b.getId());
                matched = true;
            }
        }
        if (!matched) {
            // Not any preset (moved faders, or the 全体 fader): keep it as it is.
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText("今の値のまま（カスタム）");
            b.setTag(steps.clone());
            presets.addView(b, 0);
            presets.check(b.getId());
        }
        box.addView(presets);

        TextView bassLabel = new TextView(this);
        bassLabel.setText("BASS");
        bassLabel.setTypeface(Typeface.DEFAULT_BOLD);
        bassLabel.setPadding(0, dp(12), 0, 0);
        box.addView(bassLabel);
        RadioGroup levels = new RadioGroup(this);
        levels.setOrientation(RadioGroup.HORIZONTAL);
        for (int l = 0; l <= Eq.BASS_MAX; l++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(l == 0 ? "OFF" : String.valueOf(l));
            b.setTag(l);
            levels.addView(b);
            if (l == bass[0]) levels.check(b.getId());
        }
        HorizontalFit fit = new HorizontalFit(this);
        fit.addView(levels);
        box.addView(fit);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(Outputs.labelOf(key))
                .setView(sv)
                .setPositiveButton("決定", (d, w) -> {
                    int[] s = (int[]) presets.findViewById(presets.getCheckedRadioButtonId()).getTag();
                    int l = (int) levels.findViewById(levels.getCheckedRadioButtonId()).getTag();
                    Outputs.assign(this, key, s, l);
                    fill();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** Six BASS choices may not fit a narrow dialog: let them scroll sideways. */
    private static final class HorizontalFit extends android.widget.HorizontalScrollView {
        HorizontalFit(android.content.Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
        }
    }

    private void askForget(String key) {
        int[] steps = new int[Eq.N];
        if (!Outputs.load(this, key, steps, new int[1])) return;
        new AlertDialog.Builder(this)
                .setMessage("「" + Outputs.labelOf(key) + "」の設定を消しますか？\n次に鳴らしたときは、その時の設定を引き継ぎます。")
                .setPositiveButton("消す", (d, w) -> {
                    Outputs.forget(this, key);
                    fill();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
