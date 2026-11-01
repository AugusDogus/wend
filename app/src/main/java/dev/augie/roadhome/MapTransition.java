package dev.augie.roadhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
    private enum Phase { PENDING, ANIMATING, WAITING_FOR_FRAME, REVEALING, DISPOSED }
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
            int[] position = new int[2];
            panel.getLocationOnScreen(position);
            Rect bounds = new Rect(position[0], position[1], position[0] + panel.getWidth(),
                    position[1] + panel.getHeight());
            Cover next = new Cover(snapshot, bounds, expanding, moveTask);
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

    boolean pending() { return cover != null && cover.phase == Phase.PENDING; }

    void cancel() {
        Cover current = cover;
        cover = null;
        if (current != null) current.dispose();
    }

    private final class Cover extends View {
        private final Bitmap snapshot;
        private final Rect source;
        private final RectF start;
        private final RectF end;
        private final RectF current = new RectF();
        private final RectF screen;
        private final Path clip = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final boolean expanding;
        private final Runnable moveTask;
        private ValueAnimator animator;
        private MapFrame frame;
        private Phase phase = Phase.PENDING;
        private boolean frameReady;
        private final Runnable deadline = () -> {
            Log.w("RoadHome", "Maps frame did not settle before transition deadline; revealing live task");
            frameReady = true;
            reveal();
        };
        private float progress;

        Cover(Bitmap snapshot, Rect bounds, boolean expanding, Runnable moveTask) {
            super(host);
            this.snapshot = snapshot;
            this.expanding = expanding;
            this.moveTask = moveTask;
            screen = new RectF(0, 0, snapshot.getWidth(), snapshot.getHeight());
            start = expanding ? new RectF(bounds) : new RectF(screen);
            end = expanding ? new RectF(screen) : new RectF(bounds);
            source = expanding ? new Rect(bounds) : new Rect(0, 0, snapshot.getWidth(), snapshot.getHeight());
            setClickable(true);
        }

        void start() {
            if (phase != Phase.PENDING) return;
            phase = Phase.ANIMATING;
            moveTask.run();
            if (phase == Phase.DISPOSED) return;
            frame = new MapFrame(Math.round(end.width()), () -> post(() -> {
                frameReady = true;
                reveal();
            }));
            postDelayed(deadline, 900);
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(expanding ? 260 : 220);
            animator.setInterpolator(AnimationUtils.loadInterpolator(host, R.interpolator.window_ease_out));
            animator.addUpdateListener(value -> {
                progress = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (phase == Phase.ANIMATING) {
                        phase = Phase.WAITING_FOR_FRAME;
                        reveal();
                    }
                }
            });
            animator.start();
        }

        private void reveal() {
            if (phase != Phase.WAITING_FOR_FRAME || !frameReady) return;
            phase = Phase.REVEALING;
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            animate().alpha(0).setDuration(80).withEndAction(() -> {
                if (cover == Cover.this) cancel();
            }).start();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (phase == Phase.DISPOSED) return;
            paint.setAlpha(expanding ? 255 : Math.round(255 * (1 - progress)));
            canvas.drawBitmap(snapshot, null, screen, paint);
            current.set(lerp(start.left, end.left, progress), lerp(start.top, end.top, progress),
                    lerp(start.right, end.right, progress), lerp(start.bottom, end.bottom, progress));
            float radius = Ui.dp(host, 20) * (expanding ? 1 - progress : progress);
            boolean onRight = (expanding ? start.left : end.left) > Ui.dp(host, 60) + 1;
            float[] corners = onRight
                    ? new float[]{radius, radius, 0, 0, 0, 0, radius, radius}
                    : new float[]{0, 0, radius, radius, radius, radius, 0, 0};
            clip.reset();
            clip.addRoundRect(current, corners, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setAlpha(255);
            canvas.drawBitmap(snapshot, source, current, paint);
            canvas.restore();
        }

        void dispose() {
            phase = Phase.DISPOSED;
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            if (animator != null) animator.cancel();
            animate().cancel();
            windows.removeViewImmediate(this);
            // RenderThread may still reference this hardware bitmap until its frame retires.
            // Let the bitmap's lifetime follow the detached cover instead of recycling early.
        }
    }

    private static float lerp(float from, float to, float progress) { return from + (to - from) * progress; }
}
