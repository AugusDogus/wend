package dev.augie.wend;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.EnumMap;
import java.util.Map;

/**
 * Draws what a floating window's overlay shows above the live display: floating Maps' next-turn card
 * ({@link NavigationCard}), Android 10's PiP menu (a dim scrim with its controls) and, while the
 * window is dragged, the dismiss target at the bottom. The
 * display's surface sits below this view's window buffer, so these draw over it.
 */
final class FloatingChrome extends View {
    /** Android 10's PiP menu background, 30% black. */
    private static final int SCRIM = 0x4D000000;
    private static final int TARGET = 0x99000000;
    private static final int FADE_MS = 125;
    /** A held still gives way to the live display early in the window's growth. */
    private static final int STILL_FADE_MS = 120;
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
    private final Paint stillPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path stillClip = new Path();
    private final Rect stillSource = new Rect();
    private final Rect stillAt = new Rect();
    private final ValueAnimator stillFade = ValueAnimator.ofFloat(1, 0);
    /** A frozen frame of the display held over the window, or null. */
    private Bitmap still;
    private float stillAlpha;
    /** Darkens the arrow's square, as Maps' own PiP card does. */
    private static final int ARROW_SHADE = 0x33000000;
    /** What Maps' own PiP fills its window with at the destination, in its day and night colors alike. */
    private static final int ARRIVED = 0xff1e8e3e;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint primary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint secondary = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path cardClip = new Path();
    private final Rect cardWindow = new Rect();
    private NavigationCard card;
    private Drawable arrow;
    private float cardRadius, cardAlpha;

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
        primary.setTypeface(Ui.medium());
        primary.setColor(Color.WHITE);
        secondary.setColor(Color.WHITE);
        stillFade.setDuration(STILL_FADE_MS);
        stillFade.addUpdateListener(value -> { stillAlpha = (float) value.getAnimatedValue(); invalidate(); });
        stillFade.addListener(new AnimatorListenerAdapter() {
            // Dropped, not recycled: RenderThread may still draw it this frame.
            @Override public void onAnimationEnd(Animator animation) { if (stillAlpha == 0) still = null; }
        });
    }

    boolean menuShown() { return menuShown; }

    /** The next turn to draw across the window's bottom, or null for none. */
    void turnCard(NavigationCard next) {
        card = next;
        arrow = next == null || next.arrow == null ? null : next.arrow.loadDrawable(getContext());
        // Maps draws its arrows in one color; the card shows them white on its own color.
        if (arrow != null) arrow.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    /**
     * Where the window is drawn now, and how much of its turn card shows, from 0 to 1.
     * @return whether the next frame draws the turn card there
     */
    boolean frame(Rect bounds, float cornerRadius, float turnCardOpacity) {
        cardWindow.set(bounds);
        cardRadius = cornerRadius;
        cardAlpha = turnCardOpacity;
        if (card == null) return false;
        invalidate();
        return cardAlpha > 0 && !cardWindow.isEmpty();
    }

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

    /**
     * Holds {@code still} over the window at {@code at}, filling it with rounded corners, so the live
     * display behind can change unseen; null removes it.
     */
    void still(Bitmap bitmap, Rect at, float cornerRadius) {
        stillFade.cancel();
        still = bitmap;
        stillAlpha = 1;
        stillClip.reset();
        if (bitmap != null) {
            stillAt.set(at);
            stillClip.addRoundRect(new RectF(at), cornerRadius, cornerRadius, Path.Direction.CW);
            // Fill the window, cropping the still's overflow evenly, as the display was shown.
            float scale = Math.max(at.width() / (float) bitmap.getWidth(), at.height() / (float) bitmap.getHeight());
            int width = Math.round(at.width() / scale), height = Math.round(at.height() / scale);
            int left = (bitmap.getWidth() - width) / 2, top = (bitmap.getHeight() - height) / 2;
            stillSource.set(left, top, left + width, top + height);
        }
        invalidate();
    }

    boolean holdsStill() { return still != null; }

    /** Fades a held still away, revealing the live display. */
    void fadeStill() {
        if (still == null || stillFade.isRunning()) return;
        stillFade.setFloatValues(stillAlpha, 0);
        stillFade.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (still != null && stillAlpha > 0) {
            canvas.save();
            canvas.clipPath(stillClip);
            stillPaint.setAlpha(Math.round(255 * stillAlpha));
            canvas.drawBitmap(still, stillSource, stillAt, stillPaint);
            canvas.restore();
        }
        if (card != null && cardAlpha > 0 && !cardWindow.isEmpty()) drawTurnCard(canvas, card, cardAlpha);
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

    /**
     * As Maps' own PiP card: the arrow in a darker square on the left, the road above the distance
     * (or, without a road, the distance above the trip's time) beside it, in Maps' guidance color.
     */
    private void drawTurnCard(Canvas canvas, NavigationCard next, float alpha) {
        Rect at = NavigationCard.band(cardWindow);
        canvas.save();
        cardClip.reset();
        cardClip.addRoundRect(new RectF(cardWindow), cardRadius, cardRadius, Path.Direction.CW);
        canvas.clipPath(cardClip);
        int side = at.height();
        if (next.arrived) {
            drawArrived(canvas, side, alpha);
            canvas.restore();
            return;
        }
        band.setColor(next.color);
        band.setAlpha(Math.round(Color.alpha(next.color) * alpha));
        canvas.drawRect(at, band);
        band.setColor(ARROW_SHADE);
        band.setAlpha(Math.round(Color.alpha(ARROW_SHADE) * alpha));
        canvas.drawRect(at.left, at.top, at.left + side, at.bottom, band);
        if (arrow != null) {
            int size = Math.round(side * .6f), left = at.left + (side - size) / 2, top = at.top + (side - size) / 2;
            arrow.setBounds(left, top, left + size, top + size);
            arrow.setAlpha(Math.round(255 * alpha));
            arrow.draw(canvas);
        }
        boolean road = next.road.length() > 0;
        CharSequence first = road ? next.road : next.distance;
        CharSequence second = road ? next.distance : next.eta;
        float padding = side * .18f, textLeft = at.left + side + padding, room = at.right - padding - textLeft;
        primary.setTextSize(side * .3f);
        // "Exit 551 N State St / Ukiah" does not fit the narrow window at full size.
        primary.setTextSize(NavigationCard.fittedTextSize(side * .3f, primary.measureText(first, 0, first.length()), room));
        secondary.setTextSize(side * .2f);
        primary.setAlpha(Math.round(255 * alpha));
        secondary.setAlpha(Math.round(222 * alpha));
        float firstLine = at.top + side * .46f, secondLine = at.top + side * .78f;
        if (room > 0) {
            canvas.drawText(TextUtils.ellipsize(first, primary, room, TextUtils.TruncateAt.END).toString(), textLeft,
                    second.length() > 0 ? firstLine : at.centerY() + primary.getTextSize() * .35f, primary);
            if (second.length() > 0) {
                canvas.drawText(TextUtils.ellipsize(second, secondary, room, TextUtils.TruncateAt.END).toString(),
                        textLeft, secondLine, secondary);
            }
        }
        canvas.restore();
    }

    /**
     * As Maps' own PiP at the destination: the window filled green, a map pin above "Arrived" in
     * its middle, in the proportions of that window (a 28 px pin and 26 px text in 254 px).
     *
     * @param side the height of the turn card this replaces, 26.5% of the window's
     */
    private void drawArrived(Canvas canvas, int side, float alpha) {
        band.setColor(ARRIVED);
        band.setAlpha(Math.round(Color.alpha(ARRIVED) * alpha));
        canvas.drawRect(cardWindow, band);
        String arrived = getContext().getString(R.string.maps_arrived);
        int pin = Math.round(side * .46f);
        primary.setTextSize(side * .38f);
        primary.setAlpha(Math.round(255 * alpha));
        float gap = side * .16f, top = cardWindow.exactCenterY() - (pin + gap + primary.getTextSize()) / 2;
        draw(canvas, Ui.Icon.PIN, cardWindow.centerX(), Math.round(top + pin / 2f), pin, alpha);
        canvas.drawText(arrived, cardWindow.exactCenterX() - primary.measureText(arrived) / 2,
                top + pin + gap + primary.getTextSize() * .85f, primary);
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
        stillFade.cancel();
        still = null;
    }
}
