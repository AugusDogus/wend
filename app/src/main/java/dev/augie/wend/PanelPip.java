package dev.augie.wend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.Log;

/**
 * Asks an app Wend shows in a panel (Maps navigating, the video in its card) to enter its own PiP
 * straight from the panel. The request is answered only on a device with the {@code pip-hook}
 * LSPosed module (see {@code pip-hook/README.md}): in the app it makes the app enter its own PiP,
 * and in system_server it starts Android's PiP shrink from the panel. Without the module nothing
 * answers and nothing moves.
 */
final class PanelPip {
    /** Must match {@code dev.augie.wend.piphook.PanelAppHook.ACTION}. */
    static final String ACTION = "dev.augie.wend.action.ENTER_PIP_FROM_PANEL";
    /** Must match {@code dev.augie.wend.piphook.PanelAppHook.START}. */
    private static final String START = "dev.augie.wend.START";
    /** The initial result code: no hook answered, so the app is untouched in its panel. */
    private static final int UNANSWERED = 0;
    /** The app entered its own PiP; Android is shrinking it from the panel. */
    private static final int ENTERED = 1;
    /** The hook ran but the app chose not to enter (Maps when not navigating); it stays in its panel. */
    private static final int DECLINED = 2;

    interface Result {
        void entered();
        /** The app stayed in its panel; {@code why} says whether the hook is missing or the app declined. */
        void stayed(String why);
    }

    private PanelPip() { }

    /**
     * Answers {@code result} once, on the main thread.
     * @param shownAt where the app shows on screen when its panel display is scaled (the video
     *     card), for its PiP to start there; null for an unscaled panel
     */
    static void request(Context context, String packageName, Rect shownAt, Result result) {
        Intent intent = new Intent(ACTION).setPackage(packageName)
                // The answer gates the video opening; the background queue can hold it for seconds.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (shownAt != null) intent.putExtra(START, new int[] { shownAt.left, shownAt.top, shownAt.right, shownAt.bottom });
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent answered) {
                int code = getResultCode();
                if (code == ENTERED) { result.entered(); return; }
                String why = code == DECLINED ? packageName + " declined its own PiP"
                        : code == UNANSWERED ? "the pip-hook LSPosed module did not answer (not installed or not enabled for " + packageName + ")"
                        : "unexpected answer " + code;
                Log.i("WendPip", packageName + " stays in its panel for this float: " + why);
                result.stayed(why);
            }
        }, null, UNANSWERED, null, null);
    }
}
