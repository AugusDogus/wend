package dev.augie.wend.piphook;

import android.os.Handler;
import android.os.SystemClock;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs in system_server. Waits, on a handler thread, for the main display's Home (Wend) window to
 * reach a {@link Stage}, so motion that returns things to Wend lines up with Wend appearing rather
 * than playing over the app it replaces.
 */
final class HomeShown {
    private static final long FRAME_MS = 16;

    enum Stage {
        /** Wend's window has drawn; Android shows it with its token a few frames later. */
        DRAWN,
        /** Wend's window surface is shown on screen. */
        ON_SCREEN
    }

    private HomeShown() { }

    /** Runs {@code then} on {@code thread} once Home reaches {@code stage}, or after {@code timeoutMs} regardless. */
    static void then(Object displayContent, Stage stage, Handler thread, long timeoutMs, Runnable then) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        thread.post(new Runnable() {
            @Override public void run() {
                if (reached(displayContent, stage) || SystemClock.uptimeMillis() >= deadline) then.run();
                else thread.postDelayed(this, FRAME_MS);
            }
        });
    }

    private static boolean reached(Object displayContent, Stage stage) {
        try {
            Object lock = XposedHelpers.getObjectField(XposedHelpers.getObjectField(displayContent, "mWmService"), "mGlobalLock");
            synchronized (lock) {
                Object home = XposedHelpers.callMethod(displayContent, "getHomeStack");
                if (home == null || !(boolean) XposedHelpers.callMethod(home, "isVisible")) return false;
                Object task = XposedHelpers.callMethod(home, "getTopChild");
                Object token = task == null ? null : XposedHelpers.callMethod(task, "getTopVisibleAppToken");
                if (token == null || !XposedHelpers.getBooleanField(token, "allDrawn")) return false;
                if (stage == Stage.DRAWN) return true;
                Object window = XposedHelpers.callMethod(token, "findMainWindow");
                return window != null && (boolean) XposedHelpers.callMethod(XposedHelpers.getObjectField(window, "mWinAnimator"), "getShown");
            }
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": cannot tell whether Home shows; not waiting for it: " + error);
            return true;
        }
    }
}
