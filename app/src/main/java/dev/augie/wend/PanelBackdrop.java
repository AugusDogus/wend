package dev.augie.wend;

import android.graphics.Color;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.lang.reflect.Method;

/**
 * A panel's own color under its embedded display, seen wherever the display is not, such as
 * while it floats in another window. Android 10 shows black there: the background layers it keeps
 * under every SurfaceView of the window, of which the video card's is as large as the video's
 * display and so lies under the Maps panel too. This is a color layer inside the panel's own
 * surface, above those: it is as large as the panel, moves with it and goes when it goes.
 */
final class PanelBackdrop implements SurfaceHolder.Callback {
    /**
     * The lowest layer of the panel's surface. The display is above it at whichever layer it has
     * there: 0 at first, and -1 when it comes back from a floating window.
     */
    private static final int LAYER = Integer.MIN_VALUE;
    private final SurfaceView panel;
    private final float[] color;
    private SurfaceControl layer;

    /** Keeps {@code color} under whatever {@code panel}'s surface shows, for as long as it has one. */
    static PanelBackdrop under(SurfaceView panel, int color) {
        PanelBackdrop backdrop = new PanelBackdrop(panel, color);
        panel.getHolder().addCallback(backdrop);
        if (panel.getHolder().getSurface() != null && panel.getHolder().getSurface().isValid()) backdrop.show();
        return backdrop;
    }

    private PanelBackdrop(SurfaceView panel, int color) {
        this.panel = panel;
        this.color = new float[] {Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f};
    }

    /** Whether the panel's surface has its color under it now. */
    boolean shown() { return layer != null; }

    @Override public void surfaceCreated(SurfaceHolder holder) { show(); }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { release(); }

    private void show() {
        release();
        try {
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName("Wend panel backdrop")
                    .setParent(panel.getSurfaceControl());
            SurfaceControl.Builder.class.getMethod("setColorLayer").invoke(builder);
            Method setColor = SurfaceControl.Transaction.class.getMethod("setColor", SurfaceControl.class,
                    float[].class);
            // Android 10 builds new layers hidden.
            Method show = SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class);
            layer = builder.build();
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setLayer(layer, LAYER);
                setColor.invoke(transaction, layer, color);
                show.invoke(transaction, layer);
                transaction.apply();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            release();
            Log.w("Wend", "Could not color the panel under its display; it shows black wherever the display"
                    + " is not, as while it floats", error);
        }
    }

    /** The panel's surface takes its layers with it; only this handle is left to let go. */
    void release() {
        if (layer == null) return;
        layer.release();
        layer = null;
    }
}
