package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.SurfaceControl;
import java.lang.reflect.Method;

/**
 * A hairline edge and soft shadow behind a floating surface. Color layers, not a drawn View,
 * so they move in the same compositor transaction as the surface and never lag a drag.
 */
final class FloatingOutline {
    private static final class Ring {
        final float spread, drop, alpha;
        final float[] color;
        Ring(float spread, float drop, float alpha, float[] color) {
            this.spread = spread; this.drop = drop; this.alpha = alpha; this.color = color;
        }
    }

    private static final float[] SHADOW = {0, 0, 0};
    // Neutral gray separates the video from both day (light) and night (dark) map tiles.
    private static final float[] EDGE = {.5f, .5f, .5f};
    private static final int SHADOW_RINGS = 6;
    private final Ring[] rings;
    private final SurfaceControl[] layers;
    private final Method position;
    private final Method crop;
    private final Method cornerRadius;
    private final Rect bounds = new Rect();

    /** @param density display density; ring sizes are specified in dp */
    FloatingOutline(SurfaceControl parent, float density) throws ReflectiveOperationException {
        // Without a blur primitive in Android 10, stacked faint rings approximate a soft drop
        // shadow: about 22% black at the edge, fading over 7.5 dp, offset down as if lit from
        // above. Fine 4.5% steps keep the rings from banding. A hairline edge sits on top.
        rings = new Ring[SHADOW_RINGS + 1];
        for (int i = 0; i < SHADOW_RINGS; i++) {
            int step = SHADOW_RINGS - i;
            rings[i] = new Ring(1.25f * step * density, .4f * step * density, .045f, SHADOW);
        }
        rings[SHADOW_RINGS] = new Ring(1, 0, .35f, EDGE);
        position = SurfaceControl.Transaction.class.getMethod("setPosition", SurfaceControl.class,
                float.class, float.class);
        crop = SurfaceControl.Transaction.class.getMethod("setWindowCrop", SurfaceControl.class, Rect.class);
        cornerRadius = SurfaceControl.Transaction.class.getMethod("setCornerRadius", SurfaceControl.class, float.class);
        Method colorLayer = SurfaceControl.Builder.class.getMethod("setColorLayer");
        Method color = SurfaceControl.Transaction.class.getMethod("setColor", SurfaceControl.class, float[].class);
        // Android 10 builds new layers hidden; alpha alone would leave them invisible.
        Method show = SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class);
        layers = new SurfaceControl[rings.length];
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            for (int i = 0; i < rings.length; i++) {
                SurfaceControl.Builder builder = new SurfaceControl.Builder()
                        .setName("Road Home floating outline " + i).setParent(parent);
                colorLayer.invoke(builder);
                layers[i] = builder.build();
                // Below the floating surface, which its window places at layer 1.
                transaction.setLayer(layers[i], i - rings.length);
                color.invoke(transaction, layers[i], rings[i].color);
                transaction.setAlpha(layers[i], 0);
                show.invoke(transaction, layers[i]);
            }
            transaction.apply();
        } catch (ReflectiveOperationException | RuntimeException error) {
            release();
            throw error;
        }
    }

    /** Surrounds {@code visible} with corners concentric to {@code radius}, at {@code opacity}. */
    void place(SurfaceControl.Transaction transaction, RectF visible, float radius, float opacity)
            throws ReflectiveOperationException {
        for (int i = 0; i < rings.length; i++) {
            Ring ring = rings[i];
            float left = visible.left - ring.spread;
            float top = visible.top - ring.spread + ring.drop;
            bounds.set(0, 0, Math.round(visible.width() + 2 * ring.spread),
                    Math.round(visible.height() + 2 * ring.spread));
            position.invoke(transaction, layers[i], left, top);
            crop.invoke(transaction, layers[i], bounds);
            cornerRadius.invoke(transaction, layers[i], radius + ring.spread);
            transaction.setAlpha(layers[i], ring.alpha * opacity);
        }
    }

    void release() {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            for (SurfaceControl layer : layers) {
                if (layer != null && layer.isValid()) transaction.reparent(layer, null);
            }
            transaction.apply();
        }
        for (SurfaceControl layer : layers) if (layer != null) layer.release();
    }
}
