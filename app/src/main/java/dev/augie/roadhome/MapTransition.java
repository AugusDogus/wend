package dev.augie.roadhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

/** Brief, in-memory visual bridge while the real Maps task changes displays once. */
final class MapTransition {
    private enum Completion { CROSSFADE, HANDOFF, REVEAL }
    private enum Phase { PENDING, WAITING_FOR_FRAME, REVEALING, DISPOSED }
    private final Activity host;
    private final View panel;
    private final WindowManager windows;
    private Cover cover;

    MapTransition(Activity host, View panel) {
        this.host = host;
        this.panel = panel;
        windows = host.getSystemService(WindowManager.class);
    }

    void move(boolean expanding, Runnable moveTask) {
        prepare(expanding, moveTask, Completion.CROSSFADE);
    }

    /** Keep the original pixels stationary until the native window animator is ready. */
    void hold(Runnable moveTask) { prepare(true, moveTask, Completion.HANDOFF); }

    /** Keep the final cropped frame still while Maps lays out, then remove it without fading. */
    void settle(Runnable moveTask) { prepare(false, moveTask, Completion.REVEAL); }

    /** After a slow expansion, keep its original cover until the fullscreen frame is ready. */
    void settleHeld() {
        if (cover != null && cover.phase == Phase.WAITING_FOR_FRAME) cover.awaitFrame();
    }

    private void prepare(boolean expanding, Runnable moveTask, Completion completion) {
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || windows == null || !panel.isLaidOut()) {
            moveTask.run();
            return;
        }
        Bitmap snapshot = null;
        try {
            int width = host.getResources().getDisplayMetrics().widthPixels;
            int height = host.getResources().getDisplayMetrics().heightPixels;
            int rotation = host.getWindowManager().getDefaultDisplay().getRotation();
            // This Android 10 overload excludes secure layers. No capture is stored or exported.
            Object result = SurfaceControl.class.getDeclaredMethod("screenshot", Rect.class,
                    int.class, int.class, int.class).invoke(null, new Rect(0, 0, width, height),
                    width, height, rotation);
            if (!(result instanceof Bitmap)) throw new IllegalStateException("No transition snapshot returned");
            snapshot = (Bitmap) result;
            Cover next = new Cover(snapshot, expanding ? width : panel.getWidth(), moveTask, completion);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setTitle("Road Home map transition");
            next.setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());
            windows.addView(next, params);
            cover = next;
            next.getViewTreeObserver().registerFrameCommitCallback(next::start);
            next.invalidate();
            // A missing render callback must never leave a cover over either app.
            next.postDelayed(next::start, 200);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Log.w("RoadHome", "Map transition unavailable; moving task without animation", error);
            if (cover != null) cancel();
            else if (snapshot != null) snapshot.recycle();
            moveTask.run();
        }
    }

    boolean active() { return cover != null; }

    boolean pending() { return cover != null && cover.phase == Phase.PENDING; }

    void cancel() {
        Cover current = cover;
        cover = null;
        if (current != null) current.dispose();
    }

    private final class Cover extends View {
        private final Bitmap snapshot;
        private final RectF screen;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int destinationWidth;
        private final Runnable moveTask;
        private final Completion completion;
        private MapFrame frame;
        private Phase phase = Phase.PENDING;
        private final Runnable deadline = () -> {
            Log.w("RoadHome", "Maps frame did not settle before transition deadline; revealing live task");
            reveal();
        };

        Cover(Bitmap snapshot, int destinationWidth, Runnable moveTask, Completion completion) {
            super(host);
            this.snapshot = snapshot;
            this.destinationWidth = destinationWidth;
            this.moveTask = moveTask;
            this.completion = completion;
            screen = new RectF(0, 0, snapshot.getWidth(), snapshot.getHeight());
            setClickable(true);
        }

        void start() {
            if (phase != Phase.PENDING) return;
            phase = Phase.WAITING_FOR_FRAME;
            moveTask.run();
            if (phase == Phase.DISPOSED) return;
            if (completion == Completion.HANDOFF) {
                postDelayed(deadline, 1500);
                return;
            }
            awaitFrame();
        }

        void awaitFrame() {
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            frame = new MapFrame(destinationWidth, () -> post(this::reveal));
            postDelayed(deadline, 900);
        }

        private void reveal() {
            if (phase != Phase.WAITING_FOR_FRAME) return;
            if (completion != Completion.CROSSFADE) { cancel(); return; }
            phase = Phase.REVEALING;
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            // Alpha runs on the cached render node. Never stretch Maps' controls,
            // redraw a bitmap, or allocate a clipping path on animation frames.
            animate().alpha(0).setDuration(160)
                    .setInterpolator(AnimationUtils.loadInterpolator(host, R.interpolator.window_ease_out))
                    .withEndAction(() -> {
                        if (cover == Cover.this) cancel();
                    }).start();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (phase != Phase.DISPOSED) canvas.drawBitmap(snapshot, null, screen, paint);
        }

        void dispose() {
            phase = Phase.DISPOSED;
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            animate().cancel();
            windows.removeViewImmediate(this);
            // RenderThread may still reference this hardware bitmap until its frame retires.
            // Let the bitmap's lifetime follow the detached cover instead of recycling early.
        }
    }
}
