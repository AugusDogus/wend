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
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. Returns a PiP that Wend moves back into one of its panels
 * ({@code startActivityFromRecents} onto a Wend panel display) with motion instead of a cut.
 *
 * <p>Stock Android 10 removes the PiP at once, then shows the app in the panel still at its PiP size
 * and snaps it to the panel's size when it relays out. Here a still of the PiP grows from the PiP
 * toward the panel while fading out, on the PiP shrink's curve and duration, and the app stays
 * hidden in its panel, which shows Wend's panel color, until it reports its layout settled
 * ({@link PipSignals}); it then fades in.
 */
final class PanelReturn {
    /** BoundsAnimationController.DEFAULT_TRANSITION_DURATION, as for the shrink into PiP. */
    private static final long DURATION_MS = 425;
    private static final long FADE_IN_MS = 180;
    private static final String STILL = "wendReturnStill";
    private static final String PANEL = "wendReturnPanel";

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
        SurfaceControl task = null;
        try {
            Object record = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mRootActivityContainer"), "anyTaskForId", taskId);
            task = (SurfaceControl) XposedHelpers.callMethod(XposedHelpers.callMethod(record, "getTask"), "getSurfaceControl");
            // Hidden until it has redrawn at the panel's size; the panel shows Wend's color meanwhile.
            if (task != null) new SurfaceControl.Transaction().setAlpha(task, 0f).apply();
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not hide task " + taskId + " while it relays out in its panel: " + error);
            task = null;
        }
        final SurfaceControl hidden = task;
        final Handler animation = (Handler) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.android.server.AnimationThread", atms.getClass().getClassLoader()), "getHandler");
        final SettleCover cover = hidden == null ? null : SettleCover.hold(thread -> fadeIn(thread, hidden), animation, "the app in its panel");
        animation.post(() -> {
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
                    t.apply();
                } catch (Throwable error) {
                    XposedBridge.log(PipHook.TAG + ": panel return frame failed: " + error);
                    a.cancel();
                }
            });
            grow.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator ignored) {
                    still.remove();
                    if (cover != null) cover.landed();
                }
            });
            grow.start();
        });
        XposedBridge.log(PipHook.TAG + ": returning task " + taskId + " from its PiP at " + pip + " to its panel at " + panel);
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
