package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.RectF;

/** Uniform scale preserves panel contents while the crop reveals the full app around them. */
final class PanelWindowMotion {
    private final SurfacePlacement panel;
    private final SurfacePlacement fullscreen;

    PanelWindowMotion(int x, int y, int cardWidth, int cardHeight,
            RectF source, int width, int height) {
        panel = SurfacePlacement.fit(source, new Rect(x, y, x + cardWidth, y + cardHeight));
        fullscreen = SurfacePlacement.fullscreen(width, height);
    }

    float scale(float fraction) { return at(fraction).scale; }
    float x(float fraction) { return at(fraction).x; }
    float y(float fraction) { return at(fraction).y; }
    void crop(float fraction, Rect result) { at(fraction).crop(result); }

    private SurfacePlacement at(float fraction) { return SurfacePlacement.lerp(panel, fullscreen, fraction); }
}
