package dev.augie.wend.piphook;

import android.app.PictureInPictureParams;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/**
 * The private signal from the Maps hook to the system_server hook. Maps' own
 * {@code setPictureInPictureParams} carries a source-rect hint no real layout can have; system_server
 * consumes it instead of storing it.
 */
final class PipSignals {
    private static final Rect LAYOUT_SETTLED = new Rect(Integer.MIN_VALUE, 0x57454E44, Integer.MIN_VALUE, 0x57454E44);

    private PipSignals() { }

    static PictureInPictureParams layoutSettled() {
        return new PictureInPictureParams.Builder().setSourceRectHint(new Rect(LAYOUT_SETTLED)).build();
    }

    static boolean isLayoutSettled(PictureInPictureParams params) {
        // Hidden on Android 10 (public from API 33), present in system_server.
        return params != null && LAYOUT_SETTLED.equals(XposedHelpers.callMethod(params, "getSourceRectHint"));
    }
}
