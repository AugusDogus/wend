package dev.augie.wend;

import android.graphics.RectF;

/**
 * The part of Maps' panel display a floating Maps window shows: a portrait of the map, as Android's
 * vertical PiP shows navigation, without Maps' own cards, sheet, and buttons. Maps cannot be asked
 * for its PiP layout without system PiP, so this crops the panel layout it already draws. Measured
 * on real Maps in guidance in the 70% panel: its next-turn card covers the top 95 dp, its trip
 * sheet the bottom 75 dp, and its side buttons the outer 77 dp, while the car stays centered.
 */
final class FloatingMapCrop {
    private static final int TOP_DP = 100, BOTTOM_DP = 80;
    /** Width over height: a portrait, chosen from crops of guidance screenshots, that keeps the car and the road ahead. */
    private static final float ASPECT = .8f;

    private FloatingMapCrop() { }

    /** @return the crop in the display's own pixels, or the whole display if it is too small for one */
    static RectF of(int width, int height, float density) {
        float top = TOP_DP * density;
        float shown = height - top - BOTTOM_DP * density;
        float across = shown * ASPECT;
        if (shown <= 0 || across > width) return new RectF(0, 0, width, height);
        float left = (width - across) / 2;
        return new RectF(left, top, left + across, top + shown);
    }
}
