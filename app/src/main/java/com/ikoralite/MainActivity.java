package com.ikoralite;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    /** name, then one step (half dB) per band. */
    private static final Object[][] PRESETS = {
            {"フラット", new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
            {"低音", new int[]{10, 8, 6, 3, 0, 0, 0, 0, 0, 0}},
            {"高音", new int[]{0, 0, 0, 0, 0, 0, 3, 6, 8, 10}},
            {"声", new int[]{-4, -3, -2, 0, 3, 5, 5, 3, 0, -2}},
    };

    private Switch power;
    private TextView status;
    private View battery;
    private RadioGroup picker;
    private RadioButton pickSelf;
    private final SeekBar[] bars = new SeekBar[Eq.N];
    private final TextView[] values = new TextView[Eq.N];
    /** Set while the code, not the user, moves the switch or the picker. */
    private boolean syncing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(build());
        fromPlayer(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        fromPlayer(intent);
    }

    /** Opened from a player's equalizer menu: its session comes with the intent. */
    private void fromPlayer(Intent i) {
        int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
        if (session > 0 && !Eq.sessions.containsKey(session)) {
            Eq.open(this, session, i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME));
            EqService.sync(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Eq.listener = () -> runOnUiThread(this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Eq.listener = null;
    }

    private View build() {
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        power = new Switch(this);
        power.setText(R.string.app_name);
        power.setTextSize(20);
        power.setTypeface(Typeface.DEFAULT_BOLD);
        power.setOnCheckedChangeListener((b, on) -> {
            if (!syncing) setOn(on);
        });
        col.addView(power);

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(12));
        col.addView(status);

        col.addView(batteryHint());

        LinearLayout presets = new LinearLayout(this);
        for (Object[] p : PRESETS) {
            Button b = new Button(this);
            b.setText((String) p[0]);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMaxLines(1);
            b.setPadding(dp(2), b.getPaddingTop(), dp(2), b.getPaddingBottom());
            int[] steps = (int[]) p[1];
            b.setOnClickListener(v -> {
                Eq.setSteps(this, steps);
                for (int i = 0; i < Eq.N; i++) bars[i].setProgress(steps[i] + Eq.STEPS);
            });
            presets.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        col.addView(presets);

        for (int i = 0; i < Eq.N; i++) col.addView(bandRow(i));

        col.addView(heading("使うイコライザ"));
        col.addView(picker());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        // targetSdk 35+ draws edge to edge: keep content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                    in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
        return scroll;
    }

    private void setOn(boolean on) {
        Eq.setOn(this, on);
        EqService.sync(this);
        syncControls();
    }

    private void syncControls() {
        syncing = true;
        boolean on = Eq.isOn(this);
        power.setChecked(on);
        if (on) {
            picker.check(pickSelf.getId());
        } else {
            View v = picker.findViewWithTag(Eq.prefs(this).getString("other", ""));
            if (v != null) picker.check(v.getId());
            else picker.clearCheck();
        }
        syncing = false;
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    /**
     * Every installed equalizer app. Each one hooks the sound by itself and none can switch
     * another off, so picking another app turns ikora off and opens that app to be set up.
     */
    private View picker() {
        picker = new RadioGroup(this);
        pickSelf = new RadioButton(this);
        pickSelf.setId(View.generateViewId());
        pickSelf.setText(getString(R.string.app_name) + "（このアプリ）");
        pickSelf.setOnClickListener(v -> setOn(true));
        picker.addView(pickSelf);

        PackageManager pm = getPackageManager();
        Map<String, ActivityInfo> byPkg = new LinkedHashMap<>();
        for (ResolveInfo r : pm.queryIntentActivities(
                new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL), 0)) {
            ActivityInfo a = r.activityInfo;
            if (a.packageName.equals(getPackageName())) continue;
            // Prefer the app's own screen over a redirector that may bounce elsewhere.
            ActivityInfo had = byPkg.get(a.packageName);
            if (had == null || (had.name.contains("Redirector") && !a.name.contains("Redirector"))) {
                byPkg.put(a.packageName, a);
            }
        }
        for (ActivityInfo a : byPkg.values()) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setTag(a.packageName);
            b.setText(a.applicationInfo.loadLabel(pm));
            b.setOnClickListener(v -> openOther(a));
            picker.addView(b);
        }
        return picker;
    }

    private void openOther(ActivityInfo a) {
        Eq.prefs(this).edit().putString("other", a.packageName).apply();
        setOn(false);
        Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .setComponent(new ComponentName(a.packageName, a.name))
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        for (int s : Eq.sessions.keySet()) i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, s);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            // Some panels only open from inside their own app; fall back to launching it.
            Intent launch = getPackageManager().getLaunchIntentForPackage(a.packageName);
            if (launch != null) startActivity(launch);
        }
    }

    /**
     * Android 12+ refuses to start the foreground service from a broadcast unless the user
     * has lifted battery optimisation for this app. Without it the EQ still works, but only
     * for as long as the system happens to keep the idle process.
     */
    private View batteryHint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(12));
        TextView t = new TextView(this);
        t.setText(R.string.battery_hint);
        box.addView(t);
        Button b = new Button(this);
        b.setText(R.string.battery_open);
        b.setAllCaps(false);
        b.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        box.addView(b);
        battery = box;
        return box;
    }

    private boolean needsBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        return !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private View bandRow(int i) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView label = new TextView(this);
        int f = Eq.FREQ[i];
        label.setText(f >= 1000 ? (f / 1000) + "k" : String.valueOf(f));
        label.setGravity(Gravity.END);
        row.addView(label, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(this);
        bar.setMax(Eq.STEPS * 2);
        bar.setProgress(Eq.step(this, i) + Eq.STEPS);
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView value = new TextView(this);
        value.setGravity(Gravity.END);
        value.setMaxLines(1);
        value.setMinEms(3);
        row.addView(value);

        bars[i] = bar;
        values[i] = value;
        showValue(i);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) Eq.setStep(MainActivity.this, i, p - Eq.STEPS);
                showValue(i);
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return row;
    }

    private void showValue(int i) {
        float db = (bars[i].getProgress() - Eq.STEPS) / 2f;
        values[i].setText(String.format(Locale.ROOT, "%+.1f", db));
    }

    private void refresh() {
        battery.setVisibility(needsBatteryExemption() ? View.VISIBLE : View.GONE);
        syncControls();
        if (Eq.sessions.isEmpty()) {
            status.setText(R.string.idle);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : Eq.sessions.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(label(e.getValue())).append(Eq.effects.containsKey(e.getKey()) ? " に適用中" : " を再生中（オフ）");
        }
        if (Eq.anyOverridden()) sb.append('\n').append(getString(R.string.overridden));
        status.setText(sb);
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg.isEmpty() ? "再生中のアプリ" : pkg;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
