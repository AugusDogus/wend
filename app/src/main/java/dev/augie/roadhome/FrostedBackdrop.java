package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.view.SurfaceControl;

/** Android 10 backdrop blur. Captures stay in memory and never include secure layers. */
final class FrostedBackdrop {
    interface Listener {
        void onCaptured(Bitmap bitmap);
        void onUnavailable();
    }

    private FrostedBackdrop() { }

    static void capture(Activity activity, Listener listener) {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            try {
                Object result = SurfaceControl.class.getDeclaredMethod("screenshot", Rect.class,
                        int.class, int.class, int.class).invoke(null, new Rect(0, 0, width, height),
                        320, Math.max(1, 320 * height / width), rotation);
                if (!(result instanceof Bitmap)) {
                    Log.w("RoadHome", "Backdrop capture unavailable; using translucent glass without blur");
                    activity.runOnUiThread(listener::onUnavailable);
                    return;
                }
                Bitmap capture = (Bitmap) result;
                Bitmap pixels;
                try { pixels = capture.copy(Bitmap.Config.ARGB_8888, true); }
                finally { capture.recycle(); }
                if (pixels == null) throw new IllegalStateException("Could not copy backdrop pixels");
                try { blur(activity, pixels); }
                catch (RuntimeException error) { pixels.recycle(); throw error; }
                activity.runOnUiThread(() -> listener.onCaptured(pixels));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                Log.w("RoadHome", "Backdrop blur unavailable; using translucent glass", error);
                activity.runOnUiThread(listener::onUnavailable);
            }
        }, "RoadHome-backdrop").start();
    }

    @SuppressWarnings("deprecation")
    private static void blur(Activity activity, Bitmap pixels) {
        RenderScript rs = RenderScript.create(activity.getApplicationContext());
        try {
            Allocation input = Allocation.createFromBitmap(rs, pixels);
            try {
                Allocation output = Allocation.createTyped(rs, input.getType());
                try {
                    ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
                    try {
                        blur.setRadius(8);
                        blur.setInput(input);
                        blur.forEach(output);
                        output.copyTo(pixels);
                    } finally { blur.destroy(); }
                } finally { output.destroy(); }
            } finally { input.destroy(); }
        } finally { rs.destroy(); }
    }
}
