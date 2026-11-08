package dev.augie.wend;

import android.graphics.Rect;

/** Moves the window edges; the app's pixel scale stays at one throughout. */
final class MapWindowMotion {
    private MapWindowMotion() { }
    static void interpolate(Rect start, Rect end, float progress, Rect out) {
        out.set(Math.round(start.left + (end.left - start.left) * progress),
                Math.round(start.top + (end.top - start.top) * progress),
                Math.round(start.right + (end.right - start.right) * progress),
                Math.round(start.bottom + (end.bottom - start.bottom) * progress));
    }
}
