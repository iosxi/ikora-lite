package com.ikoralite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    /** How often the open screen re-reads the chain and retries a blocked attach. */
    private static final long POLL_MS = 2000;

    private Switch power;
    private Switch global;
    private Switch resident;
    private Switch perOutput;
    /** Which output's settings the faders and BASS show. */
    private TextView outputView;
    private String shownOutput;
    private TextView status;
    private TextView chainView;
    private TextView diagView;
    private Button probeButton;
    /** Result of the last permission-free probe, null until one has run. */
    private String probeText;
    private boolean probing;
    /** Noted once per screen: music playing without a session broadcast. */
    private boolean notedSilentPlayer;
    private View battery;
    /** Opens the last player's app info, to force-stop it; shown only when a session is missed. */
    private Button playerInfo;
    private LinearLayout presets;
    private BandsView bands;
    private LinearLayout bassRow;
    /** The theme's button text colours, restored on the levels not selected. */
    private ColorStateList bassText;
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
        Outputs.check(this);
        refresh();
        markBass();
        poll.run();
        if (!canDump() && isOpen("detail")) probe(null);
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

    /**
     * Android 13+: let the resident notification show. Asked only on turning a resident mode
     * on; the service runs whether or not it is granted.
     */
    private void askNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    private boolean canDump() {
        return checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED;
    }

    private View build() {
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        // --- Always visible: what is used every day ---
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
        playerInfo = new Button(this);
        playerInfo.setAllCaps(false);
        playerInfo.setVisibility(View.GONE);
        playerInfo.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", lastPlayer(), null))));
        col.addView(playerInfo);

        col.addView(batteryHint());

        outputView = new TextView(this);
        outputView.setPadding(0, dp(12), 0, 0);
        col.addView(outputView);
        Button devices = new Button(this);
        devices.setText("機器プリセット…");
        devices.setAllCaps(false);
        devices.setOnClickListener(v -> startActivity(new Intent(this, DevicesActivity.class)));
        col.addView(devices);

        presets = new LinearLayout(this);
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.setPadding(0, dp(12), 0, 0);
        presetScroll.addView(presets);
        col.addView(presetScroll);
        TextView presetHint = new TextView(this);
        presetHint.setText(R.string.preset_hint);
        presetHint.setTextSize(12);
        col.addView(presetHint);

        bands = new BandsView(this);
        bands.setSteps(currentSteps());
        bands.setOnChange((band, step) -> {
            Eq.setStep(this, band, step);
            markPresets();
        });
        bands.setOnShift(steps -> {
            Eq.setSteps(this, steps);
            markPresets();
        });
        col.addView(bands);
        buildPresets();

        LinearLayout bassBox = new LinearLayout(this);
        bassBox.setGravity(Gravity.CENTER_VERTICAL);
        bassBox.setPadding(0, dp(8), 0, 0);
        TextView bassLabel = new TextView(this);
        bassLabel.setText("BASS");
        bassLabel.setTypeface(Typeface.DEFAULT_BOLD);
        bassLabel.setPadding(0, 0, dp(8), 0);
        bassBox.addView(bassLabel);
        bassRow = new LinearLayout(this);
        for (int level = 0; level <= Eq.BASS_MAX; level++) {
            Button b = new Button(this);
            b.setText(level == 0 ? "OFF" : String.valueOf(level));
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            // Fixed 40dp height: drop the theme's padding and minimum height, or the text is clipped.
            b.setPadding(0, 0, 0, 0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setGravity(Gravity.CENTER);
            if (level == 0) bassText = b.getTextColors();
            int l = level;
            b.setOnClickListener(v -> {
                Eq.setBass(this, l);
                markBass();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            bassRow.addView(b, lp);
        }
        bassBox.addView(bassRow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(bassBox);
        col.addView(hint(R.string.bass_hint));
        markBass();

        // --- Folded away: settings, the other equalizers, and debugging ---
        LinearLayout modes = section(col, "動作の設定", "modes");
        global = new Switch(this);
        global.setText(R.string.global_mode);
        global.setOnCheckedChangeListener((b, on) -> {
            if (syncing) return;
            Eq.setGlobal(this, on);
            // Whole-output mode keeps the service up from here (allowed: we are in front).
            EqService.sync(this);
            if (on) askNotifications();
            refresh();
        });
        modes.addView(global);
        modes.addView(hint(R.string.global_hint));
        resident = new Switch(this);
        resident.setText(R.string.resident_mode);
        resident.setOnCheckedChangeListener((b, on) -> {
            if (syncing) return;
            Eq.setResident(this, on);
            // Started from here while we are in front, which every Android allows.
            EqService.sync(this);
            if (on) askNotifications();
            refresh();
        });
        modes.addView(resident);
        modes.addView(hint(R.string.resident_hint));
        perOutput = new Switch(this);
        perOutput.setText(R.string.per_output);
        perOutput.setOnCheckedChangeListener((b, on) -> {
            if (syncing) return;
            Outputs.setEnabled(this, on);
            refresh();
        });
        modes.addView(perOutput);
        modes.addView(hint(R.string.per_output_hint));

        section(col, "使うイコライザ", "picker").addView(picker());

        LinearLayout detail = section(col, "詳しい状態", "detail");
        chainView = new TextView(this);
        chainView.setPadding(dp(12), dp(8), dp(12), dp(8));
        chainView.setBackgroundColor(0x14808080);
        detail.addView(chainView);
        probeButton = new Button(this);
        probeButton.setText("ほかの効果をもう一度調べる");
        probeButton.setAllCaps(false);
        probeButton.setOnClickListener(v -> probe(null));
        detail.addView(probeButton);

        LinearLayout diag = section(col, "診断", "diag");
        diagView = new TextView(this);
        diagView.setTextSize(12);
        diag.addView(diagView);
        Button test = new Button(this);
        test.setText("受信テスト（知らせが ikora に届くか）");
        test.setAllCaps(false);
        test.setOnClickListener(v -> {
            SessionReceiver.sendSelfTest(this);
            // Delivery takes milliseconds; show whatever arrived after a moment.
            main.postDelayed(this::refresh, 1500);
        });
        diag.addView(test);
        Button send = new Button(this);
        send.setText("診断情報を送る");
        send.setAllCaps(false);
        send.setOnClickListener(v -> sendReport());
        diag.addView(send);

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

    // --- Accordion ------------------------------------------------------------------------

    private SharedPreferences ui() {
        return getSharedPreferences("ui", MODE_PRIVATE);
    }

    private boolean isOpen(String key) {
        return ui().getBoolean("open_" + key, false);
    }

    /** A header that folds its body away; closed by default, and remembered. */
    private LinearLayout section(LinearLayout parent, String title, String key) {
        View rule = new View(this);
        rule.setBackgroundColor(0x33808080);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(16);
        parent.addView(rule, lp);

        TextView head = new TextView(this);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(16);
        head.setPadding(0, dp(12), 0, dp(12));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        Runnable show = () -> {
            boolean open = isOpen(key);
            head.setText((open ? "▾  " : "▸  ") + title);
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        };
        show.run();
        head.setOnClickListener(v -> {
            boolean open = !isOpen(key);
            ui().edit().putBoolean("open_" + key, open).apply();
            show.run();
            if (open && key.equals("detail") && !canDump()) probe(null);
        });
        parent.addView(head);
        parent.addView(body);
        return body;
    }

    private TextView hint(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    // --- Presets --------------------------------------------------------------------------

    private int[] currentSteps() {
        int[] steps = new int[Eq.N];
        for (int i = 0; i < Eq.N; i++) steps[i] = Eq.step(this, i);
        return steps;
    }

    /** Built-in presets, then the user's, then the save button. */
    private void buildPresets() {
        presets.removeAllViews();
        for (Presets.Preset p : Presets.BUILT_IN) presets.addView(presetButton(p));
        for (Presets.Preset p : Presets.user(this)) presets.addView(presetButton(p));
        Button save = chip("＋ 保存");
        save.setOnClickListener(v -> askSave());
        presets.addView(save);
        markPresets();
        presets.setEnabled(Eq.isOn(this));
        for (int i = 0; i < presets.getChildCount(); i++) presets.getChildAt(i).setEnabled(Eq.isOn(this));
    }

    private Button chip(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMaxLines(1);
        b.setMinWidth(dp(64));
        b.setMinimumWidth(dp(64));
        return b;
    }

    private Button presetButton(Presets.Preset p) {
        Button b = chip(p.name);
        b.setTag(p);
        b.setOnClickListener(v -> {
            Eq.setSteps(this, p.steps);
            bands.setSteps(p.steps);
            markPresets();
        });
        if (!p.builtIn) {
            b.setOnLongClickListener(v -> {
                askDelete(p);
                return true;
            });
        }
        return b;
    }

    /**
     * The selected BASS level stands out in the icon's teal. Backgrounds are drawn here rather
     * than tinted: this theme's buttons are not tint-based, and clearing a tint left them white.
     */
    private void markBass() {
        int level = Eq.bass(this);
        for (int i = 0; i < bassRow.getChildCount(); i++) {
            Button b = (Button) bassRow.getChildAt(i);
            boolean on = i == level;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(on ? 0xFF00897B : 0x33808080);
            b.setBackground(bg);
            b.setTextColor(on ? ColorStateList.valueOf(0xFFFFFFFF) : bassText);
            b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    /** Mark the preset the faders currently match, if any. */
    private void markPresets() {
        int[] now = currentSteps();
        for (int i = 0; i < presets.getChildCount(); i++) {
            View v = presets.getChildAt(i);
            if (!(v instanceof Button) || !(v.getTag() instanceof Presets.Preset)) continue;
            Presets.Preset p = (Presets.Preset) v.getTag();
            // A tick, not a colour: the theme's accent is near white on some devices (Xperia).
            ((Button) v).setText(p.matches(now) ? "✓ " + p.name : p.name);
        }
    }

    private void askSave() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("名前");
        name.setText("マイプリセット " + (Presets.user(this).size() + 1));
        name.selectAll();
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("今の値をプリセットとして保存")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> save(name.getText().toString().trim()))
                .setNegativeButton("キャンセル", null)
                .show();
        name.requestFocus();
    }

    private void save(String name) {
        if (name.isEmpty()) {
            toast("名前を入れてください");
            return;
        }
        if (Presets.isBuiltInName(name)) {
            toast("「" + name + "」は最初からあるプリセットの名前なので使えません");
            return;
        }
        int[] steps = currentSteps();
        if (Presets.exists(this, name)) {
            new AlertDialog.Builder(this)
                    .setMessage("「" + name + "」はもうあります。今の値で上書きしますか？")
                    .setPositiveButton("上書き", (d, w) -> {
                        Presets.save(this, name, steps);
                        buildPresets();
                        toast("「" + name + "」を上書きしました");
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
            return;
        }
        Presets.save(this, name, steps);
        buildPresets();
        toast("「" + name + "」を保存しました");
    }

    private void askDelete(Presets.Preset p) {
        new AlertDialog.Builder(this)
                .setMessage("「" + p.name + "」を削除しますか？")
                .setPositiveButton("削除", (d, w) -> {
                    Presets.delete(this, p.name);
                    buildPresets();
                    toast("「" + p.name + "」を削除しました");
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
        global.setChecked(Eq.isGlobal(this));
        resident.setChecked(Eq.isResident(this));
        perOutput.setChecked(Outputs.isEnabled(this));
        // Off: the curve stays visible but greyed and untouchable.
        bands.setEnabled(on);
        for (int i = 0; i < presets.getChildCount(); i++) presets.getChildAt(i).setEnabled(on);
        markPresets();
        for (int i = 0; i < bassRow.getChildCount(); i++) bassRow.getChildAt(i).setEnabled(on);
        bassRow.setAlpha(on ? 1f : 0.3f);
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
        showOutput();
        status.setText(summary());
        showPlayerInfo(!Eq.isGlobal(this) && Eq.isOn(this) && Eq.sessions.isEmpty() && Diag.mediaPlaying(this));
        chainView.setText(chainText());
        probeButton.setVisibility(canDump() ? View.GONE : View.VISIBLE);
        probeButton.setEnabled(!probing);
        diagView.setText(recentEvents());
    }

    /** Which output the settings are for; on a change of output, show that output's values. */
    private void showOutput() {
        String key = Outputs.isEnabled(this) ? Outputs.activeKey(this) : null;
        outputView.setVisibility(key == null ? View.GONE : View.VISIBLE);
        if (key != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder("出力: ");
            bold(sb, Outputs.activeLabel(this));
            sb.append(" の設定（出力が変わると自動で切り替わります）");
            outputView.setText(sb);
        }
        // Also on coming back from 機器プリセット: the current output's values may have been set there.
        if (key != null && shownOutput != null && !java.util.Arrays.equals(bands.steps(), currentSteps())) {
            bands.setSteps(currentSteps());
            markPresets();
        }
        if (key != null && !key.equals(shownOutput) && shownOutput != null) {
            bands.setSteps(currentSteps());
            markPresets();
            markBass();
        }
        shownOutput = key;
    }

    // --- Diagnostics without DUMP ----------------------------------------------------------

    /** Probe every open session and the whole-output mix, off the main thread. */
    private void probe(Runnable then) {
        if (probing) return;
        probing = true;
        Map<Integer, String> targets = new LinkedHashMap<>(Eq.sessions);
        Map<Integer, java.util.UUID> ours = new LinkedHashMap<>();
        Map<Integer, Boolean> working = new LinkedHashMap<>();
        for (int id : targets.keySet()) {
            ours.put(id, Eq.typeOn(id));
            working.put(id, Eq.working(id));
        }
        java.util.UUID oursMix = Eq.typeOn(Eq.GLOBAL);
        boolean workingMix = Eq.working(Eq.GLOBAL);
        refresh();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, String> e : targets.entrySet()) {
                String found = Probe.run(e.getKey(), ours.get(e.getKey()), working.get(e.getKey()));
                sb.append(label(e.getValue())).append(" の再生:\n")
                        .append(found.isEmpty() ? "（ほかの効果なし）" : found).append("\n\n");
            }
            String mix = Probe.run(Eq.GLOBAL, oursMix, workingMix);
            sb.append("全体（全アプリ共通）:\n").append(mix.isEmpty() ? "（ほかの効果なし）" : mix);
            String text = sb.toString();
            main.post(() -> {
                probeText = text;
                probing = false;
                if (then != null) then.run();
                // Only changes are worth a line: the record holds 40, and receipts matter more.
                Diag.noteIfChanged(this, "probe", "他の効果の調査: " + text.replace("\n\n", " / ").replace('\n', ' '));
                if (resumed) refresh();
            });
        }).start();
    }

    private String recentEvents() {
        List<String> ev = Diag.events(this);
        StringBuilder sb = new StringBuilder();
        String test = Diag.selfTestResult(this);
        if (test != null) sb.append("受信テスト: ").append(test).append("\n\n");
        sb.append("最近の出来事（新しい順）:");
        if (ev.isEmpty()) sb.append("\n（まだ何も起きていません）");
        for (int i = ev.size() - 1; i >= Math.max(0, ev.size() - 6); i--) sb.append('\n').append(ev.get(i));
        return sb.toString();
    }

    /** The screen's state in plain text, for the report. */
    private String stateText() {
        StringBuilder sb = new StringBuilder();
        sb.append("ikora: ").append(Eq.isOn(this) ? "ON" : "OFF").append('\n');
        sb.append("モード: ").append(Eq.isGlobal(this) ? "全体" : "再生ごと").append('\n');
        sb.append("常駐して待つ: ").append(Eq.isResident(this) ? "ON" : "OFF").append('\n');
        sb.append("出力機器ごとに覚える: ").append(Outputs.isEnabled(this)
                ? "ON（今: " + Outputs.activeLabel(this) + "）" : "OFF").append('\n');
        sb.append("常駐サービス: ").append(serviceRunning() ? "動いている" : "止まっている").append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.append("通知の許可: ").append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED ? "あり" : "なし").append('\n');
        }
        if (Eq.isGlobal(this)) {
            sb.append("全体の効果: ").append(Eq.working(Eq.GLOBAL) ? "効いている（" + Eq.engineOn(Eq.GLOBAL) + "）"
                    : Eq.effects.containsKey(Eq.GLOBAL) ? "付いているが制御権なし" : "付いていない");
            String err = Eq.errors.get(Eq.GLOBAL);
            if (err != null) sb.append(" / DynamicsProcessing: ").append(err);
            sb.append('\n');
        }
        sb.append("知らせを受けたことがある: ").append(Diag.everReceived(this) ? "はい" : "いいえ").append('\n');
        sb.append("電池の最適化: ").append(needsBatteryExemption() ? "あり（常駐できない）" : "なし/不要").append('\n');
        sb.append("DUMP 許可: ").append(canDump() ? "あり" : "なし").append('\n');
        sb.append("DynamicsProcessing を持つ端末: ").append(Eq.deviceHasDp() ? "はい" : "いいえ").append('\n');
        sb.append("イコライザアプリ: ");
        for (int i = 1; i < picker.getChildCount(); i++) {
            sb.append(i > 1 ? ", " : "").append(((RadioButton) picker.getChildAt(i)).getText());
        }
        sb.append('\n');
        if (Eq.sessions.isEmpty()) sb.append("再生中のセッション: なし\n");
        for (Map.Entry<Integer, String> e : Eq.sessions.entrySet()) {
            int id = e.getKey();
            sb.append("session ").append(id).append(" (").append(e.getValue()).append("): ")
                    .append(Eq.working(id) ? "効いている"
                            : Eq.effects.containsKey(id) ? "付いているが制御権なし" : "付いていない");
            String err = Eq.errors.get(id);
            if (err != null) sb.append(" / 理由: ").append(err);
            sb.append('\n');
        }
        sb.append('\n').append(status.getText());
        return sb.toString();
    }

    private boolean serviceRunning() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.RunningServiceInfo r : am.getRunningServices(10)) {
            if (r.service.getClassName().equals(EqService.class.getName())) return r.foreground;
        }
        return false;
    }

    private void sendReport() {
        // The probe touches the chain, so it no longer runs on every open: do it now if needed.
        if (!canDump() && probeText == null) {
            probe(this::sendReport);
            return;
        }
        String text = Diag.report(this, stateText(), probeText);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "ikora 診断情報")
                .putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "診断情報を送る"));
    }

    /** One line per open session: is ikora actually shaping it, and if not, who is. */
    private CharSequence summary() {
        if (Eq.isGlobal(this)) return globalSummary();
        if (Eq.sessions.isEmpty()) {
            if (Diag.mediaPlaying(this)) {
                // The one case a tester cannot see: the player plays but never tells us.
                if (!notedSilentPlayer) {
                    notedSilentPlayer = true;
                    Diag.note(this, "音楽が再生中なのに、音楽アプリからの知らせが無い");
                }
                SpannableStringBuilder sb = new SpannableStringBuilder();
                bold(sb, "音楽が鳴っていますが、音楽アプリから ikora への知らせが届いていません。");
                sb.append('\n').append(getString(nextStep()));
                return sb;
            }
            if (Diag.everReceived(this)) return getString(R.string.idle);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            bold(sb, "音楽アプリからの知らせを、まだ一度も受け取っていません。");
            sb.append('\n').append(getString(R.string.never_received))
                    .append('\n').append(getString(nextStep()));
            return sb;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Map.Entry<Integer, String> e : Eq.sessions.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            String player = label(e.getValue());
            if (!Eq.isOn(this)) {
                sb.append("ikora はオフ（").append(player).append(" を再生中）");
            } else if (Eq.working(e.getKey()) && !Diag.mediaPlaying(this)) {
                // Attached (possibly restored after an update) but nothing sounds right now.
                bold(sb, "✓ " + player + " に付いています（再生を待っています）");
            } else if (Eq.working(e.getKey())) {
                bold(sb, "✓ " + player + " に ikora が効いています");
            } else {
                bold(sb, "✗ " + player + " に ikora は効いていません");
                String by = rival(e.getKey());
                sb.append('\n').append(by == null
                        ? "ほかのイコライザが優先されています。そちらを止めると自動で効きます。"
                        : by + " が優先されています。そちらを止めると自動で効きます。");
                String err = Eq.errors.get(e.getKey());
                if (err != null) sb.append("\n（理由: ").append(err).append("）");
                else if (Eq.effects.containsKey(e.getKey())) {
                    sb.append("\n（理由: ikora が付けたあとで、別のアプリが優先度の高い効果を付けました）");
                }
            }
        }
        return sb;
    }

    /**
     * What to try when no broadcast arrives. On the AQUOS R8 the player did send, but a
     * stopped ikora was not woken for it: staying resident fixed it. Whole-output mode is
     * for players that never send at all.
     */
    private int nextStep() {
        return Eq.isResident(this) ? R.string.restart_player : R.string.try_resident;
    }

    /** The app that last announced a session, if it is still installed; else null. */
    private String lastPlayer() {
        String pkg = Eq.prefs(this).getString("lastPlayer", null);
        if (pkg == null) return null;
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return pkg;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Music plays but ikora does not know the session: the player announced it while ikora
     * could not hear (force-stopped, or replaced by an update). Only a new session from the
     * player helps, so offer the shortest way to force-stop it.
     */
    private void showPlayerInfo(boolean missed) {
        String pkg = missed && Eq.isResident(this) ? lastPlayer() : null;
        playerInfo.setVisibility(pkg == null ? View.GONE : View.VISIBLE);
        if (pkg != null) playerInfo.setText(label(pkg) + " のアプリ情報を開く（強制停止へ）");
    }

    private CharSequence globalSummary() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!Eq.isOn(this)) {
            sb.append("ikora はオフ（全体モード）");
        } else if (Eq.working(Eq.GLOBAL)) {
            bold(sb, "✓ 全体（すべての音）に ikora が効いています");
            sb.append("\n").append(Eq.engineOn(Eq.GLOBAL));
        } else {
            bold(sb, "✗ 全体に ikora は効いていません");
            String err = Eq.errors.get(Eq.GLOBAL);
            sb.append('\n').append(Eq.effects.containsKey(Eq.GLOBAL)
                    ? "ほかのアプリが優先されています。"
                    : "付けられませんでした" + (err == null ? "。" : "（" + err + "）。"));
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
            sb.clear();
            bold(sb, "ほかのアプリの効果（種類だけ）");
            sb.append('\n').append(probing ? "調べています…" : probeText == null ? "まだ調べていません。" : probeText);
            sb.append("\n\n持ち主のアプリ名と処理の順番まで見るには、PC から一度だけ次を実行します:\n")
                    .append(Chain.GRANT);
            return sb;
        }
        if (chain == null) {
            sb.append("\n読み込み中…");
            return sb;
        }
        if (Eq.isGlobal(this)) {
            // One whole-output chain per output thread; show the ones carrying effects.
            for (Chain.Session s : chain.sessions) {
                if (s.id != Eq.GLOBAL || s.effects.isEmpty()) continue;
                sb.append("\n\n");
                bold(sb, "全体（すべての音）");
                sb.append("  → ").append(device(s.device));
                lines(sb, s);
            }
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
        if (n.contains("DynamicsProcessing")) return Probe.DYNAMICS;
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
