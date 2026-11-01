package dev.augie.roadhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;

/** Presents the same live virtual display at either size. Never moves or restarts its task. */
final class LiveVideoWindow {
    static final String TITLE = BuildConfig.APPLICATION_ID + " live video";
    private enum Phase { WAITING, EXPANDING, FULLSCREEN, COLLAPSING, CLOSED }
    private final Activity host;
    private final WindowManager windows;
    private Running running;

    LiveVideoWindow(Activity host) { this.host = host; windows = host.getSystemService(WindowManager.class); }
    boolean active() { return running != null; }

    void open(View card, ActivityViewSurface surface, RectF source, int width, int height,
            Runnable fallback, Runnable restored) {
        if (active()) return;
        if (windows == null || !card.isLaidOut()) { fallback.run(); return; }
        int[] location = new int[2];
        card.getLocationOnScreen(location);
        Running next = new Running(surface, new Rect(location[0], location[1],
                location[0] + card.getWidth(), location[1] + card.getHeight()), source,
                width, height, fallback, restored);
        running = next;
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.setTitle(TITLE);
            next.overlay.setSystemUiVisibility(host.getWindow().getDecorView().getSystemUiVisibility());
            windows.addView(next.overlay, params);
            host.registerReceiver(next.home, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            next.listening = true;
            next.overlay.getViewTreeObserver().registerFrameCommitCallback(next::start);
            next.overlay.invalidate();
            next.overlay.postDelayed(next.deadline, 700);
        } catch (RuntimeException error) { next.fail(error); }
    }

    void collapse() { if (running != null) running.collapse(); }
    void cancel() { if (running != null) running.finish(); }
    void updateSource(RectF source) {
        if (running != null && running.phase == Phase.FULLSCREEN) running.updateSource(source);
    }

    private final class Running {
        final View overlay = new View(host);
        final ActivityViewSurface surface;
        PanelWindowMotion motion, preview;
        final int cardX, cardY, cardWidth, cardHeight;
        final Rect crop = new Rect();
        final Rect fullscreen;
        final Runnable fallback, restored;
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        final Runnable deadline = () -> fail(new IllegalStateException("Live video window was not ready within 700 ms"));
        Phase phase = Phase.WAITING;
        boolean listening;
        final BroadcastReceiver home = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                        || !"homekey".equals(intent.getStringExtra("reason"))) return;
                // Android 10 sends Home to the last touched virtual display, which cannot
                // host Home. Route that system notification to the physical Home task.
                // Start before animating: this synchronous Binder call can occupy several
                // animation frames. MainActivity's Home intent starts the live return.
                host.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClass(host, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            }
        };

        Running(ActivityViewSurface surface, Rect card, RectF source,
                int width, int height, Runnable fallback, Runnable restored) {
            this.surface = surface;
            cardX = card.left; cardY = card.top; cardWidth = card.width(); cardHeight = card.height();
            this.fallback = fallback; this.restored = restored;
            fullscreen = new Rect(0, 0, width, height);
            updateSource(source);
            overlay.setBackgroundColor(Color.TRANSPARENT);
            animator.setDuration(280);
            animator.setInterpolator(AnimationUtils.loadInterpolator(host, R.interpolator.live_window_motion));
            animator.addUpdateListener(value -> frame((float) value.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (phase == Phase.EXPANDING) {
                        phase = Phase.FULLSCREEN;
                        surface.input(fullscreen);
                    } else if (phase == Phase.COLLAPSING) finish();
                }
            });
        }

        void updateSource(RectF source) {
            motion = new PanelWindowMotion(cardX, cardY, cardWidth, cardHeight, source,
                    fullscreen.width(), fullscreen.height());
            preview = new PanelWindowMotion(0, 0, cardWidth, cardHeight, source,
                    fullscreen.width(), fullscreen.height());
        }

        void start() {
            if (phase != Phase.WAITING) return;
            overlay.removeCallbacks(deadline);
            try {
                motion.crop(0, crop);
                surface.attach(overlay, motion.scale(0), motion.x(0), motion.y(0), crop);
                phase = Phase.EXPANDING;
                animator.start();
            } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }

        void frame(float progress) {
            if (phase != Phase.EXPANDING && phase != Phase.COLLAPSING) return;
            motion.crop(progress, crop);
            try { surface.transform(motion.scale(progress), motion.x(progress), motion.y(progress), crop); }
            catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }

        void collapse() {
            if (phase == Phase.COLLAPSING || phase == Phase.CLOSED) return;
            if (phase == Phase.WAITING) { finish(); return; }
            surface.input(new Rect());
            phase = Phase.COLLAPSING;
            float progress = (float) animator.getAnimatedValue();
            animator.removeAllListeners();
            animator.cancel();
            animator.setFloatValues(progress, 0);
            animator.setDuration(Math.max(1, Math.round(280 * progress)));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { finish(); }
            });
            animator.start();
        }

        void fail(Exception error) {
            if (phase == Phase.CLOSED) return;
            Log.w("RoadHomeVideo", "Live fullscreen presentation unavailable; opening the existing task", error);
            finish();
            fallback.run();
        }

        void finish() {
            if (phase == Phase.CLOSED) return;
            boolean attached = phase != Phase.WAITING;
            phase = Phase.CLOSED;
            overlay.removeCallbacks(deadline);
            if (listening) {
                host.unregisterReceiver(home);
                listening = false;
            }
            animator.cancel();
            if (attached) {
                preview.crop(0, crop);
                try { surface.restorePreview(preview.scale(0), preview.x(0), preview.y(0), crop); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    Log.e("RoadHomeVideo", "Could not restore the live preview surface", error);
                }
            }
            if (overlay.isAttachedToWindow()) windows.removeViewImmediate(overlay);
            running = null;
            restored.run();
        }
    }
}
