package dev.augie.wend;

/**
 * Drag rules for a bottom sheet, in pixels. An offset is how far the sheet sits below its
 * open position; velocities are downward-positive pixels per second.
 */
final class SheetDrag {
    enum Release { DISMISS, SETTLE }

    /** Like Vaul: releasing past a quarter of the height dismisses. */
    static final float DISMISS_FRACTION = 0.25f;
    /** A flick at least this fast decides the outcome regardless of distance. */
    static final float FLING_DP_PER_SECOND = 500;
    /** Upward overdrag approaches, but never reaches, this distance. */
    static final float OVERDRAG_DP = 24;
    // iOS scroll views use the same initial resistance.
    private static final float RESISTANCE = 0.55f;

    private final float flingVelocity;
    private final float overdragLimit;

    SheetDrag(float density) {
        flingVelocity = FLING_DP_PER_SECOND * density;
        overdragLimit = OVERDRAG_DP * density;
    }

    Release release(float offset, float height, float velocity) {
        if (velocity >= flingVelocity) return Release.DISMISS;
        if (velocity <= -flingVelocity) return Release.SETTLE;
        return offset >= height * DISMISS_FRACTION ? Release.DISMISS : Release.SETTLE;
    }

    /** Maps the finger's travel to the sheet's offset: 1:1 downward, rubber-banded upward. */
    float follow(float fingerOffset) {
        if (fingerOffset >= 0) return fingerOffset;
        float pull = -fingerOffset;
        return -overdragLimit * (1 - 1 / (pull * RESISTANCE / overdragLimit + 1));
    }

    /** 1 when open (or overdragged), 0 when the sheet is fully below the screen. */
    static float openness(float offset, float height) {
        return Math.max(0, Math.min(1, 1 - offset / height));
    }
}
