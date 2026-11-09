package dev.augie.wend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Asks Google Maps, navigating in its panel, to enter its own navigation PiP straight from the panel.
 * The request is answered only on a device with the {@code pip-hook} LSPosed module (see
 * {@code pip-hook/README.md}): in Maps it runs Maps' own leave hint, and in system_server it starts
 * Android's PiP shrink from the panel's bounds. Without the module nothing answers and nothing moves.
 */
final class MapsPanelPip {
    /** Must match {@code dev.augie.wend.piphook.MapsPipHook.ACTION}. */
    static final String ACTION = "dev.augie.wend.action.ENTER_PIP_FROM_PANEL";
    /** The initial result code: no hook answered, so Maps is untouched in its panel. */
    private static final int UNANSWERED = 0;
    /** Maps entered its own PiP; Android is shrinking it from the panel. */
    private static final int ENTERED = 1;
    /** The hook ran but Maps chose not to enter (for example, not navigating); it stays in its panel. */
    private static final int DECLINED = 2;

    interface Result {
        void entered();
        /** Maps stayed in its panel; {@code why} says whether the hook is missing or Maps declined. */
        void stayed(String why);
    }

    private MapsPanelPip() { }

    /** Answers {@code result} once, on the main thread. */
    static void request(Context context, Result result) {
        Intent intent = new Intent(ACTION).setPackage(EmbeddedMaps.PACKAGE)
                // The answer gates the video opening; the background queue can hold it for seconds.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent answered) {
                int code = getResultCode();
                if (code == ENTERED) { result.entered(); return; }
                String why = code == DECLINED ? "Maps declined its own PiP"
                        : code == UNANSWERED ? "the pip-hook LSPosed module did not answer (not installed or not enabled for Maps)"
                        : "unexpected answer " + code;
                Log.i("WendPip", "Maps stays in its panel for this float: " + why);
                result.stayed(why);
            }
        }, null, UNANSWERED, null, null);
    }
}
