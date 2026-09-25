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
 */
final class BandsView extends View {

    interface OnChange {
        void onChange(int band, int step);
    }

    private final int[] steps = new int[Eq.N];
    private OnChange onChange;
    private int dragging = -1;
    private float downY;
    private int downStep;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    }

    void setOnChange(OnChange l) {
        onChange = l;
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

    private float colX(int i) {
        float w = getWidth() / (float) Eq.N;
        return w * i + w / 2;
    }

    private float yOf(int step) {
        return top() + (bottom() - top()) * (Eq.STEPS - step) / (2f * Eq.STEPS);
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
                dragging = Math.max(0, Math.min(Eq.N - 1, (int) (e.getX() / (getWidth() / (float) Eq.N))));
                downY = e.getY();
                downStep = steps[dragging];
                // Keep a parent ScrollView from stealing the vertical drag.
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging >= 0) {
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
