package com.ikoralite;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Children left to right, wrapping onto a new row when one is full (the preset buttons). */
final class Flow extends ViewGroup {

    Flow(Context c) {
        super(c);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int child = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
        int x = 0, y = 0, row = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(child, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + v.getMeasuredWidth() > width) {
                x = 0;
                y += row;
                row = 0;
            }
            x += v.getMeasuredWidth();
            row = Math.max(row, v.getMeasuredHeight());
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                resolveSize(y + row + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, row = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + w > width) {
                x = 0;
                y += row;
                row = 0;
            }
            v.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
            x += w;
            row = Math.max(row, h);
        }
    }
}
