package dev.augie.wend;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Fixed-size cells in reading order. Hidden tiles close their gap, so search never rebuilds views. */
final class AppGrid extends ViewGroup {
    private final int columns;
    private final int cellHeight;

    AppGrid(Context context, int columns, int cellHeight) {
        super(context);
        this.columns = columns;
        this.cellHeight = cellHeight;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int cell = MeasureSpec.makeMeasureSpec(cellWidth(width), MeasureSpec.EXACTLY);
        int height = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY);
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(cell, height);
            visible++;
        }
        int rows = (visible + columns - 1) / columns;
        int content = rows == 0 ? 0 : rows * cellHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(content, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = cellWidth(right - left);
        int index = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int x = getPaddingLeft() + index % columns * width;
            int y = getPaddingTop() + index / columns * cellHeight;
            child.layout(x, y, x + width, y + cellHeight);
            index++;
        }
    }

    @Override public boolean shouldDelayChildPressedState() { return false; }

    private int cellWidth(int width) { return (width - getPaddingLeft() - getPaddingRight()) / columns; }
}
