package dev.augie.wend.piphook;

import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceControl;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. Holds a still of a panel app's PiP, exactly as it landed, over the app while
 * Android 10 resizes it and the app redraws, then fades the still out.
 *
 * <p>The still sits on the display's overlay layer over the PiP. It is revealed when the app reports its PiP layout settled ({@link PipSignals}), or after
 * {@link #SETTLE_CAP_MS} if the app never does, so it can never stay over the PiP.
 */
final class LandingBridge {
    private static final long SETTLE_CAP_MS = 1500;
    private static final long FADE_MS = 180;
    private static final long FRAME_MS = 16;

    private static final Object lock = new Object();
    /** The still over the PiP, or null; guarded by {@link #lock}. */
    private static SurfaceControl still;
    private static Handler handler;

    private LandingBridge() { }

    /** Called on the bounds animation thread as the shrink ends at {@code landed} (display coordinates). */
    static void cover(Object taskStack, Rect landed) {
        try {
            Object displayContent = XposedHelpers.callMethod(taskStack, "getDisplayContent");
            Handler animation = (Handler) XposedHelpers.callMethod(
                    XposedHelpers.getObjectField(displayContent, "mBoundsAnimationController"), "getHandler");
            IBinder display = (IBinder) XposedHelpers.callStaticMethod(SurfaceControl.class, "getInternalDisplayToken");
            Object shot = XposedHelpers.callStaticMethod(SurfaceControl.class, "screenshotToBuffer", display,
                    new Rect(landed), landed.width(), landed.height(), false, 0);
            if (shot == null) {
                XposedBridge.log(PipHook.TAG + ": no still of the landed PiP; the app's relayout shows as is");
                return;
            }
            // On the display's overlay layer, at the screen bounds the still was taken from, so it lines
            // up with the PiP exactly; it is up for under two seconds, so a drag in that time is not tracked.
            SurfaceControl.Builder builder = (SurfaceControl.Builder) XposedHelpers.callMethod(displayContent, "makeOverlay");
            SurfaceControl cover = builder.setName("WendPipLandingStill")
                    .setBufferSize(landed.width(), landed.height()).build();
            Surface surface = new Surface(cover);
            try { XposedHelpers.callMethod(surface, "attachAndQueueBuffer", XposedHelpers.callMethod(shot, "getGraphicBuffer")); }
            finally { surface.release(); }
            new SurfaceControl.Transaction().setLayer(cover, Integer.MAX_VALUE).setPosition(cover, landed.left, landed.top)
                    .setAlpha(cover, 1f).setVisibility(cover, true).apply();
            SurfaceControl previous;
            synchronized (lock) {
                previous = still;
                still = cover;
                handler = animation;
            }
            if (previous != null) remove(previous);
            animation.postDelayed(() -> revealIfStill(cover, "Maps did not report its PiP layout within "
                    + SETTLE_CAP_MS + " ms"), SETTLE_CAP_MS);
            XposedBridge.log(PipHook.TAG + ": holding the landed PiP still over " + landed);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not hold a still over the landed PiP; the app's relayout shows as is: " + error);
        }
    }

    static void reveal(String why) {
        SurfaceControl cover;
        synchronized (lock) { cover = still; }
        if (cover != null) revealIfStill(cover, why);
    }

    private static void revealIfStill(final SurfaceControl cover, String why) {
        final Handler animation;
        synchronized (lock) {
            if (still != cover) return;
            still = null;
            animation = handler;
        }
        XposedBridge.log(PipHook.TAG + ": revealing the PiP: " + why);
        final long start = SystemClock.uptimeMillis();
        animation.post(new Runnable() {
            @Override public void run() {
                try {
                    float progress = (SystemClock.uptimeMillis() - start) / (float) FADE_MS;
                    if (progress >= 1f) { remove(cover); return; }
                    new SurfaceControl.Transaction().setAlpha(cover, 1f - progress).apply();
                    animation.postDelayed(this, FRAME_MS);
                } catch (Throwable error) {
                    XposedBridge.log(PipHook.TAG + ": PiP still fade failed; removing it: " + error);
                    remove(cover);
                }
            }
        });
    }

    private static void remove(SurfaceControl cover) {
        try {
            new SurfaceControl.Transaction().reparent(cover, null).apply();
            cover.release();
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not remove the PiP still: " + error);
        }
    }
}
