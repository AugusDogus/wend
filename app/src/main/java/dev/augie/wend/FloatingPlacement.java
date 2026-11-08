package dev.augie.wend;

import android.graphics.Point;
import android.graphics.Rect;

/**
 * Where a floating window rests, how it follows a drag, and which corner a release sends it to.
 * Pure screen geometry in pixels, so any floating surface can share it.
 */
final class FloatingPlacement {
    enum Corner {
        TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true);

        final boolean right;
        final boolean bottom;

        Corner(boolean right, boolean bottom) { this.right = right; this.bottom = bottom; }

        static Corner parse(String name, Corner fallback) {
            for (Corner corner : values()) if (corner.name().equals(name)) return corner;
            return fallback;
        }
    }

    /** A floating video's 16:9 size; other floating windows match its area. */
    static final int VIDEO_WIDTH_DP = 288, VIDEO_HEIGHT_DP = 162;
    // A normal scroll's deceleration per millisecond: a release travels v * 0.499 px.
    private static final float DECELERATION = 0.998f;
    /** Android 10's PipMotionHelper.DISMISS_OFFSCREEN_FRACTION. */
    private static final float DISMISS_OFFSCREEN_FRACTION = .3f;
    private final Rect area;
    private final int width;
    private final int height;
    private final int margin;

    /** @param area the part of the screen the window may cover, excluding system bars */
    FloatingPlacement(Rect area, int width, int height, int margin) {
        this.area = new Rect(area);
        this.width = width;
        this.height = height;
        this.margin = margin;
    }

    Rect rest(Corner corner) {
        int left = corner.right ? area.right - margin - width : area.left + margin;
        int top = corner.bottom ? area.bottom - margin - height : area.top + margin;
        return new Rect(left, top, left + width, top + height);
    }

    /**
     * Moves exactly with the finger. The window stays on screen except through the bottom edge,
     * where Android 10's PiP is dragged away to close it.
     */
    Rect dragged(Rect start, float dx, float dy) {
        int left = clamp(Math.round(start.left + dx), area.left, area.right - width);
        int top = clamp(Math.round(start.top + dy), area.top, area.bottom);
        return new Rect(left, top, left + width, top + height);
    }

    /** The same window moved straight down until it is wholly below the bottom edge. */
    Rect below(Rect bounds) {
        Rect moved = new Rect(bounds);
        moved.offsetTo(bounds.left, area.bottom);
        return moved;
    }

    /** Released this far below the bottom edge, the window closes, as Android 10's PiP does. */
    boolean offscreenDismisses(Rect bounds) {
        return bounds.bottom - area.bottom >= DISMISS_OFFSCREEN_FRACTION * height;
    }

    /**
     * A mostly downward fling closes the window when the finger went down or came up in the
     * bottom row of resting windows, as Android 10's PiP does; from higher up it only moves there.
     * @param downY where the finger went down, in screen px
     * @param upY where it came up
     * @param minVelocity px/s below which a release is not a fling
     */
    boolean flingDismisses(float downY, float upY, float velocityX, float velocityY, float minVelocity) {
        float bottomRow = rest(Corner.BOTTOM_LEFT).top;
        return velocityY >= minVelocity && Math.abs(velocityY) > Math.abs(velocityX)
                && (downY >= bottomRow || upY >= bottomRow);
    }

    /** Projects the release momentum forward, then picks the corner closest to where it lands. */
    Corner release(Rect current, float velocityX, float velocityY) {
        float x = current.exactCenterX() + projection(velocityX);
        float y = current.exactCenterY() + projection(velocityY);
        Corner nearest = Corner.BOTTOM_RIGHT;
        float best = Float.MAX_VALUE;
        for (Corner corner : Corner.values()) {
            Rect rest = rest(corner);
            float distance = (rest.exactCenterX() - x) * (rest.exactCenterX() - x)
                    + (rest.exactCenterY() - y) * (rest.exactCenterY() - y);
            if (distance < best) { best = distance; nearest = corner; }
        }
        return nearest;
    }

    /**
     * A window of the same visual weight (area) as {@code width} x {@code height}, but shaped
     * {@code aspect} (width / height), such as a floating map that keeps its panel's shape.
     */
    static Point matchingArea(int width, int height, float aspect) {
        double area = (double) width * height;
        return new Point((int) Math.round(Math.sqrt(area * aspect)), (int) Math.round(Math.sqrt(area / aspect)));
    }

    /** Distance in px a release at this px/s velocity would coast before stopping. */
    static float projection(float velocity) {
        return velocity / 1000f * DECELERATION / (1 - DECELERATION);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
