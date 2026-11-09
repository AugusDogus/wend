package dev.augie.wend.piphook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. A still of part of the main display, shown on the display's overlay layer
 * (above every window), that can be moved to other screen bounds and faded out. It covers an app
 * while Android 10 resizes it and the app redraws.
 */
final class ScreenStill implements SettleCover.Reveal {
    private static final long FADE_MS = 180;

    private final SurfaceControl layer;
    private final Rect source;
    private boolean removed;

    private ScreenStill(SurfaceControl layer, Rect source) {
        this.layer = layer;
        this.source = source;
    }

    /**
     * Takes a still of {@code bounds} (display coordinates) and shows it in place at once.
     * @param displayContent the main display's DisplayContent
     * @return null if Android could not take or show it; nothing is left on screen then
     */
    static ScreenStill show(Object displayContent, Rect bounds, String name) {
        Capture capture = Capture.take(bounds);
        return capture == null ? null : show(displayContent, capture, bounds, name, Integer.MAX_VALUE, 1f);
    }

    /**
     * Shows {@code capture} covering {@code at} at once.
     * @param layer its z-order on the display's overlay layer
     * @return null if Android could not show it; nothing is left on screen then
     */
    static ScreenStill show(Object displayContent, Capture capture, Rect at, String name, int layer, float alpha) {
        try {
            return show((SurfaceControl.Builder) XposedHelpers.callMethod(displayContent, "makeOverlay"), capture, at, name, layer, alpha);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not show a still of " + capture.bounds + ": " + error);
            return null;
        }
    }

    /** Shows {@code capture} covering {@code at} as the top child of {@code parent}, in its coordinates. */
    static ScreenStill showIn(SurfaceControl parent, Capture capture, Rect at, String name, float alpha) {
        return show(new SurfaceControl.Builder().setParent(parent), capture, at, name, Integer.MAX_VALUE, alpha);
    }

    private static ScreenStill show(SurfaceControl.Builder builder, Capture capture, Rect at, String name, int layer, float alpha) {
        try {
            SurfaceControl control = builder.setName(name)
                    .setBufferSize(capture.whole.width(), capture.whole.height()).build();
            Surface surface = new Surface(control);
            try { XposedHelpers.callMethod(surface, "attachAndQueueBuffer", capture.buffer); }
            finally { surface.release(); }
            // The part of the buffer this still shows, in the buffer's own coordinates.
            Rect source = new Rect(capture.bounds);
            source.offset(-capture.whole.left, -capture.whole.top);
            ScreenStill still = new ScreenStill(control, source);
            SurfaceControl.Transaction t = new SurfaceControl.Transaction().setLayer(control, layer)
                    .setAlpha(control, alpha).setVisibility(control, true);
            still.place(t, at);
            t.apply();
            return still;
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not show a still of " + capture.bounds + ": " + error);
            return null;
        }
    }

    /**
     * A picture of part of the main display, kept to be shown later. One screenshot can stand for
     * several parts of it ({@link #part}): each screenshot makes SurfaceFlinger render offscreen,
     * which competes with the frames of a motion starting at that moment.
     */
    static final class Capture {
        /** What this picture shows, in screen coordinates. */
        final Rect bounds;
        final Object buffer;
        /** The screen area the whole buffer holds. */
        final Rect whole;

        private Capture(Rect bounds, Object buffer, Rect whole) {
            this.bounds = bounds;
            this.buffer = buffer;
            this.whole = whole;
        }

        /** The same screenshot, showing only {@code region} of it (screen coordinates, within it). */
        Capture part(Rect region) { return new Capture(new Rect(region), buffer, whole); }

        /**
         * The main display's {@code bounds} without the layers of {@code excluded} window containers
         * (such as the PiP stack), as if they were not there.
         * @return null if Android could not take it
         */
        static Capture takeWithout(Object displayContent, Rect bounds, Object... excluded) {
            try {
                SurfaceControl root = (SurfaceControl) XposedHelpers.callMethod(displayContent, "getWindowingLayer");
                java.util.List<IBinder> handles = new java.util.ArrayList<>();
                for (Object container : excluded) {
                    if (container != null) handles.add((IBinder) XposedHelpers.callMethod(XposedHelpers.callMethod(container, "getSurfaceControl"), "getHandle"));
                }
                Object shot = XposedHelpers.callStaticMethod(SurfaceControl.class, "captureLayersExcluding",
                        XposedHelpers.callMethod(root, "getHandle"), new Rect(bounds), 1f, handles.toArray(new IBinder[0]));
                return shot == null ? null : new Capture(new Rect(bounds), XposedHelpers.callMethod(shot, "getGraphicBuffer"), new Rect(bounds));
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": could not take a still of " + bounds + ": " + error);
                return null;
            }
        }

        /** @return null if Android could not take it */
        static Capture take(Rect bounds) {
            try {
                Object shot = XposedHelpers.callStaticMethod(SurfaceControl.class, "screenshotToBuffer",
                        (IBinder) XposedHelpers.callStaticMethod(SurfaceControl.class, "getInternalDisplayToken"),
                        new Rect(bounds), bounds.width(), bounds.height(), false, 0);
                return shot == null ? null : new Capture(new Rect(bounds), XposedHelpers.callMethod(shot, "getGraphicBuffer"), new Rect(bounds));
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": could not take a still of " + bounds + ": " + error);
                return null;
            }
        }
    }

    /** Shows the still covering {@code target} without distortion, cropped around its center. */
    void place(SurfaceControl.Transaction t, Rect target) {
        float scale = Math.max(target.width() / (float) source.width(), target.height() / (float) source.height());
        float cropWidth = target.width() / scale;
        float cropHeight = target.height() / scale;
        int cropLeft = source.left + Math.round((source.width() - cropWidth) / 2f);
        int cropTop = source.top + Math.round((source.height() - cropHeight) / 2f);
        XposedHelpers.callMethod(t, "setWindowCrop", layer,
                new Rect(cropLeft, cropTop, Math.round(cropLeft + cropWidth), Math.round(cropTop + cropHeight)));
        XposedHelpers.callMethod(t, "setMatrix", layer, scale, 0f, 0f, scale);
        XposedHelpers.callMethod(t, "setPosition", layer, target.left - cropLeft * scale, target.top - cropTop * scale);
    }

    /** Fades the still out on {@code thread}, the animation thread, and removes it. */
    @Override public void reveal(Handler thread) {
        thread.post(() -> {
            ValueAnimator fade = ValueAnimator.ofFloat(1f, 0f).setDuration(FADE_MS);
            fade.addUpdateListener(a -> setAlpha((float) a.getAnimatedValue()));
            fade.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { remove(); }
            });
            fade.start();
        });
    }

    void alpha(SurfaceControl.Transaction t, float alpha) { t.setAlpha(layer, alpha); }

    SurfaceControl layer() { return layer; }

    void setAlpha(float alpha) {
        try { new SurfaceControl.Transaction().setAlpha(layer, alpha).apply(); }
        catch (Throwable error) { XposedBridge.log(PipHook.TAG + ": still alpha failed: " + error); }
    }

    /** Removes the still at once. */
    void remove() {
        if (removed) return;
        removed = true;
        try {
            new SurfaceControl.Transaction().reparent(layer, null).apply();
            layer.release();
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not remove a still: " + error);
        }
    }
}
