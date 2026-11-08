package dev.augie.wend;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import java.util.EnumMap;
import java.util.Map;

/**
 * Draws what a floating window's overlay shows above the live display: Android 10's PiP menu (a dim
 * scrim with its controls) and, while the window is dragged, the dismiss target at the bottom. The
 * display's surface sits below this view's window buffer, so these draw over it.
 */
final class FloatingChrome extends View {
    /** Android 10's PiP menu background, 30% black. */
    private static final int SCRIM = 0x4D000000;
    private static final int TARGET = 0x99000000;
    private static final int FADE_MS = 125;
    private final float density;
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Ui.Icon, Ui.Symbol> icons = new EnumMap<>(Ui.Icon.class);
    private final Rect window = new Rect();
    private final RectF rounded = new RectF();
    private final ValueAnimator menuFade = ValueAnimator.ofFloat(0, 1);
    private final ValueAnimator targetFade = ValueAnimator.ofFloat(0, 1);
    private float radius;
    /** The menu's and the dismiss target's current opacity. */
    private float menuAlpha, targetAlpha;
    private FloatingMenu.Playback playback = FloatingMenu.Playback.NONE;
    private boolean menuShown;
    private boolean targetShown;

    FloatingChrome(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.TRANSPARENT);
        scrim.setColor(SCRIM);
        target.setColor(TARGET);
        menuFade.setDuration(FADE_MS);
        menuFade.addUpdateListener(value -> { menuAlpha = (float) value.getAnimatedValue(); invalidate(); });
        targetFade.setDuration(FADE_MS);
        targetFade.addUpdateListener(value -> { targetAlpha = (float) value.getAnimatedValue(); invalidate(); });
    }

    boolean menuShown() { return menuShown; }

    /** Fades the menu in over the window at {@code bounds}, or out; at once if not {@code animate}. */
    void menu(boolean shown, Rect bounds, float cornerRadius, boolean animate) {
        if (bounds != null) window.set(bounds);
        radius = cornerRadius;
        if (shown == menuShown && (animate || !menuFade.isRunning())) { invalidate(); return; }
        menuShown = shown;
        menuFade.cancel();
        if (animate) fade(menuFade, menuAlpha, shown);
        else { menuAlpha = shown ? 1 : 0; invalidate(); }
    }

    /** What the playback control offers, redrawn at once if the menu shows. */
    void playback(FloatingMenu.Playback next) {
        if (playback == next) return;
        playback = next;
        invalidate();
    }

    void dismissTarget(boolean shown) {
        if (shown == targetShown) return;
        targetShown = shown;
        targetFade.cancel();
        fade(targetFade, targetAlpha, shown);
    }

    private static void fade(ValueAnimator fade, float from, boolean in) {
        fade.setFloatValues(from, in ? 1 : 0);
        fade.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float menu = menuAlpha;
        if (menu > 0 && !window.isEmpty()) {
            rounded.set(window);
            scrim.setAlpha(Math.round(Color.alpha(SCRIM) * menu));
            canvas.drawRoundRect(rounded, radius, radius, scrim);
            for (FloatingMenu.Control control : FloatingMenu.controls(playback)) {
                Rect at = FloatingMenu.target(control, window, density);
                int icon = Math.round((control == FloatingMenu.Control.CLOSE ? 20 : 28) * density);
                draw(canvas, iconFor(control), at.centerX(), at.centerY(), icon, menu);
            }
        }
        float dismiss = targetAlpha;
        if (dismiss > 0) {
            float size = 56 * density;
            float x = getWidth() / 2f, y = getHeight() - 24 * density - size / 2;
            target.setAlpha(Math.round(Color.alpha(TARGET) * dismiss));
            canvas.drawCircle(x, y, size / 2, target);
            draw(canvas, Ui.Icon.CLOSE, Math.round(x), Math.round(y), Math.round(24 * density), dismiss);
        }
    }

    private Ui.Icon iconFor(FloatingMenu.Control control) {
        switch (control) {
            case CLOSE: return Ui.Icon.CLOSE;
            case FULLSCREEN: return Ui.Icon.FULLSCREEN;
            default: return playback.icon();
        }
    }

    private void draw(Canvas canvas, Ui.Icon icon, int centerX, int centerY, int size, float alpha) {
        Ui.Symbol symbol = icons.get(icon);
        if (symbol == null) {
            symbol = new Ui.Symbol(icon, Color.WHITE);
            icons.put(icon, symbol);
        }
        symbol.setBounds(centerX - size / 2, centerY - size / 2, centerX + size / 2, centerY + size / 2);
        symbol.setAlpha(Math.round(255 * alpha));
        symbol.draw(canvas);
    }

    void release() {
        menuFade.cancel();
        targetFade.cancel();
    }
}
