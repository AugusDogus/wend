package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.RectF;

/** Uniform scale preserves panel contents while the crop reveals the full app around them. */
final class PanelWindowMotion {
    private final float startScale, startX, startY;
    private final RectF source;
    private final int width, height;

    PanelWindowMotion(int x, int y, int cardWidth, int cardHeight,
            RectF source, int width, int height) {
        this.source = new RectF(source);
        this.width = width;
        this.height = height;
        startScale = Math.min(cardWidth / source.width(), cardHeight / source.height());
        startX = x + (cardWidth - source.width() * startScale) / 2 - source.left * startScale;
        startY = y + (cardHeight - source.height() * startScale) / 2 - source.top * startScale;
    }

    float scale(float fraction) { return lerp(startScale, 1, fraction); }
    float x(float fraction) { return lerp(startX, 0, fraction); }
    float y(float fraction) { return lerp(startY, 0, fraction); }

    void crop(float fraction, Rect result) {
        result.set(Math.round(lerp(source.left, 0, fraction)), Math.round(lerp(source.top, 0, fraction)),
                Math.round(lerp(source.right, width, fraction)),
                Math.round(lerp(source.bottom, height, fraction)));
    }

    private static float lerp(float from, float to, float fraction) { return from + (to - from) * fraction; }
}
