package dev.augie.wend;

import android.graphics.RectF;

/**
 * The part of Maps' panel display a floating Maps window shows: a portrait of the map, as Android's
 * vertical PiP shows navigation, without Maps' own cards, sheet, and buttons. Maps cannot be asked
 * for its PiP layout without system PiP, so this crops the panel layout it already draws. Measured
 * on real Maps in guidance in the 70% panel: its next-turn card covers the top 95 dp, its trip
 * sheet the bottom 75 dp, and its side buttons the outer 77 dp, while the car stays centered.
 * Under the next-turn card ({@link NavigationCard}) the same portrait sits lower, so the car stays in
 * the part of the map the card leaves visible and the trip sheet's top goes under the card.
 */
final class FloatingMapCrop {
    private static final int TOP_DP = 100, BOTTOM_DP = 80;
    /** Width over height: a portrait, chosen from crops of guidance screenshots, that keeps the car and the road ahead. */
    private static final float ASPECT = .8f;
    /** Moves the portrait down under the turn card: in guidance Maps draws the car 468 of 720 px down. */
    private static final int UNDER_CARD_DP = 47;

    private FloatingMapCrop() { }

    /**
     * @param underTurnCard whether the floating window draws the next-turn card across its bottom
     * @return the crop in the display's own pixels, or the whole display if it is too small for one
     */
    static RectF of(int width, int height, float density, boolean underTurnCard) {
        float shift = underTurnCard ? UNDER_CARD_DP * density : 0;
        float top = TOP_DP * density + shift;
        float shown = height - TOP_DP * density - BOTTOM_DP * density;
        float across = shown * ASPECT;
        if (shown <= 0 || across > width) return new RectF(0, 0, width, height);
        float left = (width - across) / 2;
        return new RectF(left, top, left + across, top + shown);
    }
}
