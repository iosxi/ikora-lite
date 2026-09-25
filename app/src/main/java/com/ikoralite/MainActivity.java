package com.ikoralite;

import android.Manifest;
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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    /** name, then one step (half dB) per band: 31, 88, 250, 700, 2k, 5.6k, 16k Hz. */
    private static final Object[][] PRESETS = {
            {"フラット", new int[]{0, 0, 0, 0, 0, 0, 0}},
            {"低音", new int[]{10, 7, 3, 0, 0, 0, 0}},
            {"高音", new int[]{0, 0, 0, 0, 3, 7, 10}},
            {"声", new int[]{-4, -2, 2, 5, 4, 0, -2}},
    };

    /** How often the open screen re-reads the chain and retries a blocked attach. */
    private static final long POLL_MS = 2000;

    private Switch power;
    private TextView status;
    private TextView chainView;
    private View battery;
    private LinearLayout presets;
    private BandsView bands;
    private RadioGroup picker;
    private RadioButton pickSelf;
    /** Set while the code, not the user, moves the switch or the picker. */
    private boolean syncing;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;
    /** Last chain read, or null when it cannot be read (no DUMP permission). */
    private Chain chain;

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
        resumed = true;
        Eq.listener = () -> runOnUiThread(this::refresh);
        refresh();
        poll.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(poll);
        Eq.listener = null;
    }

    /** While the screen is open only: retry blocked attaches and re-read the chain. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (Eq.attachMissing(MainActivity.this)) EqService.sync(MainActivity.this);
            if (canDump()) {
                new Thread(() -> {
                    Chain c = Chain.read();
                    main.post(() -> {
                        chain = c;
                        if (resumed) refresh();
                    });
                }).start();
            } else {
                chain = null;
                refresh();
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    private boolean canDump() {
        return checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
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
        status.setTextSize(16);
        status.setPadding(0, dp(4), 0, dp(8));
        col.addView(status);

        chainView = new TextView(this);
        chainView.setPadding(dp(12), dp(8), dp(12), dp(8));
        chainView.setBackgroundColor(0x14808080);
        col.addView(chainView);

        col.addView(batteryHint());

        presets = new LinearLayout(this);
        presets.setPadding(0, dp(12), 0, dp(4));
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
                bands.setSteps(steps);
            });
            presets.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        col.addView(presets);

        bands = new BandsView(this);
        int[] steps = new int[Eq.N];
        for (int i = 0; i < Eq.N; i++) steps[i] = Eq.step(this, i);
        bands.setSteps(steps);
        bands.setOnChange((band, step) -> Eq.setStep(this, band, step));
        col.addView(bands);

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
        // Off: the curve stays visible but greyed and untouchable.
        bands.setEnabled(on);
        for (int i = 0; i < presets.getChildCount(); i++) presets.getChildAt(i).setEnabled(on);
        presets.setAlpha(on ? 1f : 0.3f);
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

    // --- What is in effect ----------------------------------------------------------------

    private void refresh() {
        battery.setVisibility(needsBatteryExemption() ? View.VISIBLE : View.GONE);
        syncControls();
        status.setText(summary());
        chainView.setText(chainText());
    }

    /** One line per open session: is ikora actually shaping it, and if not, who is. */
    private CharSequence summary() {
        if (Eq.sessions.isEmpty()) return getString(R.string.idle);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Map.Entry<Integer, String> e : Eq.sessions.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            String player = label(e.getValue());
            if (!Eq.isOn(this)) {
                sb.append("ikora はオフ（").append(player).append(" を再生中）");
            } else if (Eq.working(e.getKey())) {
                bold(sb, "✓ " + player + " に ikora が効いています");
            } else {
                bold(sb, "✗ " + player + " に ikora は効いていません");
                String by = rival(e.getKey());
                sb.append('\n').append(by == null
                        ? "ほかのイコライザが優先されています。そちらを止めると自動で効きます。"
                        : by + " が優先されています。そちらを止めると自動で効きます。");
            }
        }
        return sb;
    }

    /** The app whose equalizer-type effect is in control of the session, if the chain says. */
    private String rival(int session) {
        if (chain == null) return null;
        Chain.Session s = chain.find(session);
        if (s == null) return null;
        for (Chain.Effect f : s.effects) {
            if (f.enabled && isEq(f.name) && !f.owner.equals(getPackageName())) return owner(f);
        }
        return null;
    }

    private static boolean isEq(String name) {
        return name.contains("DynamicsProcessing") || name.contains("Equalizer");
    }

    /** The processing order, as audioserver runs it, for each open session. */
    private CharSequence chainText() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        bold(sb, "今の音の流れ（上から順に処理）");
        if (!canDump()) {
            sb.append("\n詳しく見るには、PC につないで一度だけ次を実行してください:\n")
                    .append(Chain.GRANT);
            return sb;
        }
        if (chain == null) {
            sb.append("\n読み込み中…");
            return sb;
        }
        if (Eq.sessions.isEmpty()) {
            sb.append("\n再生が始まると表示します。");
            return sb;
        }
        for (Map.Entry<Integer, String> e : Eq.sessions.entrySet()) {
            Chain.Session s = chain.find(e.getKey());
            sb.append("\n\n");
            bold(sb, label(e.getValue()));
            if (s == null) {
                sb.append("\n  （何も付いていません）");
                continue;
            }
            sb.append("  → ").append(device(s.device));
            lines(sb, s);
            Chain.Session mix = chain.mixOf(s);
            if (mix != null && !mix.effects.isEmpty()) {
                sb.append("\n  ↓ ほかのアプリの音と合流（全体）");
                lines(sb, mix);
            }
            sb.append("\n  ↓ ").append(device(s.device))
                    .append("\n  （ヘッドホン本体のイコライザは、ここからは見えません）");
        }
        return sb;
    }

    private void lines(SpannableStringBuilder sb, Chain.Session s) {
        for (Chain.Effect f : s.effects) {
            int start = sb.length();
            sb.append("\n  ").append(f.enabled ? "● " : "○ ")
                    .append(owner(f)).append(" — ").append(effectName(f.name))
                    .append(f.enabled ? "" : "（無効）");
            if (!f.enabled) {
                sb.setSpan(new ForegroundColorSpan(0xFF9E9E9E), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private String owner(Chain.Effect f) {
        if (f.owner.isEmpty()) return "持ち主なし";
        if (f.owner.equals(getPackageName())) return "ikora";
        if (f.owner.equals("audioserver")) return "システム";
        return label(f.owner);
    }

    private static String effectName(String n) {
        if (n.contains("DynamicsProcessing")) return "多バンドEQ";
        if (n.contains("Equalizer")) return "イコライザ";
        if (n.contains("Bass")) return "低音強調";
        if (n.contains("Virtualizer")) return "バーチャライザ";
        if (n.contains("Loudness")) return "音量強調";
        return n;
    }

    private static String device(String d) {
        if (d.contains("BLUETOOTH") || d.contains("BLE_")) return "Bluetooth";
        if (d.contains("SPEAKER")) return "スピーカー";
        if (d.contains("WIRED") || d.contains("HEADSET") || d.contains("HEADPHONE")) return "有線イヤホン";
        if (d.contains("USB")) return "USB";
        return d.isEmpty() ? "出力" : d;
    }

    private static void bold(SpannableStringBuilder sb, String s) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // --- Picker -------------------------------------------------------------------------

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

    // --- Battery ------------------------------------------------------------------------

    /**
     * Android 12+ refuses to start the foreground service from a broadcast unless the user
     * has lifted battery optimisation for this app. Without it the EQ still works, but only
     * for as long as the system happens to keep the idle process.
     */
    private View batteryHint() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);
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
