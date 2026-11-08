package dev.augie.roadhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.LinearLayout;

/** A vertical column that never grows wider than a readable measure; center it in its parent. */
@SuppressLint("ViewConstructor")
final class MaxWidthColumn extends LinearLayout {
    private final int maxWidth;

    MaxWidthColumn(Context context, int maxWidthDp) {
        super(context);
        maxWidth = Ui.dp(context, maxWidthDp);
        setOrientation(VERTICAL);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED || width > maxWidth) {
            widthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
