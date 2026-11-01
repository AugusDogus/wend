package dev.augie.roadhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.PathInterpolator;
import java.lang.reflect.Method;

/** Shrinks Maps' fullscreen surface before committing its panel layout. Android 10 only. */
final class MapsHomeAnimation extends IRemoteAnimationRunner.Stub {
    private enum Phase { ANIMATING, HANDOFF, COMPLETE }
    private final Activity host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MapTransition bridge;
    private Method position;
    private Method crop;
    private Method show;
    private Pending pending;
    private Running running;

    MapsHomeAnimation(Activity host, View panel) {
        this.host = host;
        bridge = new MapTransition(host, panel);
    }

    void initialize() throws ReflectiveOperationException {
        position = SurfaceControl.Transaction.class.getMethod("setPosition", SurfaceControl.class,
                float.class, float.class);
        crop = SurfaceControl.Transaction.class.getMethod("setWindowCrop", SurfaceControl.class,
                int.class, int.class);
        show = SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class);
        RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        // Activity-local registration cannot intercept transitions that do not involve Home.
        // Task open/front/back plus their wallpaper variants in Android 10.
        for (int transition : new int[]{8, 10, 11, 12, 13, 14, 15}) {
            definition.addRemoteAnimation(transition, 2,
                    new RemoteAnimationAdapter(this, 240, 0));
        }
        Activity.class.getMethod("registerRemoteAnimations", RemoteAnimationDefinition.class)
                .invoke(host, definition);
    }

    boolean pending() { return pending != null || running != null || bridge.pending(); }

    void move(int task, Rect bounds, Runnable place) {
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) { place.run(); return; }
        pending = new Pending(task, bounds, place);
        handler.postDelayed(pending, 700);
    }

    @Override public void onAnimationStart(RemoteAnimationTarget[] apps,
            IRemoteAnimationFinishedCallback callback) {
        handler.post(() -> {
            Pending request = pending;
            RemoteAnimationTarget home = null;
            RemoteAnimationTarget maps = null;
            for (RemoteAnimationTarget target : apps) {
                if (target.taskId == host.getTaskId() && target.mode == 0) home = target;
                if (request != null && target.taskId == request.task && target.mode == 1) maps = target;
            }
            if (home == null || maps == null || request == null) { finishRemote(callback); return; }
            handler.removeCallbacks(request);
            pending = null;
            running = new Running(maps, home, callback, request);
            running.start();
        });
    }

    @Override public void onAnimationCancelled() {
        handler.post(() -> { if (running != null) running.finish(false); });
    }

    void cancel() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
        if (running != null) running.finish(false);
        bridge.cancel();
    }

    void release() {
        cancel();
        try { Activity.class.getMethod("unregisterRemoteAnimations").invoke(host); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("RoadHomeReturn", "Could not unregister Home animation", error);
        }
    }

    private static void finishRemote(IRemoteAnimationFinishedCallback callback) {
        try { callback.onAnimationFinished(); }
        catch (RemoteException error) { Log.w("RoadHomeReturn", "Could not finish Home animation", error); }
    }

    private final class Pending implements Runnable {
        final int task;
        final Rect bounds;
        final Runnable place;
        Pending(int task, Rect bounds, Runnable place) {
            this.task = task; this.bounds = new Rect(bounds); this.place = place;
        }
        @Override public void run() {
            if (pending != this) return;
            pending = null;
            Log.w("RoadHomeReturn", "No Maps Home animation target; using the panel handoff");
            bridge.settle(place);
        }
    }

    private final class Running {
        final RemoteAnimationTarget target;
        final RemoteAnimationTarget home;
        final IRemoteAnimationFinishedCallback callback;
        final Pending request;
        final Rect start;
        final Rect current = new Rect();
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        Phase phase = Phase.ANIMATING;

        Running(RemoteAnimationTarget target, RemoteAnimationTarget home,
                IRemoteAnimationFinishedCallback callback, Pending request) {
            this.target = target; this.home = home; this.callback = callback; this.request = request;
            start = new Rect(target.sourceContainerBounds);
            start.offsetTo(target.position.x, target.position.y);
        }

        void start() {
            Log.i("RoadHomeReturn", "Returning live task " + target.taskId + " " + start + " -> " + request.bounds);
            animator.setDuration(240);
            animator.setInterpolator(new PathInterpolator(0.22f, 1, 0.36f, 1));
            animator.addUpdateListener(value -> {
                MapWindowMotion.interpolate(start, request.bounds, (float) value.getAnimatedValue(), current);
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.setAlpha(home.leash, 1);
                    show.invoke(transaction, home.leash);
                    position.invoke(transaction, target.leash, (float) current.left, (float) current.top);
                    crop.invoke(transaction, target.leash, current.width(), current.height());
                    transaction.setAlpha(target.leash, 1).setLayer(target.leash, 10000).apply();
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w("RoadHomeReturn", "Could not animate Maps back to its panel", error);
                    finish(true);
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { finish(true); }
            });
            animator.start();
        }

        void finish(boolean place) {
            if (phase != Phase.ANIMATING) {
                if (!place) complete();
                return;
            }
            phase = Phase.HANDOFF;
            animator.removeAllListeners(); animator.removeAllUpdateListeners(); animator.cancel();
            // Restore before acknowledging Home, so Maps never hides and recreates its
            // SurfaceView between the closing fullscreen task and the opening panel.
            if (place) bridge.settle(() -> {
                try { request.place.run(); }
                finally { complete(); }
            });
            else complete();
        }

        void complete() {
            if (phase == Phase.COMPLETE) return;
            phase = Phase.COMPLETE;
            finishRemote(callback);
            if (running == this) running = null;
        }
    }
}
