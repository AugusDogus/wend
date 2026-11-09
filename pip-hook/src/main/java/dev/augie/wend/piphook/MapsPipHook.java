package dev.augie.wend.piphook;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * Runs in Google Maps. Wend sends {@link #ACTION} as an ordered broadcast while Maps navigates in its
 * panel; Maps' resumed activity then gets {@code onUserLeaveHint}, the moment Maps itself decides to
 * enter its own navigation PiP, exactly as if the user had left it for another app. Maps renders its
 * own PiP card; this hook only chooses when, and answers whether Maps entered.
 */
final class MapsPipHook {
    /** Must match {@code dev.augie.wend.MapsPanelPip.ACTION}. */
    static final String ACTION = "dev.augie.wend.action.ENTER_PIP_FROM_PANEL";
    /** Must match {@code dev.augie.wend.MapsPanelPip.ENTERED}; an unanswered request keeps code 0. */
    private static final int ENTERED = 1;
    /** Must match {@code dev.augie.wend.MapsPanelPip.DECLINED}. */
    private static final int DECLINED = 2;
    /** Only a caller allowed to rearrange tasks, such as Wend, may ask Maps to leave for its PiP. */
    private static final String SENDER_PERMISSION = "android.permission.MANAGE_ACTIVITY_STACKS";

    /** How long Maps' layout must stay unchanged after it enters PiP before its PiP card counts as drawn. */
    private static final long SETTLE_MS = 150;
    /** Gives up on a layout that keeps changing; system_server reveals the PiP on its own cap anyway. */
    private static final long SETTLE_CAP_MS = 1200;

    /** The activity Wend floated from its panel, until it reports its PiP layout settled. */
    private static final AtomicReference<Activity> floated = new AtomicReference<>();
    /** Set while Wend's request runs Maps' leave hint: whether Maps then entered PiP. */
    private static final AtomicReference<Boolean> entered = new AtomicReference<>();

    private MapsPipHook() { }

    static void install() {
        final AtomicReference<Activity> resumed = new AtomicReference<>();
        final AtomicBoolean registered = new AtomicBoolean();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                setResultCode(enter(resumed.get()) ? ENTERED : DECLINED);
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
                        if ((boolean) param.args[0] && floated.compareAndSet(activity, null)) reportWhenSettled(activity);
                    }
                });
        XposedBridge.log(PipHook.TAG + ": Maps hook installed");
    }

    /**
     * Maps swaps its full layout for its PiP card in the layout passes that follow its PiP mode
     * change. Once the set of views it shows has changed and then stayed the same for {@link #SETTLE_MS}, the card is
     * what Maps shows, and system_server may fade out the still it holds over the PiP. Layout passes
     * that only change text (the clock, the ETA) do not count as changes.
     */
    private static void reportWhenSettled(final Activity activity) {
        final View decor = activity.getWindow().getDecorView();
        final Handler main = new Handler(Looper.getMainLooper());
        final long deadline = SystemClock.uptimeMillis() + SETTLE_CAP_MS;
        final int[] shown = { shownViews(decor) };
        final ViewTreeObserver.OnGlobalLayoutListener[] listener = new ViewTreeObserver.OnGlobalLayoutListener[1];
        final AtomicBoolean reported = new AtomicBoolean();
        final Runnable report = new Runnable() {
            @Override public void run() {
                if (!reported.compareAndSet(false, true)) return;
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(listener[0]);
                try { activity.setPictureInPictureParams(PipSignals.layoutSettled()); }
                catch (RuntimeException error) { XposedBridge.log(PipHook.TAG + ": could not report Maps' PiP layout: " + error); }
            }
        };
        listener[0] = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                int now = shownViews(decor);
                if (now == shown[0]) return;
                shown[0] = now;
                main.removeCallbacks(report);
                main.postAtTime(report, Math.min(SystemClock.uptimeMillis() + SETTLE_MS, deadline));
            }
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(listener[0]);
        // The card swap comes a few hundred ms after this callback: wait for a change, never just quiet.
        main.postAtTime(report, deadline);
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

    /** @return whether Maps entered its own PiP */
    private static boolean enter(Activity activity) {
        if (activity == null) {
            XposedBridge.log(PipHook.TAG + ": PiP requested but no Maps activity is resumed; Maps stays where it is");
            return false;
        }
        if (activity.isInPictureInPictureMode()) return true;
        entered.set(false);
        try {
            Method leaveHint = onUserLeaveHint(activity.getClass());
            leaveHint.setAccessible(true);
            leaveHint.invoke(activity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            XposedBridge.log(PipHook.TAG + ": Maps could not be asked for its PiP; it stays in its panel: " + error);
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
