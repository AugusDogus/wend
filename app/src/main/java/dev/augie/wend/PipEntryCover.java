package dev.augie.wend;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

/**
 * A full-screen held still of whatever the main display shows, drawn in a TYPE_APPLICATION_OVERLAY
 * window above every app. Case B's entry uses it to hide, behind the video, the moment Maps is
 * launched fullscreen on the main display and settles into active navigation so it can arm its own
 * PiP: the still of the fullscreen video stays on screen while Maps arms unseen beneath it, then
 * {@link #reveal} crossfades it away so SystemUI's own native PiP shrink is the only visible motion.
 */
final class PipEntryCover {
    private final Activity host;
    private final WindowManager windows;
    private View cover;
    private boolean revealing;

    PipEntryCover(Activity host) {
        this.host = host;
        windows = host.getSystemService(WindowManager.class);
    }

    boolean active() { return cover != null; }

    /**
     * Captures the main display now and holds it above every app until {@link #reveal} or
     * {@link #dispose}. Meant to be called once the video shows fullscreen, so the still is the
     * video, before Maps is launched fullscreen over it.
     *
     * @param onShown runs once the still has drawn its first frame and is covering the screen, so
     *     the caller can launch Maps behind it without Maps flashing before the still appears; it
     *     runs immediately when no still could be shown, so the caller always proceeds
     */
    void cover(Runnable onShown) {
        Runnable once = new Runnable() {
            private boolean ran;
            @Override public void run() { if (!ran) { ran = true; onShown.run(); } }
        };
        if (cover != null || windows == null) { once.run(); return; }
        int width = host.getResources().getDisplayMetrics().widthPixels;
        int height = host.getResources().getDisplayMetrics().heightPixels;
        Bitmap snapshot;
        try {
            int rotation = host.getWindowManager().getDefaultDisplay().getRotation();
            // This Android 10 overload excludes secure layers. No capture is stored or exported.
            Object result = SurfaceControl.class.getDeclaredMethod("screenshot", Rect.class,
                    int.class, int.class, int.class).invoke(null, new Rect(0, 0, width, height),
                    width, height, rotation);
            if (!(result instanceof Bitmap)) throw new IllegalStateException("No screen still returned");
            snapshot = (Bitmap) result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w("WendPip", "Could not capture the video still for Maps' PiP entry; Maps' brief"
                    + " fullscreen arming will show instead of the video, but its own PiP still works", error);
            once.run();
            return;
        }
        Still still = new Still(snapshot);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setTitle("Wend PiP entry cover");
        still.setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());
        try {
            windows.addView(still, params);
        } catch (RuntimeException error) {
            Log.w("WendPip", "Could not show the video still for Maps' PiP entry; Maps' brief fullscreen"
                    + " arming will show instead, but its own PiP still works", error);
            snapshot.recycle();
            once.run();
            return;
        }
        cover = still;
        // Launch Maps only once the still has actually drawn, so Maps never composites before the
        // still covers it. A missing render callback must still let the caller proceed.
        still.getViewTreeObserver().registerFrameCommitCallback(once);
        still.invalidate();
        still.postDelayed(once, 200);
    }

    /** Crossfades the still away so the native PiP shrink shows beneath it. Idempotent. */
    void reveal() {
        if (cover == null || revealing) return;
        revealing = true;
        View current = cover;
        current.animate().alpha(0).setDuration(200)
                .setInterpolator(AnimationUtils.loadInterpolator(host, R.interpolator.window_ease_out))
                .withEndAction(() -> { if (cover == current) dispose(); }).start();
    }

    /** Removes the still at once, whatever its state. */
    void dispose() {
        revealing = false;
        View current = cover;
        cover = null;
        if (current == null) return;
        current.animate().cancel();
        if (current.isAttachedToWindow() && windows != null) windows.removeViewImmediate(current);
    }

    private final class Still extends View {
        private final Bitmap snapshot;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dest = new Rect();

        Still(Bitmap snapshot) {
            super(host);
            this.snapshot = snapshot;
            // Consume touches so a stray tap never reaches Maps while it arms behind the still.
            setClickable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            dest.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(snapshot, null, dest, paint);
        }
    }
}
