package dev.augie.wend.hook;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.util.Rational;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Forces YouTube to enter its own native player picture-in-picture when it is backgrounded, which
 * YouTube otherwise declines to do on command. Runs in the YouTube process under LSPosed; it does
 * not repackage or re-sign YouTube, so its Google signature stays intact.
 *
 * <p>The Wend launcher already makes the task switch (the app-op allowed and
 * {@code supportsEnterPipOnTaskSwitch} set); this hook only makes YouTube call
 * {@code enterPictureInPictureMode}, so YouTube renders its own mini-player.
 */
public final class YouTubePipHook implements IXposedHookLoadPackage {
    private static final String YOUTUBE = "com.google.android.youtube";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!YOUTUBE.equals(lpparam.packageName)) return;
        XC_MethodHook enter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!PipDecision.shouldEnter(activity.getClass().getName(),
                        activity.isFinishing(), activity.isInPictureInPictureMode())) {
                    return;
                }
                try {
                    activity.enterPictureInPictureMode(new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9)).build());
                } catch (RuntimeException error) {
                    XposedBridge.log("youtube-pip-hook: enterPictureInPictureMode refused: " + error);
                }
            }
        };
        // The user leaving the watch activity for another task is the real auto-PiP moment; onPause
        // is the fallback when onUserLeaveHint is not dispatched.
        XposedHelpers.findAndHookMethod(Activity.class, "onUserLeaveHint", enter);
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", enter);
        XposedBridge.log("youtube-pip-hook: installed in " + lpparam.packageName);
    }
}
