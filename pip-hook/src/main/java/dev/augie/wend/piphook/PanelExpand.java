package dev.augie.wend.piphook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
 * Runs in system_server. Expands an app Wend moves out of an unscaled panel (Maps) to fullscreen on
 * the main display, instead of the cut Android 10 makes: there the app first shows at its panel's
 * size against the screen's left edge, then snaps to fullscreen once it has relaid out.
 *
 * <p>Wend asks for it with the panel's screen bounds under {@link #EXPAND_FROM} in the options of
 * {@code startActivityFromRecents}. A still of the panel grows to fullscreen on the PiP shrink's
 * curve and duration, over a still of the screen as it was, while the app stays hidden; the panel
 * still then covers the app until it reports its new layout ({@link PipSignals}), and fades out.
 * Both stills are children of the app's stack, so a PiP stays above them.
 */
final class PanelExpand {
    /** Must match {@code dev.augie.wend.MapsTask.EXPAND_FROM}: {left, top, right, bottom} on screen. */
    static final String EXPAND_FROM = "dev.augie.wend.EXPAND_FROM";
    private static final long DURATION_MS = 425;
    private static final String PANEL = "wendExpandPanel";
    private static final String SCREEN = "wendExpandScreen";
    private static final String FROM = "wendExpandFrom";
    private static final String DEFERRED = "wendExpandDeferred";

    private PanelExpand() { }

    static void install(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", loader),
                "startActivityFromRecents", int.class, Bundle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Bundle options = (Bundle) param.args[1];
                        int[] from = options == null ? null : options.getIntArray(EXPAND_FROM);
                        if (from == null || from.length != 4) return;
                        options.remove(EXPAND_FROM);
                        if (Binder.getCallingUid() != Process.SYSTEM_UID) return;
                        try {
                            Rect panel = new Rect(from[0], from[1], from[2], from[3]);
                            Object displayContent = XposedHelpers.callMethod(
                                    XposedHelpers.getObjectField(param.thisObject, "mWindowManager"), "getDefaultDisplayContentLocked");
                            Rect full = new Rect();
                            XposedHelpers.callMethod(displayContent, "getBounds", full);
                            Object pinned = XposedHelpers.callMethod(displayContent, "getPinnedStack");
                            ScreenStill.Capture screen = ScreenStill.Capture.takeWithout(displayContent, full, pinned);
                            if (screen == null) return;
                            param.setObjectExtra(PANEL, screen.part(panel));
                            param.setObjectExtra(SCREEN, screen);
                            param.setObjectExtra(FROM, panel);
                            // Until the stills are up and the app hidden: Android would otherwise show
                            // the app at its panel's size against the left edge for a frame.
                            Object windowManager = XposedHelpers.getObjectField(param.thisObject, "mWindowManager");
                            XposedHelpers.callMethod(windowManager, "deferSurfaceLayout");
                            param.setObjectExtra(DEFERRED, windowManager);
                        } catch (Throwable error) {
                            XposedBridge.log(PipHook.TAG + ": could not prepare the panel expansion; the app cuts to fullscreen: " + error);
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object windowManager = param.getObjectExtra(DEFERRED);
                        try {
                            ScreenStill.Capture panel = (ScreenStill.Capture) param.getObjectExtra(PANEL);
                            ScreenStill.Capture screen = (ScreenStill.Capture) param.getObjectExtra(SCREEN);
                            if (panel == null || screen == null || !(param.getResult() instanceof Integer) || (Integer) param.getResult() < 0) return;
                            start(param.thisObject, (int) param.args[0], (Rect) param.getObjectExtra(FROM), panel, screen);
                        } finally {
                            if (windowManager != null) XposedHelpers.callMethod(windowManager, "continueSurfaceLayout");
                        }
                    }
                });
        XposedBridge.log(PipHook.TAG + ": panel expand hook installed");
    }

    private static void start(Object atms, int taskId, Rect from, ScreenStill.Capture panel, ScreenStill.Capture screen) {
        try {
            Object record = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mRootActivityContainer"), "anyTaskForId", taskId);
            Object task = XposedHelpers.callMethod(record, "getTask");
            final SurfaceControl app = (SurfaceControl) XposedHelpers.callMethod(task, "getSurfaceControl");
            SurfaceControl stack = (SurfaceControl) XposedHelpers.callMethod(XposedHelpers.getObjectField(task, "mStack"), "getSurfaceControl");
            final Rect full = new Rect(screen.bounds);
            final ScreenStill behind = ScreenStill.showIn(stack, screen, full, "WendExpandScreen", 1f);
            final ScreenStill growing = ScreenStill.showIn(stack, panel, from, "WendExpandPanel", 1f);
            if (behind == null || growing == null || app == null) {
                if (behind != null) behind.remove();
                if (growing != null) growing.remove();
                return;
            }
            // The still beneath lies under the app's task, the growing one above it.
            new SurfaceControl.Transaction().setLayer(behind.layer(), Integer.MIN_VALUE).setAlpha(app, 0f).apply();
            final Handler animation = MotionThread.get();
            final SettleCover cover = SettleCover.hold(taskId, growing, animation, "the expanded app");
            animation.post(() -> {
                ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
                grow.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
                final Rect at = new Rect();
                grow.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    at.set(lerp(from.left, full.left, f), lerp(from.top, full.top, f), lerp(from.right, full.right, f), lerp(from.bottom, full.bottom, f));
                    try {
                        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                        growing.place(t, at);
                        t.apply();
                    } catch (Throwable error) {
                        XposedBridge.log(PipHook.TAG + ": panel expand frame failed: " + error);
                        a.cancel();
                    }
                });
                grow.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator ignored) {
                        // The panel still now covers the whole screen: the app shows beneath it.
                        try { new SurfaceControl.Transaction().setAlpha(app, 1f).apply(); }
                        catch (Throwable error) { XposedBridge.log(PipHook.TAG + ": could not show the expanded app: " + error); }
                        behind.remove();
                        cover.landed();
                    }
                });
                grow.start();
            });
            XposedBridge.log(PipHook.TAG + ": expanding task " + taskId + " from its panel at " + from);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not expand task " + taskId + " from its panel; it cuts to fullscreen: " + error);
        }
    }

    private static int lerp(int from, int to, float f) { return Math.round(from + (to - from) * f); }
}
