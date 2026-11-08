package dev.augie.roadhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
    private enum Completion { CROSSFADE, HELD_CROSSFADE, HANDOFF, REVEAL }
    private enum Phase { PENDING, WAITING_FOR_FRAME, REVEALING, DISPOSED }
    /** Waiting for Maps' frame after a move under this cover. */
    private static final int FRAME_MS = 900;
    /**
     * Waiting for Maps' frame after a held move. Maps has usually laid out at its new size before
     * it moves; otherwise it finishes live, as a frozen frame much over a second reads as a hang.
     */
    private static final int MOVED_FRAME_MS = 300;
    /** A held frame whose work never reports the move. */
    private static final int HELD_MS = 1500;
    private final Activity host;
    private final View panel;
    private final WindowManager windows;
    private Cover cover;
    /** A held takeover with no frozen frame: revealed once {@link #settleHeld} says Maps moved. */
    private Runnable heldReveal;

    MapTransition(Activity host, View panel) {
        this.host = host;
        this.panel = panel;
        windows = host.getSystemService(WindowManager.class);
    }

    void move(boolean expanding, Runnable moveTask) { move(expanding, moveTask, () -> { }); }

    /** @param revealed runs once Maps shows with no cover left above it; never if canceled */
    void move(boolean expanding, Runnable moveTask, Runnable revealed) {
        prepare(expanding, moveTask, Completion.CROSSFADE, revealed);
    }

    /**
     * Keep the original pixels stationary until {@link #settleHeld}, then crossfade once Maps'
     * frame is ready, as when Maps first lays out at a new size on its own display.
     * @param revealed runs once Maps shows with no cover left above it; never if canceled
     */
    void holdThenCrossfade(Runnable covered, Runnable revealed) {
        prepare(true, covered, Completion.HELD_CROSSFADE, revealed);
    }

    /** Keep the original pixels stationary until the native window animator is ready. */
    void hold(Runnable moveTask) { prepare(true, moveTask, Completion.HANDOFF, () -> { }); }

    /** Keep the final cropped frame still while Maps lays out, then remove it without fading. */
    void settle(Runnable moveTask) { prepare(false, moveTask, Completion.REVEAL, () -> { }); }

    /**
     * Maps has moved: keep the frozen frame until Maps shows its new size, which it usually
     * already does, but never long after the move.
     */
    void settleHeld() {
        Runnable reveal = heldReveal;
        heldReveal = null;
        if (reveal != null) reveal.run();
        else if (cover != null && cover.phase == Phase.WAITING_FOR_FRAME) cover.awaitFrame(MOVED_FRAME_MS);
    }

    private void prepare(boolean expanding, Runnable moveTask, Completion completion, Runnable revealed) {
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || windows == null || !panel.isLaidOut()) {
            moveUncovered(moveTask, completion, revealed);
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
            Cover next = new Cover(snapshot, expanding ? width : panel.getWidth(), moveTask, completion, revealed);
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
            moveUncovered(moveTask, completion, revealed);
        }
    }

    private void moveUncovered(Runnable moveTask, Completion completion, Runnable revealed) {
        // A held takeover's work only prepares the move; Maps shows once it has moved.
        if (completion == Completion.HELD_CROSSFADE) heldReveal = revealed;
        moveTask.run();
        if (completion != Completion.HELD_CROSSFADE) revealed.run();
    }

    boolean active() { return cover != null || heldReveal != null; }

    /**
     * Blanks part of the frozen frame whose live content has just moved to another window,
     * such as a video leaving its card, so the cover never shows it twice.
     */
    void conceal(Rect area) {
        if (cover != null && area != null && !area.isEmpty()) cover.conceal(area);
    }

    boolean pending() { return cover != null && cover.phase == Phase.PENDING; }

    void cancel() {
        heldReveal = null;
        Cover current = cover;
        cover = null;
        if (current != null) current.dispose();
    }

    private final class Cover extends View {
        private final Bitmap snapshot;
        private final RectF screen;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint blank = new Paint();
        private final Rect concealed = new Rect();
        private final int destinationWidth;
        private final Runnable moveTask;
        private final Completion completion;
        private final Runnable revealed;
        private MapFrame frame;
        private Phase phase = Phase.PENDING;
        private final Runnable deadline = this::deadlinePassed;

        Cover(Bitmap snapshot, int destinationWidth, Runnable moveTask, Completion completion, Runnable revealed) {
            super(host);
            this.snapshot = snapshot;
            this.destinationWidth = destinationWidth;
            this.moveTask = moveTask;
            this.completion = completion;
            this.revealed = revealed;
            screen = new RectF(0, 0, snapshot.getWidth(), snapshot.getHeight());
            // The empty video card is black in Road Home as well.
            blank.setColor(Color.BLACK);
            setClickable(true);
        }

        void conceal(Rect area) {
            concealed.union(area);
            invalidate();
        }

        void start() {
            if (phase != Phase.PENDING) return;
            phase = Phase.WAITING_FOR_FRAME;
            moveTask.run();
            if (phase != Phase.WAITING_FOR_FRAME) return;
            if (completion == Completion.HANDOFF || completion == Completion.HELD_CROSSFADE) {
                // Held until settleHeld, unless that already ran as the work moved Maps.
                if (frame == null) postDelayed(deadline, HELD_MS);
                return;
            }
            awaitFrame(FRAME_MS);
        }

        /** @param deadlineMs how long Maps may take to show its new size before it shows as it is */
        void awaitFrame(int deadlineMs) {
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            frame = new MapFrame(destinationWidth, () -> post(this::reveal));
            postDelayed(deadline, deadlineMs);
        }

        private void deadlinePassed() {
            Log.w("RoadHome", "Maps did not show its " + destinationWidth + " px wide layout before its"
                    + " transition deadline; revealing it as it is, and it finishes laying out live");
            reveal();
        }

        private void reveal() {
            if (phase != Phase.WAITING_FOR_FRAME) return;
            if (completion == Completion.HANDOFF || completion == Completion.REVEAL) { cancel(); return; }
            phase = Phase.REVEALING;
            removeCallbacks(deadline);
            if (frame != null) frame.close();
            // Alpha runs on the cached render node. Never stretch Maps' controls,
            // redraw a bitmap, or allocate a clipping path on animation frames.
            animate().alpha(0).setDuration(160)
                    .setInterpolator(AnimationUtils.loadInterpolator(host, R.interpolator.window_ease_out))
                    .withEndAction(() -> {
                        if (cover != Cover.this) return;
                        cancel();
                        revealed.run();
                    }).start();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (phase == Phase.DISPOSED) return;
            canvas.drawBitmap(snapshot, null, screen, paint);
            if (!concealed.isEmpty()) canvas.drawRect(concealed, blank);
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
