package dev.augie.roadhome;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutually exclusive choices in a recessed track. A raised thumb slides behind the chosen
 * segment, so the selection reads at a glance. Each segment is a 48 dp radio target.
 */
@SuppressLint("ViewConstructor")
final class SegmentedControl<T> extends LinearLayout {
    interface Listener<T> { void onSelected(T value); }

    /** A value and its visible label. */
    static final class Option<T> {
        final T value;
        final String label;

        Option(T value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    // A strong ease-out: the thumb answers the tap at once and settles softly.
    private static final TimeInterpolator EASE_OUT = new PathInterpolator(0.23f, 1, 0.32f, 1);
    private static final int SLIDE_MS = 220;
    private static final int INSET_DP = 3;

    private final List<Option<T>> options;
    private final List<TextView> segments = new ArrayList<>();
    private final Listener<T> listener;
    private final GradientDrawable thumb = new GradientDrawable();
    private final int inset;
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private int selected;
    private float thumbStart;
    private float thumbEnd;
    private ValueAnimator slide;

    SegmentedControl(Context context, List<Option<T>> options, T initial, Listener<T> listener) {
        super(context);
        this.options = options;
        this.listener = listener;
        inset = Ui.dp(context, INSET_DP);
        setOrientation(HORIZONTAL);
        setWillNotDraw(false);
        setPadding(inset, inset, inset, inset);
        setBackground(Ui.surface(context, 0x38000000, 12));
        thumb.setColor(0x2EFFFFFF);
        thumb.setCornerRadius(Ui.dp(context, 12 - INSET_DP));
        thumb.setStroke(1, 0x1AFFFFFF);
        for (int i = 0; i < options.size(); i++) {
            int index = i;
            TextView segment = Ui.text(context, options.get(i).label, 16, Ui.MUTED);
            segment.setGravity(Gravity.CENTER);
            segment.setSingleLine(true);
            segment.setEllipsize(TextUtils.TruncateAt.END);
            int padding = Ui.dp(context, 12);
            segment.setPadding(padding, 0, padding, 0);
            segment.setBackground(Ui.ripple(context, 12 - INSET_DP));
            segment.setOnClickListener(v -> choose(index));
            segment.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.RadioButton");
                    info.setCheckable(true);
                    info.setChecked(index == selected);
                }
            });
            segments.add(segment);
            addView(segment, new LayoutParams(0, -1, 1));
        }
        selected = indexOf(initial);
        style();
    }

    /** Shows {@code value} as chosen without notifying the listener. */
    void select(T value) {
        int index = indexOf(value);
        if (index == selected) return;
        selected = index;
        style();
        slideThumb();
    }

    private void choose(int index) {
        if (index == selected) return;
        selected = index;
        style();
        slideThumb();
        listener.onSelected(options.get(index).value);
    }

    private int indexOf(T value) {
        for (int i = 0; i < options.size(); i++) if (options.get(i).value.equals(value)) return i;
        throw new IllegalArgumentException(value + " is not one of " + options.size() + " segment options");
    }

    private void style() {
        for (int i = 0; i < segments.size(); i++) {
            TextView segment = segments.get(i);
            boolean chosen = i == selected;
            segment.setTextColor(chosen ? Ui.TEXT : Ui.MUTED);
            segment.setTypeface(chosen ? Ui.medium() : regular);
            segment.setSelected(chosen);
        }
    }

    private void slideThumb() {
        if (slide != null) slide.cancel();
        View target = segments.get(selected);
        if (!isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            thumbStart = target.getLeft();
            thumbEnd = target.getRight();
            invalidate();
            return;
        }
        float fromStart = thumbStart;
        float fromEnd = thumbEnd;
        slide = ValueAnimator.ofFloat(0, 1);
        slide.setDuration(SLIDE_MS);
        slide.setInterpolator(EASE_OUT);
        slide.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            thumbStart = fromStart + (target.getLeft() - fromStart) * t;
            thumbEnd = fromEnd + (target.getRight() - fromEnd) * t;
            invalidate();
        });
        slide.start();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (slide != null && slide.isRunning()) return;
        View target = segments.get(selected);
        thumbStart = target.getLeft();
        thumbEnd = target.getRight();
    }

    @Override protected void onDraw(Canvas canvas) {
        thumb.setBounds(Math.round(thumbStart), inset, Math.round(thumbEnd), getHeight() - inset);
        thumb.draw(canvas);
    }
}
