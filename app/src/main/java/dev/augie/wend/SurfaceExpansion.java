package dev.augie.wend;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

/** Expands an owned ActivityView surface, then transfers its existing task once. */
final class SurfaceExpansion {
    private enum Phase { PREPARING, ANIMATING, HANDOFF, CLOSED }
    private final Activity host;
    private final WindowManager windows;
    private Running running;

    SurfaceExpansion(Activity host) {
        this.host = host;
        windows = host.getSystemService(WindowManager.class);
    }

    boolean active() { return running != null; }
    boolean handingOff() { return running != null && running.phase == Phase.HANDOFF; }

    void open(View card, ActivityViewSurface surface, RectF source,
            int width, int height, Runnable open) {
        open(card, surface, source, width, height, () -> { }, open);
    }

    void open(View card, ActivityViewSurface surface, RectF source,
            int width, int height, Runnable reveal, Runnable open) {
        if (running != null) return;
        if (!ValueAnimator.areAnimatorsEnabled() || !card.isLaidOut() || windows == null) {
            open.run();
            return;
        }
        int[] location = new int[2];
        card.getLocationOnScreen(location);
        Running next = new Running(surface, new PanelWindowMotion(location[0], location[1],
                card.getWidth(), card.getHeight(), source, width, height), reveal, open);
        running = next;
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setTitle("Wend panel expansion");
            next.view.setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());
            windows.addView(next.view, params);
            next.view.getViewTreeObserver().registerFrameCommitCallback(next::start);
            next.view.invalidate();
            next.view.postDelayed(next.deadline, 700);
        } catch (RuntimeException error) { next.fail(error); }
    }

    void cancel() {
        if (running != null) running.finish(false);
    }

    private final class Running {
        final View view = new View(host);
        final ActivityViewSurface surface;
        final PanelWindowMotion motion;
        final Runnable open;
        final Runnable reveal;
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        final Rect crop = new Rect();
        final Runnable deadline = () -> fail(new IllegalStateException("Panel expansion surface was not ready within 700 ms"));
        Phase phase = Phase.PREPARING;

        Running(ActivityViewSurface surface, PanelWindowMotion motion, Runnable reveal, Runnable open) {
            this.surface = surface;
            this.motion = motion;
            this.open = open;
            this.reveal = reveal;
            view.setBackgroundColor(Color.TRANSPARENT);
            animator.setDuration(260);
            animator.setInterpolator(AnimationUtils.loadInterpolator(host, R.interpolator.window_ease_out));
            animator.addUpdateListener(value -> frame(value.getAnimatedFraction()));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { finish(true); }
            });
        }

        void start() {
            if (phase != Phase.PREPARING) return;
            view.removeCallbacks(deadline);
            try {
                motion.crop(0, crop);
                surface.attach(view, motion.scale(0), motion.x(0), motion.y(0), crop);
                phase = Phase.ANIMATING;
                reveal.run();
                animator.start();
            } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }

        void frame(float fraction) {
            if (phase != Phase.ANIMATING) return;
            motion.crop(fraction, crop);
            try { surface.transform(motion.scale(fraction), motion.x(fraction), motion.y(fraction), crop); }
            catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }

        void fail(Exception error) {
            if (phase == Phase.CLOSED) return;
            Log.w("WendSurface", "Live panel expansion unavailable; opening the existing task directly", error);
            finish(true);
        }

        void finish(boolean launch) {
            if (phase == Phase.CLOSED || phase == Phase.HANDOFF) return;
            boolean restore = phase == Phase.ANIMATING;
            phase = Phase.HANDOFF;
            view.removeCallbacks(deadline);
            animator.cancel();
            // Keep the live display visible until WindowManager transfers the task.
            // Hiding/removing it first exposes Home for a frame on a warm transfer.
            if (launch) open.run();
            if (restore) {
                try { surface.restore(); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    Log.e("WendSurface", "Could not restore the embedded surface after expansion", error);
                }
            }
            if (view.isAttachedToWindow()) windows.removeViewImmediate(view);
            phase = Phase.CLOSED;
            running = null;
        }
    }
}
