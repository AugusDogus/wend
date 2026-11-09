package dev.augie.wend.piphook;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.animation.PathInterpolator;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. Grows a task Wend moves out of a panel card to fullscreen on the main
 * display, instead of the cut Android 10 makes (no app transition runs for a task that was already
 * visible in its card).
 *
 * <p>Wend asks for it by putting the card's screen bounds under {@link #GROW_FROM} in the options of
 * {@code startActivityFromRecents}. The moved task's surface then scales from the card to fullscreen
 * on the PiP shrink's curve and duration, over a still of the screen as it was before the move (without
 * the PiP), since Android hides Wend the moment the task covers it.
 */
final class CardGrow {
    /** Must match {@code dev.augie.wend.VideoTask.GROW_FROM}: the card's screen bounds as {left, top, right, bottom}. */
    static final String GROW_FROM = "dev.augie.wend.GROW_FROM";
    /** BoundsAnimationController.DEFAULT_TRANSITION_DURATION, so the grow and Maps' shrink move together. */
    private static final long DURATION_MS = 425;
    private static final String CARD = "wendGrowCard";
    private static final String BACKDROP = "wendGrowBackdrop";

    private CardGrow() { }

    static void install(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", loader),
                "startActivityFromRecents", int.class, Bundle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Bundle options = (Bundle) param.args[1];
                        int[] card = options == null ? null : options.getIntArray(GROW_FROM);
                        if (card == null || card.length != 4) return;
                        options.remove(GROW_FROM);
                        if (Binder.getCallingUid() != Process.SYSTEM_UID) return;
                        param.setObjectExtra(CARD, new Rect(card[0], card[1], card[2], card[3]));
                        param.setObjectExtra(BACKDROP, backdrop(param.thisObject));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Rect card = (Rect) param.getObjectExtra(CARD);
                        if (card == null || !(param.getResult() instanceof Integer) || (Integer) param.getResult() < 0) return;
                        start(param.thisObject, (int) param.args[0], card, param.getObjectExtra(BACKDROP));
                    }
                });
        XposedBridge.log(PipHook.TAG + ": card grow hook installed");
    }

    /** A still of the main display without its PiP, as Wend shows it before the move; null if unavailable. */
    private static Object backdrop(Object atms) {
        try {
            Object windowManager = XposedHelpers.getObjectField(atms, "mWindowManager");
            Object display = XposedHelpers.callMethod(windowManager, "getDefaultDisplayContentLocked");
            // The display's own root layer: WindowContainer.getSurfaceControl() is null for a DisplayContent.
            SurfaceControl root = (SurfaceControl) XposedHelpers.callMethod(display, "getWindowingLayer");
            Object pinned = XposedHelpers.callMethod(display, "getPinnedStack");
            IBinder[] exclude = pinned == null ? new IBinder[0] : new IBinder[] {
                    (IBinder) XposedHelpers.callMethod(XposedHelpers.callMethod(pinned, "getSurfaceControl"), "getHandle") };
            Rect bounds = new Rect();
            XposedHelpers.callMethod(display, "getBounds", bounds);
            return XposedHelpers.callStaticMethod(SurfaceControl.class, "captureLayersExcluding",
                    XposedHelpers.callMethod(root, "getHandle"), bounds, 1f, exclude);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": no backdrop for the card grow; it grows over what Android shows: " + error);
            return null;
        }
    }

    private static void start(Object atms, int taskId, Rect card, Object backdrop) {
        try {
            Object record = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mRootActivityContainer"), "anyTaskForId", taskId);
            Object task = XposedHelpers.callMethod(record, "getTask");
            Object stack = XposedHelpers.getObjectField(task, "mStack");
            final SurfaceControl surface = (SurfaceControl) XposedHelpers.callMethod(task, "getSurfaceControl");
            Rect full = new Rect();
            XposedHelpers.callMethod(task, "getBounds", full);
            if (surface == null || full.isEmpty()) return;
            final SurfaceControl behind = backdrop == null ? null : backdropLayer(stack, full, backdrop);
            final float from = card.width() / (float) full.width();
            final float fromX = card.left - full.left;
            final float fromY = card.top - full.top;
            // In place before the first frame after the move is composed, so the task never shows fullscreen early.
            apply(surface, from, fromX, fromY);
            Handler animation = (Handler) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.AnimationThread", atms.getClass().getClassLoader()), "getHandler");
            animation.post(() -> {
                ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
                grow.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
                grow.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    try {
                        apply(surface, from + (1f - from) * f, fromX * (1f - f), fromY * (1f - f));
                        if (f >= 1f) finish(surface, behind);
                    } catch (Throwable error) {
                        XposedBridge.log(PipHook.TAG + ": card grow frame failed; showing the task fullscreen: " + error);
                        a.cancel();
                        finish(surface, behind);
                    }
                });
                grow.start();
            });
            XposedBridge.log(PipHook.TAG + ": growing task " + taskId + " from its card at " + card);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not grow task " + taskId + " from its card; it shows fullscreen at once: " + error);
        }
    }

    /** The backdrop as the lowest child of the moved task's stack, so it stays fullscreen while the task grows over it. */
    private static SurfaceControl backdropLayer(Object stack, Rect full, Object backdrop) {
        try {
            SurfaceControl parent = (SurfaceControl) XposedHelpers.callMethod(stack, "getSurfaceControl");
            SurfaceControl layer = new SurfaceControl.Builder().setName("WendGrowBackdrop").setParent(parent)
                    .setBufferSize(full.width(), full.height()).build();
            Surface surface = new Surface(layer);
            try { XposedHelpers.callMethod(surface, "attachAndQueueBuffer", XposedHelpers.callMethod(backdrop, "getGraphicBuffer")); }
            finally { surface.release(); }
            SurfaceControl.Transaction t = new SurfaceControl.Transaction().setLayer(layer, Integer.MIN_VALUE)
                    .setVisibility(layer, true);
            XposedHelpers.callMethod(t, "setPosition", layer, 0f, 0f);
            t.apply();
            return layer;
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": no backdrop layer for the card grow: " + error);
            return null;
        }
    }

    private static void apply(SurfaceControl surface, float scale, float x, float y) {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        XposedHelpers.callMethod(t, "setMatrix", surface, scale, 0f, 0f, scale);
        XposedHelpers.callMethod(t, "setPosition", surface, x, y);
        t.apply();
    }

    private static void finish(SurfaceControl surface, SurfaceControl behind) {
        apply(surface, 1f, 0f, 0f);
        if (behind == null) return;
        new SurfaceControl.Transaction().reparent(behind, null).apply();
        behind.release();
    }
}
