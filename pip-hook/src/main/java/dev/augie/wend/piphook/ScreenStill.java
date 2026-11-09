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
        try {
            Object shot = XposedHelpers.callStaticMethod(SurfaceControl.class, "screenshotToBuffer",
                    (IBinder) XposedHelpers.callStaticMethod(SurfaceControl.class, "getInternalDisplayToken"),
                    new Rect(bounds), bounds.width(), bounds.height(), false, 0);
            if (shot == null) return null;
            SurfaceControl layer = ((SurfaceControl.Builder) XposedHelpers.callMethod(displayContent, "makeOverlay"))
                    .setName(name).setBufferSize(bounds.width(), bounds.height()).build();
            Surface surface = new Surface(layer);
            try { XposedHelpers.callMethod(surface, "attachAndQueueBuffer", XposedHelpers.callMethod(shot, "getGraphicBuffer")); }
            finally { surface.release(); }
            ScreenStill still = new ScreenStill(layer, new Rect(bounds));
            SurfaceControl.Transaction t = new SurfaceControl.Transaction().setLayer(layer, Integer.MAX_VALUE)
                    .setVisibility(layer, true);
            still.place(t, bounds);
            t.apply();
            return still;
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not show a still of " + bounds + ": " + error);
            return null;
        }
    }

    /** Shows the still covering {@code target} without distortion, cropped around its center. */
    void place(SurfaceControl.Transaction t, Rect target) {
        float scale = Math.max(target.width() / (float) source.width(), target.height() / (float) source.height());
        float cropWidth = target.width() / scale;
        float cropHeight = target.height() / scale;
        int cropLeft = Math.round((source.width() - cropWidth) / 2f);
        int cropTop = Math.round((source.height() - cropHeight) / 2f);
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
