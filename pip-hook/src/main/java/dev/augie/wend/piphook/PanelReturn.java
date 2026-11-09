package dev.augie.wend.piphook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.view.SurfaceControl;
import android.view.animation.PathInterpolator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. Returns a PiP that Wend moves back into one of its panels
 * ({@code startActivityFromRecents} onto a Wend panel display) with motion instead of a cut.
 *
 * <p>Stock Android 10 removes the PiP at once, then shows the app in the panel still at its PiP size
 * and snaps it to the panel's size when it relays out. Here a still of the PiP grows from the PiP
 * toward the panel while crossfading into a picture of the panel taken as the app left it, on the
 * PiP shrink's curve and duration; the picture covers the app until it reports its layout settled
 * ({@link PipSignals}), then fades out over it, as Android's own task snapshots do.
 */
final class PanelReturn {
    /** BoundsAnimationController.DEFAULT_TRANSITION_DURATION, as for the shrink into PiP. */
    private static final long DURATION_MS = 425;
    private static final long FADE_IN_MS = 180;
    private static final long HOME_TIMEOUT_MS = 1000;
    /**
     * The panel picture's alpha while it waits under the PiP still: unseen there, but composed, so
     * its buffer is on the GPU before the motion. At 0 it was first composed, and uploaded, on the
     * motion's first frame, which then took about 55 ms.
     */
    private static final float BARELY_VISIBLE = 0.01f;
    private static final String STILL = "wendReturnStill";
    private static final String PANEL = "wendReturnPanel";

    /** The panel each floated task left, by task id, until it returns. */
    private static final Map<Integer, ScreenStill.Capture> panelPictures = new ConcurrentHashMap<>();

    private PanelReturn() { }

    static void install(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", loader),
                "startActivityFromRecents", int.class, Bundle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) { prepare(param); }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        ScreenStill still = (ScreenStill) param.getObjectExtra(STILL);
                        if (still == null) return;
                        if (!(param.getResult() instanceof Integer) || (Integer) param.getResult() < 0) {
                            still.remove();
                            return;
                        }
                        start(param.thisObject, (int) param.args[0], still, (Rect[]) param.getObjectExtra(PANEL));
                    }
                });
        XposedBridge.log(PipHook.TAG + ": panel return hook installed");
    }

    /** Before the move: if a pinned task is going into a Wend panel, cover the PiP with a still of it. */
    private static void prepare(XC_MethodHook.MethodHookParam param) {
        try {
            if (Binder.getCallingUid() != Process.SYSTEM_UID || param.args[1] == null) return;
            ActivityOptions options = (ActivityOptions) XposedHelpers.callStaticMethod(ActivityOptions.class, "fromBundle", param.args[1]);
            if (options == null || options.getLaunchDisplayId() <= 0) return;
            Object root = XposedHelpers.getObjectField(param.thisObject, "mRootActivityContainer");
            Object record = XposedHelpers.callMethod(root, "anyTaskForId", (int) param.args[0]);
            Object stack = record == null ? null : XposedHelpers.callMethod(record, "getStack");
            if (stack == null || !(boolean) XposedHelpers.callMethod(stack, "inPinnedWindowingMode")) return;
            Rect panel = PanelPipHook.panelBounds(XposedHelpers.callMethod(root, "getActivityDisplay", options.getLaunchDisplayId()));
            if (panel == null) return;
            // Where it left from on screen: a scaled panel (the video card) shows smaller than its display.
            ScreenStill.Capture left = panelPictures.get((int) param.args[0]);
            if (left != null) panel = new Rect(left.bounds);
            Rect pip = new Rect((Rect) XposedHelpers.callMethod(stack, "getBounds"));
            ScreenStill still = ScreenStill.show(displayContent(param.thisObject), pip, "WendPanelReturnStill");
            if (still == null) return;
            param.setObjectExtra(STILL, still);
            param.setObjectExtra(PANEL, new Rect[] { pip, panel });
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not prepare the PiP's return to its panel; it returns the stock way: " + error);
        }
    }

    private static void start(Object atms, int taskId, ScreenStill still, Rect[] bounds) {
        final Rect pip = bounds[0];
        final Rect panel = bounds[1];
        final Handler animation = MotionThread.get();
        // The panel as it was when the app left it: the PiP morphs into it, and it covers the app
        // until the app has redrawn there, as Android's own task snapshots do.
        ScreenStill.Capture left = panelPictures.remove(taskId);
        final ScreenStill picture = left == null || !left.bounds.equals(panel) ? null
                : ScreenStill.show(displayContent(atms), left, pip, "WendPanelPicture", Integer.MAX_VALUE - 1, BARELY_VISIBLE);
        final SettleCover cover;
        if (picture != null) {
            cover = SettleCover.hold(taskId, picture, animation, "the app in its panel");
        } else {
            SurfaceControl hidden = hide(atms, taskId);
            cover = hidden == null ? null : SettleCover.hold(taskId, thread -> fadeIn(thread, hidden), animation, "the app in its panel");
        }
        // With Wend's Home, in the frame Wend appears, together with the video's return to its card.
        HomeShown.then(displayContent(atms), HomeShown.Stage.ON_SCREEN, animation, HOME_TIMEOUT_MS, () -> animation.postDelayed(() -> {
            ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
            grow.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
            final Rect at = new Rect();
            grow.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                at.set(lerp(pip.left, panel.left, f), lerp(pip.top, panel.top, f), lerp(pip.right, panel.right, f), lerp(pip.bottom, panel.bottom, f));
                try {
                    SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                    still.place(t, at);
                    still.alpha(t, 1f - f);
                    if (picture != null) {
                        picture.place(t, at);
                        picture.alpha(t, Math.max(BARELY_VISIBLE, f));
                    }
                    t.apply();
                } catch (Throwable error) {
                    XposedBridge.log(PipHook.TAG + ": panel return frame failed: " + error);
                    a.cancel();
                }
            });
            grow.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator ignored) {
                    if (picture != null) {
                        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                        picture.place(t, panel);
                        picture.alpha(t, 1f);
                        t.apply();
                    }
                    still.remove();
                    if (cover != null) cover.landed();
                }
            });
            grow.start();
        }, CardReturn.START_DELAY_MS));
        XposedBridge.log(PipHook.TAG + ": returning task " + taskId + " from its PiP at " + pip + " to its panel at " + panel
                + (picture == null ? " (no panel picture)" : ""));
    }

    /** Hides the app until it has redrawn at its panel's size; the panel shows Wend's color meanwhile. */
    private static SurfaceControl hide(Object atms, int taskId) {
        try {
            Object record = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mRootActivityContainer"), "anyTaskForId", taskId);
            SurfaceControl task = (SurfaceControl) XposedHelpers.callMethod(XposedHelpers.callMethod(record, "getTask"), "getSurfaceControl");
            if (task != null) new SurfaceControl.Transaction().setAlpha(task, 0f).apply();
            return task;
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not hide task " + taskId + " while it relays out in its panel: " + error);
            return null;
        }
    }

    /** Remembers the panel {@code taskId} is leaving, as it leaves, for its return. */
    static void panelLeft(int taskId, ScreenStill.Capture panel) {
        if (panel != null) panelPictures.put(taskId, panel);
    }

    private static void fadeIn(Handler thread, SurfaceControl task) {
        thread.post(() -> {
            ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_IN_MS);
            fade.addUpdateListener(a -> {
                try { new SurfaceControl.Transaction().setAlpha(task, (float) a.getAnimatedValue()).apply(); }
                catch (Throwable error) { XposedBridge.log(PipHook.TAG + ": panel fade-in frame failed: " + error); }
            });
            fade.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator ignored) {
                    try { new SurfaceControl.Transaction().setAlpha(task, 1f).apply(); }
                    catch (Throwable error) { XposedBridge.log(PipHook.TAG + ": could not show the returned app: " + error); }
                }
            });
            fade.start();
        });
    }

    private static int lerp(int from, int to, float f) { return Math.round(from + (to - from) * f); }

    private static Object displayContent(Object atms) {
        return XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mWindowManager"), "getDefaultDisplayContentLocked");
    }
}
