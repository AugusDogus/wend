package dev.augie.wend.piphook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Handler;
import android.view.SurfaceControl;
import android.view.animation.PathInterpolator;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. When Home covers a fullscreen task that Wend grew out of a card
 * ({@link CardGrow}), shrinks that task back into its card instead of the cut Android 10 makes.
 *
 * <p>The video keeps playing until Wend's window is drawn. Then a still of it, without the PiP, is
 * placed at the top of Wend's window token: above Wend and below the PiP, so it appears in the very
 * frame Wend replaces the video and the PiP stays visible. It then shrinks into the card on the PiP
 * shrink's curve and duration, and fades out over the card.
 */
final class CardReturn {
    private static final long DURATION_MS = 425;
    /** Home normally shows within a few frames; the still never waits longer than this for it. */
    private static final long HOME_TIMEOUT_MS = 1000;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    /**
     * How long after Wend's window is marked shown the shrink starts. SurfaceFlinger composites
     * Wend's first full frame about 80 ms later and then stalls for about 60 ms; a shrink started
     * before that visibly skipped there.
     */
    static final long START_DELAY_MS = 150;
    /**
     * The still is not quite opaque, which is invisible but makes SurfaceFlinger compose Wend beneath
     * it during the hold. Fully opaque, Wend was first composed at the shrink's first frame, which
     * then took about 70 ms.
     */
    private static final float ALMOST_OPAQUE = 0.99f;

    private CardReturn() { }

    static void install(ClassLoader loader) {
        // Every way Home comes back (the Home key, a HOME intent) moves the Home stack to the front.
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.ActivityStack", loader),
                "moveToFront", String.class, XposedHelpers.findClass("com.android.server.wm.TaskRecord", loader), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object home = param.thisObject;
                            if (!(boolean) XposedHelpers.callMethod(home, "isActivityTypeHome")
                                    || (boolean) XposedHelpers.callMethod(home, "isTopStackOnDisplay")) return;
                            Object display = XposedHelpers.callMethod(home, "getDisplay");
                            if (display == null || XposedHelpers.getIntField(display, "mDisplayId") != 0) return;
                            start(XposedHelpers.getObjectField(home, "mRootActivityContainer"));
                        } catch (Throwable error) {
                            XposedBridge.log(PipHook.TAG + ": could not check Home coming back: " + error);
                        }
                    }
                });
        XposedBridge.log(PipHook.TAG + ": card return hook installed");
    }

    private static void start(Object root) {
        try {
            Object display = XposedHelpers.callMethod(root, "getDefaultDisplay");
            Object stack = XposedHelpers.callMethod(display, "getTopStackInWindowingMode", WINDOWING_MODE_FULLSCREEN);
            if (stack == null || (boolean) XposedHelpers.callMethod(stack, "isActivityTypeHome")
                    || !(boolean) XposedHelpers.callMethod(stack, "shouldBeVisible", (Object) null)) return;
            Object task = XposedHelpers.callMethod(stack, "topTask");
            if (task == null) return;
            int taskId = XposedHelpers.getIntField(task, "taskId");
            final Rect card = CardGrow.cardOf(taskId);
            if (card == null) return;
            final Object displayContent = XposedHelpers.getObjectField(display, "mDisplayContent");
            final Rect full = new Rect();
            XposedHelpers.callMethod(displayContent, "getBounds", full);
            final Object videoTask = XposedHelpers.callMethod(task, "getTask");
            // The video shows until Wend's window does; this still is only a fallback for a fresh one.
            final ScreenStill.Capture early = ScreenStill.Capture.takeWithout(displayContent, full,
                    XposedHelpers.callMethod(displayContent, "getPinnedStack"));
            if (early == null) return;
            final Handler animation = MotionThread.get();
            HomeShown.then(displayContent, HomeShown.Stage.DRAWN, animation, HOME_TIMEOUT_MS, () -> {
                Object[] token = new Object[1];
                ScreenStill.Capture shot = fresh(displayContent, full, videoTask, token);
                if (token[0] == null) return;
                // Part of Wend's window token, so it appears in the very frame Wend replaces the video.
                ScreenStill still = ScreenStill.showIn((SurfaceControl) XposedHelpers.callMethod(token[0], "getSurfaceControl"),
                        shot == null ? early : shot, full, "WendCardReturnStill", ALMOST_OPAQUE);
                if (still == null) return;
                HomeShown.then(displayContent, HomeShown.Stage.ON_SCREEN, animation, HOME_TIMEOUT_MS,
                        () -> animation.postDelayed(() -> shrink(still, full, card, animation), START_DELAY_MS));
            });
            XposedBridge.log(PipHook.TAG + ": returning task " + taskId + " to its card at " + card);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not return the fullscreen task to its card; Home cuts to Wend: " + error);
        }
    }

    /**
     * Under the window manager's lock, so Android cannot hide the video meanwhile: a still of the
     * video as it shows right now, if it still shows, and Wend's window token.
     */
    private static ScreenStill.Capture fresh(Object displayContent, Rect full, Object videoTask, Object[] token) {
        try {
            Object lock = XposedHelpers.getObjectField(XposedHelpers.getObjectField(displayContent, "mWmService"), "mGlobalLock");
            synchronized (lock) {
                Object home = XposedHelpers.callMethod(displayContent, "getHomeStack");
                Object homeTask = home == null ? null : XposedHelpers.callMethod(home, "getTopChild");
                token[0] = homeTask == null ? null : XposedHelpers.callMethod(homeTask, "getTopVisibleAppToken");
                if (!(boolean) XposedHelpers.callMethod(videoTask, "isVisible")) return null;
                return ScreenStill.Capture.takeWithout(displayContent, full,
                        XposedHelpers.callMethod(displayContent, "getPinnedStack"), home);
            }
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": no fresh still of the video; using the one from Home's start: " + error);
            return null;
        }
    }

    private static void shrink(ScreenStill still, Rect from, Rect card, Handler thread) {
        ValueAnimator shrink = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
        shrink.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        final Rect at = new Rect();
        shrink.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            at.set(lerp(from.left, card.left, f), lerp(from.top, card.top, f), lerp(from.right, card.right, f), lerp(from.bottom, card.bottom, f));
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                still.place(t, at);
                t.apply();
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": card return frame failed: " + error);
                a.cancel();
            }
        });
        shrink.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ignored) { still.reveal(thread); }
        });
        shrink.start();
    }

    private static int lerp(int from, int to, float f) { return Math.round(from + (to - from) * f); }
}
