package dev.augie.wend;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BACKGROUND = Color.rgb(17, 29, 42);
    static final int PANEL = Color.rgb(28, 45, 64);
    static final int TEXT = Color.rgb(241, 246, 251);
    static final int MUTED = Color.rgb(168, 185, 204);
    static final int ACCENT = Color.rgb(132, 191, 255);
    /** Pressed-state ripple, an accent tint. */
    static final int RIPPLE = 0x3384BFFF;
    /** A settings card on sheet glass, and the hairlines between its rows. */
    static final int CARD = 0x14FFFFFF;
    static final int DIVIDER = 0x17FFFFFF;
    /** Controls that cannot be used right now keep their place at this opacity. */
    static final float DISABLED_ALPHA = 0.38f;

    enum Icon { APPS, SETTINGS, PLAY, PAUSE, PREVIOUS, NEXT, CLOSE, BACK, ADD, REMOVE, UP, DOWN, FORWARD, FULLSCREEN }

    private Ui() { }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static GradientDrawable wallpaper() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF283B45, 0xFF222C36, 0xFF19212A});
    }

    static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    static Typeface medium() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    /** A rounded group of settings rows that clips their press ripples to its corners. */
    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(surface(context, CARD, 14));
        card.setClipToOutline(true);
        return card;
    }

    /** A small muted heading above a card, aligned with the card's text. */
    static TextView sectionLabel(Context context, String label) {
        TextView view = text(context, label, 14, MUTED);
        view.setTypeface(medium());
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPaddingRelative(dp(context, 16), 0, dp(context, 16), 0);
        return view;
    }

    /** A press ripple clipped to a visible shape. */
    static RippleDrawable pressable(Drawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null);
    }

    /**
     * A press ripple over a transparent background, clipped to rounded corners. The mask must
     * be explicit: Android masks a ripple by its content, and transparent content hides it.
     */
    static RippleDrawable ripple(Context context, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, surface(context, Color.WHITE, radius));
    }

    static TextView action(Context context, String label, Runnable action) {
        TextView button = text(context, label, 16, ACCENT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setBackground(pressable(surface(context, PANEL, 12)));
        button.setOnClickListener(v -> action.run());
        button.setFocusable(true);
        button.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        return button;
    }

    static ImageButton iconButton(Context context, Icon icon, String label, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setImageDrawable(new Symbol(icon, TEXT));
        button.setContentDescription(label);
        button.setBackground(ripple(context, 16));
        int padding = dp(context, 12);
        button.setPadding(padding, padding, padding, padding);
        button.setMinimumWidth(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    static final class Symbol extends Drawable {
        private final Icon icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Symbol(Icon icon, int color) {
            this.icon = icon;
            // Directional glyphs point the other way in right-to-left layouts.
            setAutoMirrored(icon == Icon.BACK || icon == Icon.FORWARD);
            paint.setColor(color);
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            float size = Math.min(getBounds().width(), getBounds().height());
            canvas.translate(getBounds().centerX() - size / 2, getBounds().centerY() - size / 2);
            canvas.scale(size / 24, size / 24);
            if (isAutoMirrored() && getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) canvas.scale(-1, 1, 12, 12);
            paint.setStyle(Paint.Style.FILL);
            switch (icon) {
                case BACK:
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(19, 12, 5, 12, paint);
                    polyline(canvas, 11, 6, 5, 12, 11, 18); break;
                case FORWARD:
                    paint.setStyle(Paint.Style.STROKE);
                    polyline(canvas, 9, 6, 15, 12, 9, 18); break;
                case UP:
                    paint.setStyle(Paint.Style.STROKE);
                    polyline(canvas, 6, 15, 12, 9, 18, 15); break;
                case DOWN:
                    paint.setStyle(Paint.Style.STROKE);
                    polyline(canvas, 6, 9, 12, 15, 18, 9); break;
                case ADD:
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(12, 5, 12, 19, paint);
                    canvas.drawLine(5, 12, 19, 12, paint); break;
                case REMOVE:
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(12, 12, 8.5f, paint);
                    canvas.drawLine(8, 12, 16, 12, paint); break;
                case CLOSE:
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint); break;
                case FULLSCREEN:
                    paint.setStyle(Paint.Style.STROKE);
                    polyline(canvas, 4, 9, 4, 4, 9, 4);
                    polyline(canvas, 15, 4, 20, 4, 20, 9);
                    polyline(canvas, 20, 15, 20, 20, 15, 20);
                    polyline(canvas, 9, 20, 4, 20, 4, 15); break;
                case PLAY: triangle(canvas, 7, 4, 20, 12, 7, 20); break;
                case PAUSE:
                    canvas.drawRoundRect(6, 4, 10, 20, 1, 1, paint);
                    canvas.drawRoundRect(14, 4, 18, 20, 1, 1, paint); break;
                case NEXT:
                    triangle(canvas, 4, 5, 15, 12, 4, 19);
                    canvas.drawRoundRect(17, 5, 20, 19, 1, 1, paint); break;
                case PREVIOUS:
                    triangle(canvas, 20, 5, 9, 12, 20, 19);
                    canvas.drawRoundRect(4, 5, 7, 19, 1, 1, paint); break;
                case APPS:
                    for (int x = 4; x <= 16; x += 6)
                        for (int y = 4; y <= 16; y += 6)
                            canvas.drawRoundRect(x, y, x + 3, y + 3, 1, 1, paint);
                    break;
                case SETTINGS:
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(3, 7, 21, 7, paint);
                    canvas.drawLine(3, 17, 21, 17, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(8, 7, 3, paint);
                    canvas.drawCircle(16, 17, 3, paint); break;
            }
            canvas.restore();
        }

        private void polyline(Canvas canvas, float a, float b, float c, float d, float e, float f) {
            Path path = new Path();
            path.moveTo(a, b); path.lineTo(c, d); path.lineTo(e, f);
            canvas.drawPath(path, paint);
        }

        private void triangle(Canvas canvas, float a, float b, float c, float d, float e, float f) {
            Path path = new Path();
            path.moveTo(a, b); path.lineTo(c, d); path.lineTo(e, f); path.close();
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }
}
