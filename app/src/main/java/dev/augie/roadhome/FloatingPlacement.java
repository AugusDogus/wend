package dev.augie.roadhome;

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

    // A normal scroll's deceleration per millisecond: a release travels v * 0.499 px.
    private static final float DECELERATION = 0.998f;
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

    /** Moves exactly with the finger, but keeps the whole window on screen. */
    Rect dragged(Rect start, float dx, float dy) {
        int left = clamp(Math.round(start.left + dx), area.left, area.right - width);
        int top = clamp(Math.round(start.top + dy), area.top, area.bottom - height);
        return new Rect(left, top, left + width, top + height);
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

    /** Distance in px a release at this px/s velocity would coast before stopping. */
    static float projection(float velocity) {
        return velocity / 1000f * DECELERATION / (1 - DECELERATION);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
