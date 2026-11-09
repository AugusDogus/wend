package dev.augie.wend.piphook;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs in an app Wend shows in a panel (Google Maps, YouTube). Wend sends {@link #ACTION} as an
 * ordered broadcast; the app's resumed activity then enters its own PiP ({@link Entry}), and the hook
 * answers whether it did. The app renders its own PiP; this hook only chooses when. After the app
 * enters or leaves PiP, it reports when its new layout is drawn ({@link PipSignals}).
 */
final class PanelAppHook {
    /** How the app is asked into its own PiP. */
    enum Entry {
        /**
         * Its own {@code onUserLeaveHint}, the moment Maps itself decides to enter its navigation PiP,
         * exactly as if the user had left it for another app.
         */
        LEAVE_HINT,
        /**
         * Directly, as a 16:9 player: YouTube gates its own PiP on a setting and entitlement and
         * declines from {@code onUserLeaveHint}, but renders its player PiP once in it.
         */
        PLAYER
    }

    /**
     * Must match {@code dev.augie.wend.PanelPip.START}: where the app shows on screen, as
     * {left, top, right, bottom}, when its panel display is scaled (the video card), so system_server
     * starts the PiP there. Passed on as the PiP's source-rect hint, which {@link PanelPipHook} consumes.
     */
    private static final String START = "dev.augie.wend.START";
    /** Must match {@code dev.augie.wend.PanelPip.ACTION}. */
    static final String ACTION = "dev.augie.wend.action.ENTER_PIP_FROM_PANEL";
    /** Must match {@code dev.augie.wend.PanelPip.ENTERED}; an unanswered request keeps code 0. */
    private static final int ENTERED = 1;
    /** Must match {@code dev.augie.wend.PanelPip.DECLINED}. */
    private static final int DECLINED = 2;
    /** Only a caller allowed to rearrange tasks, such as Wend, may ask the app to leave for its PiP. */
    private static final String SENDER_PERMISSION = "android.permission.MANAGE_ACTIVITY_STACKS";

    /** How long after its PiP mode change the app may take to start swapping its layout. */
    private static final long SWAP_WAIT_MS = 250;
    /** Gives up on a layout that keeps changing; system_server reveals the PiP on its own cap anyway. */
    private static final long SETTLE_CAP_MS = 1200;

    /** The activity Wend floated from its panel, until it reports its PiP layout settled. */
    private static final AtomicReference<Activity> floated = new AtomicReference<>();
    /** Set while Wend's request runs: whether the app then entered PiP. */
    private static final AtomicReference<Boolean> entered = new AtomicReference<>();

    private PanelAppHook() { }

    static void install(final Entry entry) {
        final AtomicReference<Activity> resumed = new AtomicReference<>();
        final AtomicBoolean registered = new AtomicBoolean();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                setResultCode(enter(resumed.get(), entry, intent.getIntArrayExtra(START)) ? ENTERED : DECLINED);
            }
        };
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                resumed.set(activity);
                if (registered.compareAndSet(false, true)) {
                    activity.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION),
                            SENDER_PERMISSION, null);
                }
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                resumed.compareAndSet((Activity) param.thisObject, null);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "enterPictureInPictureMode", PictureInPictureParams.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() == null) entered.compareAndSet(false, (Boolean) param.getResult());
                    }
                });
        // The framework's dispatch, not onPictureInPictureModeChanged: Maps overrides that without
        // always calling through to Activity.
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchPictureInPictureModeChanged", boolean.class,
                Configuration.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        // Into PiP from Wend's panel, or out of PiP: system_server holds the app out of
                        // sight in both until it reports its new layout.
                        if (!(boolean) param.args[0] || floated.compareAndSet(activity, null)) reportWhenSettled(activity);
                    }
                });
        // Moved between displays without a PiP change (Maps from its panel to fullscreen):
        // system_server holds a still over it until it reports its new layout too. Not while pinned,
        // where the PiP mode change reports instead.
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchMovedToDisplay", int.class, Configuration.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        if (!activity.isInMultiWindowMode()) reportWhenSettled(activity);
                    }
                });
        XposedBridge.log(PipHook.TAG + ": " + entry + " hook installed");
    }

    /**
     * An app swaps its layout for its PiP one (or back) in the layout pass that follows its PiP mode
     * change. As soon as the set of views it shows has changed and that frame is committed, the new
     * layout is what the app shows, and system_server may reveal it. If nothing changes within
     * {@link #SWAP_WAIT_MS}, the app already shows its new layout: the next committed frame is reported.
     * Layout passes that only change text (the clock, the ETA) do not count as changes.
     */
    private static void reportWhenSettled(final Activity activity) {
        final View decor = activity.getWindow().getDecorView();
        final Handler main = new Handler(Looper.getMainLooper());
        final int shown = shownViews(decor);
        final AtomicBoolean reported = new AtomicBoolean();
        final AtomicBoolean waiting = new AtomicBoolean(true);
        final Runnable report = () -> {
            if (!reported.compareAndSet(false, true)) return;
            try { activity.setPictureInPictureParams(PipSignals.layoutSettled()); }
            catch (RuntimeException error) { XposedBridge.log(PipHook.TAG + ": could not report the app's layout: " + error); }
        };
        final ViewTreeObserver.OnGlobalLayoutListener[] listener = new ViewTreeObserver.OnGlobalLayoutListener[1];
        final Runnable afterNextFrame = () -> {
            if (!waiting.compareAndSet(true, false)) return;
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(listener[0]);
            decor.getViewTreeObserver().registerFrameCommitCallback(report);
            decor.invalidate();
        };
        listener[0] = () -> { if (shownViews(decor) != shown) afterNextFrame.run(); };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
        main.postDelayed(afterNextFrame, SWAP_WAIT_MS);
        main.postDelayed(report, SETTLE_CAP_MS);
    }

    /** How many views under {@code view} are shown with a nonzero size. */
    private static int shownViews(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) return 0;
        int count = 1;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += shownViews(group.getChildAt(i));
        }
        return count;
    }

    /**
     * @param start where the app shows on screen, or null when its panel is unscaled
     * @return whether the app entered its own PiP
     */
    private static boolean enter(Activity activity, Entry entry, int[] start) {
        if (activity == null) {
            XposedBridge.log(PipHook.TAG + ": PiP requested but no activity is resumed; the app stays where it is");
            return false;
        }
        if (activity.isInPictureInPictureMode()) return true;
        entered.set(false);
        try {
            if (entry == Entry.LEAVE_HINT) {
                Method leaveHint = onUserLeaveHint(activity.getClass());
                leaveHint.setAccessible(true);
                leaveHint.invoke(activity);
            } else {
                PictureInPictureParams.Builder params = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9));
                if (start != null && start.length == 4) params.setSourceRectHint(new Rect(start[0], start[1], start[2], start[3]));
                activity.enterPictureInPictureMode(params.build());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            XposedBridge.log(PipHook.TAG + ": the app could not be asked for its PiP; it stays in its panel: " + error);
        }
        boolean result = Boolean.TRUE.equals(entered.getAndSet(null));
        if (result) floated.set(activity);
        XposedBridge.log(PipHook.TAG + ": " + activity.getClass().getName()
                + (result ? " entered its own PiP" : " declined its PiP (not navigating?); it stays in its panel"));
        return result;
    }

    /**
     * Maps' most specific {@code onUserLeaveHint}. Looked up by name and signature only: scanning all
     * of Maps' methods, as {@code XposedHelpers.callMethod} does, resolves parameter types from newer
     * Android releases and throws on Android 10.
     */
    private static Method onUserLeaveHint(Class<?> type) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod("onUserLeaveHint"); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + ".onUserLeaveHint()");
    }
}
