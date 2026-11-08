package dev.augie.wend;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityView;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/** Live map transitions without a freeform task or its forced system bars. */
final class EmbeddedMapMotion {
    private enum Phase { IDLE, PREPARING, EXPANDING }
    private final View panel;
    private final ActivityView view;
    private final MapTransition cover;
    private final SurfaceExpansion expanding;
    private final MapsHomeAnimation returning;
    private final Point screen = new Point();
    private MapFrame frame;
    private Phase phase = Phase.IDLE;
    private Runnable deadline;
    private MapPanel.Covering covering;
    private int generation;

    EmbeddedMapMotion(Activity host, View panel, ActivityView view) throws ReflectiveOperationException {
        this.panel = panel;
        this.view = view;
        host.getWindowManager().getDefaultDisplay().getRealSize(screen);
        cover = new MapTransition(host, panel);
        expanding = new SurfaceExpansion(host);
        returning = new MapsHomeAnimation(host, panel);
        returning.initialize();
    }

    boolean pending() { return expanding() || returning.pending(); }
    boolean expanding() { return phase != Phase.IDLE || cover.active(); }

    void expand(Runnable move, MapPanel.Covering covering) {
        cancel();
        this.covering = covering;
        if (!ValueAnimator.areAnimatorsEnabled() || !panel.isLaidOut()) { move.run(); covering(); return; }
        phase = Phase.PREPARING;
        int request = generation;
        cover.hold(() -> {
            if (phase != Phase.PREPARING) return;
            // Resize once under a stationary cover. Motion only changes crop and position.
            view.setLayoutParams(new FrameLayout.LayoutParams(screen.x, screen.y));
            frame = new MapFrame(screen.x, () -> panel.post(() -> {
                if (request == generation) animate(move);
            }));
            deadline = () -> {
                if (phase != Phase.PREPARING) return;
                Log.w("WendMap", "Map buffers did not resize before the expansion deadline; opening directly; view=" + view.getWidth() + "x" + view.getHeight());
                finish(move);
            };
            panel.postDelayed(deadline, 900);
        });
    }

    private void animate(Runnable move) {
        if (phase != Phase.PREPARING) return;
        stopWaiting();
        phase = Phase.EXPANDING;
        try {
            ActivityViewSurface surface = new ActivityViewSurface(view, ActivityViewSurface.PreviewInput.FORWARDED);
            // Source matches the card dimensions, giving scale=1 throughout the animation.
            expanding.open(panel, surface, new RectF(0, 0, panel.getWidth(), panel.getHeight()),
                    screen.x, screen.y, () -> {
                        cover.cancel();
                        covering();
                    }, () -> finish(move));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("WendMap", "Embedded map surface animation unavailable; opening directly", error);
            finish(move);
        }
    }

    private void finish(Runnable move) {
        stopWaiting();
        phase = Phase.IDLE;
        move.run();
        // A slow expansion signals only after the blocking task move, so motion that starts
        // now is not starved of its first frames. A live expansion signaled when it began.
        covering();
        cover.settleHeld();
        // SurfaceExpansion restores the root parent before this layout runs.
        panel.post(this::restoreSize);
    }

    void returnHome(int task, Runnable move) {
        cancel();
        int[] location = new int[2];
        panel.getLocationOnScreen(location);
        returning.move(task, new Rect(location[0], location[1], location[0] + panel.getWidth(),
                location[1] + panel.getHeight()), move);
    }

    private void covering() {
        MapPanel.Covering once = covering;
        covering = null;
        // A slow expansion still holds Wend's frozen frame; blank what just left it.
        if (once != null) cover.conceal(once.starting());
    }

    void cancel() {
        generation++;
        covering = null;
        phase = Phase.IDLE;
        stopWaiting();
        expanding.cancel();
        returning.cancel();
        cover.cancel();
        restoreSize();
    }

    private void restoreSize() {
        if (phase != Phase.IDLE) return;
        if (view.getLayoutParams().width != -1 || view.getLayoutParams().height != -1) {
            view.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private void stopWaiting() {
        if (deadline != null) panel.removeCallbacks(deadline);
        deadline = null;
        if (frame != null) frame.close();
        frame = null;
    }

    void release() { cancel(); returning.release(); }
}
