package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.RectF;

/** Where an owned display surface is drawn: a uniform scale, its offset, and the region shown. */
final class SurfacePlacement {
    final float scale;
    final float x;
    final float y;
    /** Shown region in the surface's own (display) coordinates. */
    final RectF crop;

    private SurfacePlacement(float scale, float x, float y, RectF crop) {
        this.scale = scale;
        this.x = x;
        this.y = y;
        this.crop = crop;
    }

    /** Shows only {@code source}, scaled uniformly and centered inside {@code target}. */
    static SurfacePlacement fit(RectF source, Rect target) {
        float scale = Math.min(target.width() / source.width(), target.height() / source.height());
        return new SurfacePlacement(scale,
                target.left + (target.width() - source.width() * scale) / 2 - source.left * scale,
                target.top + (target.height() - source.height() * scale) / 2 - source.top * scale,
                new RectF(source));
    }

    /** The whole display at pixel scale one. */
    static SurfacePlacement fullscreen(int width, int height) {
        return new SurfacePlacement(1, 0, 0, new RectF(0, 0, width, height));
    }

    /** The whole display at pixel scale one, centered on a screen at least as large. */
    static SurfacePlacement centered(int width, int height, int screenWidth, int screenHeight) {
        return new SurfacePlacement(1, (screenWidth - width) / 2f, (screenHeight - height) / 2f,
                new RectF(0, 0, width, height));
    }

    /** The same shown region, scaled by {@code factor} about its on-screen center. */
    SurfacePlacement scaled(float factor) {
        RectF visible = visible();
        float next = scale * factor;
        return new SurfacePlacement(next, visible.centerX() - crop.centerX() * next,
                visible.centerY() - crop.centerY() * next, new RectF(crop));
    }

    static SurfacePlacement lerp(SurfacePlacement from, SurfacePlacement to, float fraction) {
        return new SurfacePlacement(lerp(from.scale, to.scale, fraction), lerp(from.x, to.x, fraction),
                lerp(from.y, to.y, fraction), new RectF(lerp(from.crop.left, to.crop.left, fraction),
                lerp(from.crop.top, to.crop.top, fraction), lerp(from.crop.right, to.crop.right, fraction),
                lerp(from.crop.bottom, to.crop.bottom, fraction)));
    }

    /** The on-screen rectangle the shown region occupies. */
    RectF visible() {
        return new RectF(x + crop.left * scale, y + crop.top * scale,
                x + crop.right * scale, y + crop.bottom * scale);
    }

    void crop(Rect result) {
        result.set(Math.round(crop.left), Math.round(crop.top), Math.round(crop.right), Math.round(crop.bottom));
    }

    private static float lerp(float from, float to, float fraction) { return from + (to - from) * fraction; }
}
