package com.ikoralite;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Seven vertical faders drawn as one view. A whole column is the grip: dragging anywhere
 * in it moves that band's knob by the same distance, so there is no thin bar to aim for.
 * The knobs are joined by a line, so the curve itself is visible.
 *
 * Left of them, set apart by a rule and drawn in another colour with a square knob, is the
 * "全体" fader: it sits at the bands' average and moves every band by the same amount, so
 * the curve keeps its shape. It stops where any band would pass ±12 dB, rather than flatten
 * the curve against the end.
 */
final class BandsView extends View {

    interface OnChange {
        void onChange(int band, int step);
    }

    /** The "全体" fader moved every band at once. */
    interface OnShift {
        void onShift(int[] steps);
    }

    /** Column of the "全体" fader; the bands take columns 1..N. */
    private static final int MASTER = -2;

    private final int[] steps = new int[Eq.N];
    private OnChange onChange;
    private OnShift onShift;
    private int dragging = -1;
    private float downY;
    private int downStep;
    /** The bands when the "全体" drag began, and how far they may move up and down. */
    private final int[] downSteps = new int[Eq.N];
    private int shiftUp, shiftDown;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint masterTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint masterKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint masterText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final float dp;

    BandsView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        TypedArray a = c.obtainStyledAttributes(new int[]{
                android.R.attr.colorAccent, android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        int accent = a.getColor(0, 0xFF00897B);
        int primary = a.getColor(1, 0xFF000000);
        int secondary = a.getColor(2, 0xFF757575);
        a.recycle();

        track.setColor(secondary);
        track.setAlpha(70);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(10 * dp);
        fill.setColor(accent);
        fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeWidth(10 * dp);
        knob.setColor(accent);
        curve.setColor(accent);
        curve.setAlpha(140);
        curve.setStyle(Paint.Style.STROKE);
        curve.setStrokeWidth(2 * dp);
        zero.setColor(secondary);
        zero.setAlpha(120);
        zero.setStrokeWidth(1 * dp);
        zero.setPathEffect(new DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
        text.setColor(primary);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * dp * getResources().getConfiguration().fontScale);
        // Amber, apart from the accent: this one is not a band.
        masterTrack.setColor(0xFFFFA000);
        masterTrack.setAlpha(60);
        masterTrack.setStrokeWidth(18 * dp);
        masterKnob.setColor(0xFFFFA000);
        masterText.set(text);
        masterText.setColor(0xFFE08A00);
        masterText.setFakeBoldText(true);
        rule.setColor(secondary);
        rule.setAlpha(90);
        rule.setStrokeWidth(1 * dp);
    }

    void setOnShift(OnShift l) {
        onShift = l;
    }

    void setOnChange(OnChange l) {
        onChange = l;
    }

    int[] steps() {
        return steps.clone();
    }

    void setSteps(int[] s) {
        System.arraycopy(s, 0, steps, 0, Eq.N);
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.3f);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(300 * dp));
    }

    // Layout: value labels on top, faders in the middle, frequency labels at the bottom.
    private float labelH() {
        return text.getTextSize() * 1.8f;
    }

    private float top() {
        return labelH() + 16 * dp;
    }

    private float bottom() {
        return getHeight() - labelH() - 16 * dp;
    }

    private float colW() {
        return getWidth() / (float) (Eq.N + 1);
    }

    /** Centre of band i, or of the "全体" fader for MASTER. */
    private float colX(int i) {
        int col = i == MASTER ? 0 : i + 1;
        return colW() * col + colW() / 2;
    }

    /** The bands' average, in (fractional) steps: where the "全体" knob sits. */
    private float mean() {
        float sum = 0;
        for (int s : steps) sum += s;
        return sum / Eq.N;
    }

    private float yAt(float step) {
        return top() + (bottom() - top()) * (Eq.STEPS - step) / (2f * Eq.STEPS);
    }

    private float yOf(int step) {
        return yAt(step);
    }

    @Override
    protected void onDraw(Canvas c) {
        float y0 = yOf(0);
        c.drawLine(0, y0, getWidth(), y0, zero);

        path.reset();
        for (int i = 0; i < Eq.N; i++) {
            float x = colX(i), y = yOf(steps[i]);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        c.drawPath(path, curve);

        // The "全体" fader: wide pale track, square knob, amber, behind a rule.
        float mx = colX(MASTER), my = yAt(mean()), k = 12 * dp;
        c.drawLine(colW(), top() - 8 * dp, colW(), bottom() + 8 * dp, rule);
        c.drawLine(mx, top(), mx, bottom(), masterTrack);
        c.drawRoundRect(mx - k * 1.3f, my - k * 0.8f, mx + k * 1.3f, my + k * 0.8f, 3 * dp, 3 * dp, masterKnob);
        float m = Math.round(mean()) / 2f;
        c.drawText(m == 0 ? "0" : String.format(Locale.ROOT, "%+.1f", m), mx, labelH(), masterText);
        c.drawText("全体", mx, getHeight() - labelH() / 2, masterText);

        for (int i = 0; i < Eq.N; i++) {
            float x = colX(i), y = yOf(steps[i]);
            c.drawLine(x, top(), x, bottom(), track);
            c.drawLine(x, y0, x, y, fill);
            c.drawCircle(x, y, 13 * dp, knob);

            String v = steps[i] == 0 ? "0" : String.format(Locale.ROOT, "%+.1f", steps[i] / 2f);
            c.drawText(v, x, labelH(), text);
            int f = Eq.FREQ[i];
            String hz = f >= 1000 ? (f % 1000 == 0 ? f / 1000 + "k" : String.format(Locale.ROOT, "%.1fk", f / 1000f)) : String.valueOf(f);
            c.drawText(hz, x, getHeight() - labelH() / 2, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The knob moves with the finger, relative to where it was: a tap alone
                // changes nothing, so the column can be grabbed anywhere without a jump.
                int col = Math.max(0, Math.min(Eq.N, (int) (e.getX() / colW())));
                dragging = col == 0 ? MASTER : col - 1;
                downY = e.getY();
                if (dragging == MASTER) {
                    System.arraycopy(steps, 0, downSteps, 0, Eq.N);
                    int max = -Eq.STEPS, min = Eq.STEPS;
                    for (int s : steps) {
                        max = Math.max(max, s);
                        min = Math.min(min, s);
                    }
                    shiftUp = Eq.STEPS - max;
                    shiftDown = Eq.STEPS + min;
                } else {
                    downStep = steps[dragging];
                }
                // Keep a parent ScrollView from stealing the vertical drag.
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == MASTER) {
                    float perStep = (bottom() - top()) / (2f * Eq.STEPS);
                    int d = Math.round((downY - e.getY()) / perStep);
                    d = Math.max(-shiftDown, Math.min(shiftUp, d));
                    if (downSteps[0] + d != steps[0]) {
                        for (int i = 0; i < Eq.N; i++) steps[i] = downSteps[i] + d;
                        invalidate();
                        if (onShift != null) onShift.onShift(steps.clone());
                    }
                } else if (dragging >= 0) {
                    float perStep = (bottom() - top()) / (2f * Eq.STEPS);
                    int s = downStep + Math.round((downY - e.getY()) / perStep);
                    s = Math.max(-Eq.STEPS, Math.min(Eq.STEPS, s));
                    if (s != steps[dragging]) {
                        steps[dragging] = s;
                        invalidate();
                        if (onChange != null) onChange.onChange(dragging, s);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(e);
    }
}
