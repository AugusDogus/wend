package dev.augie.wend;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.SurfaceControl;
import java.lang.reflect.Method;

/**
 * A hairline edge behind a floating surface, as Android 10's own PiP window has no shadow. A color
 * layer, not a drawn View, so it moves in the same compositor transaction and never lags a drag.
 */
final class FloatingOutline {
    // Neutral gray separates the window from both day (light) and night (dark) map tiles.
    private static final float[] EDGE = {.5f, .5f, .5f};
    private static final float EDGE_ALPHA = .35f;
    /** Below the floating surface, which its window places at layer 1. */
    private static final int LAYER = -1;
    private final SurfaceControl layer;
    private final Method position;
    private final Method crop;
    private final Method cornerRadius;
    private final Rect bounds = new Rect();

    FloatingOutline(SurfaceControl parent) throws ReflectiveOperationException {
        position = SurfaceControl.Transaction.class.getMethod("setPosition", SurfaceControl.class,
                float.class, float.class);
        crop = SurfaceControl.Transaction.class.getMethod("setWindowCrop", SurfaceControl.class, Rect.class);
        cornerRadius = SurfaceControl.Transaction.class.getMethod("setCornerRadius", SurfaceControl.class, float.class);
        Method colorLayer = SurfaceControl.Builder.class.getMethod("setColorLayer");
        Method color = SurfaceControl.Transaction.class.getMethod("setColor", SurfaceControl.class, float[].class);
        // Android 10 builds new layers hidden; alpha alone would leave them invisible.
        Method show = SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class);
        SurfaceControl.Builder builder = new SurfaceControl.Builder().setName("Wend floating outline").setParent(parent);
        colorLayer.invoke(builder);
        layer = builder.build();
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setLayer(layer, LAYER);
            color.invoke(transaction, layer, EDGE);
            transaction.setAlpha(layer, 0);
            show.invoke(transaction, layer);
            transaction.apply();
        } catch (ReflectiveOperationException | RuntimeException error) {
            release();
            throw error;
        }
    }

    /** Surrounds {@code visible} by one pixel with corners concentric to {@code radius}, at {@code opacity}. */
    void place(SurfaceControl.Transaction transaction, RectF visible, float radius, float opacity)
            throws ReflectiveOperationException {
        bounds.set(0, 0, Math.round(visible.width() + 2), Math.round(visible.height() + 2));
        position.invoke(transaction, layer, visible.left - 1, visible.top - 1);
        crop.invoke(transaction, layer, bounds);
        cornerRadius.invoke(transaction, layer, radius + 1);
        transaction.setAlpha(layer, EDGE_ALPHA * opacity);
    }

    void release() {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            if (layer.isValid()) transaction.reparent(layer, null);
            transaction.apply();
        }
        layer.release();
    }
}
