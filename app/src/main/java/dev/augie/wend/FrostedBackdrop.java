package dev.augie.wend;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.view.SurfaceControl;

/**
 * Android 10 backdrop blur. Captures stay in memory and never include secure layers.
 * One worker thread keeps a single RenderScript context, so only the first blur pays
 * for its creation. The screenshot is reported before blurring so callers can draw
 * over the screen as soon as its pixels are taken.
 */
final class FrostedBackdrop {
    interface Listener {
        /** The screen pixels are taken; windows drawn after this are absent from the blur. */
        void onCaptured();
        /** Called after {@link #onCaptured()} with a blurred, display-proportioned bitmap. */
        void onBlurred(Bitmap bitmap);
        /** Capture or blur failed; may follow {@link #onCaptured()}. The failure is logged. */
        void onUnavailable();
    }

    /** One capture in flight. Any thread may ask whether its pixels are already taken. */
    static final class Capture {
        private volatile boolean taken;

        /** True once the screen pixels are taken, possibly before onCaptured is delivered. */
        boolean isTaken() { return taken; }
    }

    private static final int CAPTURE_WIDTH = 320;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Wend-backdrop", Process.THREAD_PRIORITY_DISPLAY);
    private final Handler worker;
    // Worker-thread state.
    private RenderScript renderScript;
    private ScriptIntrinsicBlur blur;

    FrostedBackdrop(Context context) {
        this.context = context.getApplicationContext();
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::warmUp);
    }

    Capture capture(Activity activity, Listener listener) {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        Capture capture = new Capture();
        worker.post(() -> captureOnWorker(rotation, width, height, capture, listener));
        return capture;
    }

    /** Releases the RenderScript context and stops the worker after any queued capture. */
    void release() {
        worker.post(() -> {
            if (blur != null) blur.destroy();
            if (renderScript != null) renderScript.destroy();
            blur = null;
            renderScript = null;
        });
        thread.quitSafely();
    }

    private void captureOnWorker(int rotation, int width, int height, Capture capture, Listener listener) {
        if (Build.VERSION.SDK_INT != 29 || Process.myUid() != 1000) {
            Log.w("Wend", "Backdrop blur needs Android 10 and the platform signature; using translucent glass");
            main.post(listener::onUnavailable);
            return;
        }
        try {
            long started = SystemClock.uptimeMillis();
            Object result = SurfaceControl.class.getDeclaredMethod("screenshot", Rect.class,
                    int.class, int.class, int.class).invoke(null, new Rect(0, 0, width, height),
                    CAPTURE_WIDTH, Math.max(1, CAPTURE_WIDTH * height / width), rotation);
            if (!(result instanceof Bitmap)) {
                Log.w("Wend", "Backdrop capture returned no bitmap; using translucent glass without blur");
                main.post(listener::onUnavailable);
                return;
            }
            long captured = SystemClock.uptimeMillis();
            capture.taken = true;
            main.post(listener::onCaptured);
            Bitmap screen = (Bitmap) result;
            Bitmap pixels;
            try { pixels = screen.copy(Bitmap.Config.ARGB_8888, true); }
            finally { screen.recycle(); }
            if (pixels == null) throw new IllegalStateException("Could not copy backdrop pixels into a software bitmap");
            try { blur(pixels); }
            catch (RuntimeException error) { pixels.recycle(); throw error; }
            Log.i("Wend", "Backdrop captured in " + (captured - started) + " ms and blurred in "
                    + (SystemClock.uptimeMillis() - captured) + " ms");
            main.post(() -> listener.onBlurred(pixels));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w("Wend", "Backdrop blur unavailable; using translucent glass", error);
            main.post(listener::onUnavailable);
        }
    }

    /** Creating the RenderScript context takes 25 to 50 ms; do it before the first opening. */
    private void warmUp() {
        long started = SystemClock.uptimeMillis();
        try {
            createBlur();
            Log.i("Wend", "Backdrop blur prepared in " + (SystemClock.uptimeMillis() - started) + " ms");
        } catch (RuntimeException | LinkageError error) {
            Log.w("Wend", "Could not prepare the backdrop blur early; the first opening retries", error);
        }
    }

    @SuppressWarnings("deprecation")
    private void createBlur() {
        if (renderScript != null) return;
        renderScript = RenderScript.create(context);
        blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript));
        blur.setRadius(8);
    }

    @SuppressWarnings("deprecation")
    private void blur(Bitmap pixels) {
        createBlur();
        Allocation input = Allocation.createFromBitmap(renderScript, pixels);
        try {
            Allocation output = Allocation.createTyped(renderScript, input.getType());
            try {
                blur.setInput(input);
                blur.forEach(output);
                output.copyTo(pixels);
            } finally { output.destroy(); }
        } finally { input.destroy(); }
    }
}
