package dev.augie.wend.piphook;

import android.graphics.Point;
import android.graphics.Rect;
import android.animation.ValueAnimator;
import android.app.PictureInPictureParams;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Display;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs in system_server (Android 10, AOSP android-10.0.0_r47 {@code com.android.server.wm}).
 *
 * <p>Stock Android pins an activity on the display it is on and starts the shrink from its stack's
 * bounds there. For an app inside a Wend panel (an ActivityView virtual display) that would put the
 * PiP on the hidden virtual display. Instead, as {@code RootActivityContainer.moveActivityToPinnedStack}
 * starts, this moves the app's stack to the main display with surface layout deferred (so it is never
 * drawn fullscreen there), and makes the pinned stack start at the panel's on-screen bounds. Android's
 * own bounds animation then shrinks the app from its panel into the PiP corner.
 */
final class PanelPipHook {
    private static final Set<String> PANEL_HOSTS = new HashSet<>(Arrays.asList("dev.augie.wend", "dev.augie.wend.emulator"));
    /** The panel bounds the pinned stack starts from, set for the duration of one pinning. */
    private static final ThreadLocal<Rect> START = new ThreadLocal<>();
    /** The panel bounds of the shrink just scheduled, until its bounds animation starts on the animation thread. */
    private static final AtomicReference<Rect> SHRINKING = new AtomicReference<>();
    /** The TaskStack shrinking from a panel, until its bounds animation ends. */
    private static final AtomicReference<Object> LANDING = new AtomicReference<>();
    /** Shrink frames applied and when the shrink was scheduled, for the log line as it lands. */
    private static final AtomicInteger shrinkFrames = new AtomicInteger();
    private static volatile long shrinkScheduledAt;
    private static final String DEFERRED = "wendDeferredWindowManager";

    private PanelPipHook() { }

    static void install(ClassLoader loader) {
        Class<?> root = XposedHelpers.findClass("com.android.server.wm.RootActivityContainer", loader);
        Class<?> activityRecord = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", loader);
        Class<?> activityStack = XposedHelpers.findClass("com.android.server.wm.ActivityStack", loader);
        XposedHelpers.findAndHookMethod(root, "moveActivityToPinnedStack", activityRecord, Rect.class, float.class,
                String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) { leavePanel(param); }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        START.remove();
                        Object windowManager = param.getObjectExtra(DEFERRED);
                        if (windowManager != null) XposedHelpers.callMethod(windowManager, "continueSurfaceLayout");
                    }
                });
        XposedHelpers.findAndHookMethod(root, "resizeStack", activityStack, Rect.class, Rect.class, Rect.class,
                boolean.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Rect start = START.get();
                        if (start == null || !(boolean) XposedHelpers.callMethod(param.args[0], "inPinnedWindowingMode")) return;
                        START.remove();
                        param.args[1] = new Rect(start);
                        SHRINKING.set(new Rect(start));
                    }
                });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.BoundsAnimationController", loader),
                "animateBounds", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 8 || !(param.args[1] instanceof Rect)) return;
                        Rect panel = SHRINKING.get();
                        if (panel == null || !panel.equals(param.args[1]) || !SHRINKING.compareAndSet(panel, null)) return;
                        LANDING.set(param.args[0]);
                        shrinkFrames.set(0);
                        shrinkScheduledAt = SystemClock.uptimeMillis();
                    }
                });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.BoundsAnimationController$BoundsAnimator", loader),
                "onAnimationUpdate", ValueAnimator.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object target = LANDING.get();
                        if (target != null && target == XposedHelpers.getObjectField(param.thisObject, "mTarget")) {
                            shrinkFrames.incrementAndGet();
                        }
                    }
                });
        // Stock Android holds a PiP shrink until the app opening below has drawn its first frame, so
        // the app sat frozen at its full panel size over the new app's snapshot. A panel shrink
        // starts at once: the panel is already on screen, and what opens below is not covered by it.
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.TaskStack", loader),
                "shouldDeferStartOnMoveToFullscreen", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (LANDING.get() == param.thisObject) param.setResult(false);
                    }
                });
        // Android 10 tells the app it is in PiP, and resizes it, only as the shrink ends. Maps then
        // draws a blank frame and its full layout crammed into the PiP before its own PiP card: the
        // bridge holds the last frame of the shrink over Maps until Maps reports its card is drawn.
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.android.server.wm.TaskStack", loader),
                "onAnimationEnd", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!LANDING.compareAndSet(param.thisObject, null)) return;
                        XposedBridge.log(PipHook.TAG + ": shrink landed after " + (SystemClock.uptimeMillis() - shrinkScheduledAt)
                                + " ms with " + shrinkFrames.get() + " frames");
                        Rect landed = param.args.length > 1 && param.args[1] instanceof Rect ? (Rect) param.args[1] : null;
                        if (landed != null) LandingBridge.cover(param.thisObject, landed);
                    }
                });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", loader),
                "setPictureInPictureParams", IBinder.class, PictureInPictureParams.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!PipSignals.isLayoutSettled((PictureInPictureParams) param.args[1])) return;
                        param.setResult(null);
                        LandingBridge.reveal("Maps reported its PiP layout settled");
                    }
                });
        XposedBridge.log(PipHook.TAG + ": system_server hook installed");
    }

    private static void leavePanel(XC_MethodHook.MethodHookParam param) {
        try {
            Object stack = XposedHelpers.callMethod(param.args[0], "getActivityStack");
            Object display = XposedHelpers.callMethod(stack, "getDisplay");
            if (XposedHelpers.getIntField(display, "mDisplayId") == Display.DEFAULT_DISPLAY) return;
            Rect panel = panelBounds(display);
            if (panel == null) return;
            Object windowManager = XposedHelpers.getObjectField(param.thisObject, "mWindowManager");
            XposedHelpers.callMethod(windowManager, "deferSurfaceLayout");
            param.setObjectExtra(DEFERRED, windowManager);
            // displayRemoved = true skips postReparent's resume and visibility pass, which would show
            // the app fullscreen over Wend and hide Wend's window for a few frames. The pinning that
            // follows runs both passes itself once the app is in the pinned stack.
            XposedHelpers.callMethod(stack, "reparent", XposedHelpers.callMethod(param.thisObject, "getDefaultDisplay"),
                    true /* onTop */, true /* displayRemoved */);
            // The app's source-rect hint is in its panel's coordinates; the shrink runs on the main display.
            Rect hint = (Rect) param.args[1];
            if (hint != null && !hint.isEmpty()) hint.offset(panel.left, panel.top);
            START.set(panel);
            XposedBridge.log(PipHook.TAG + ": pinning " + param.args[0] + " from its panel at " + panel
                    + ", hint " + hint);
        } catch (Throwable error) {
            // Before the move nothing changed; Android pins the app the stock way.
            XposedBridge.log(PipHook.TAG + ": could not start PiP from the panel, pinning the stock way: " + error);
        }
    }

    /** The on-screen bounds of a Wend panel's virtual display, or null if the display is not a Wend panel. */
    private static Rect panelBounds(Object activityDisplay) {
        Object content = XposedHelpers.getObjectField(activityDisplay, "mDisplayContent");
        if (content == null) return null;
        Object parent = XposedHelpers.callMethod(content, "getParentWindow");
        if (parent == null || !PANEL_HOSTS.contains(XposedHelpers.callMethod(parent, "getOwningPackage"))) return null;
        Point location = (Point) XposedHelpers.callMethod(content, "getLocationInParentWindow");
        Rect frame = (Rect) XposedHelpers.callMethod(parent, "getFrameLw");
        Object info = XposedHelpers.callMethod(content, "getDisplayInfo");
        int left = frame.left + location.x;
        int top = frame.top + location.y;
        return new Rect(left, top, left + XposedHelpers.getIntField(info, "logicalWidth"),
                top + XposedHelpers.getIntField(info, "logicalHeight"));
    }
}
