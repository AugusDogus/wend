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
import android.view.IRemoteAnimationRunner;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.PathInterpolator;
import java.lang.reflect.Method;

/** Animates the live Maps window without changing its pixel aspect ratio. API 29 only. */
final class LiveMapsAnimation extends IRemoteAnimationRunner.Stub {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MapTransition cover;
    private int expectedTask = -1;
    private Method crop;
    private Method position;
    private boolean registered;
    private Running running;
    private final Runnable missingCallback = this::cancel;

    LiveMapsAnimation(Activity host, View panel) { cover = new MapTransition(host, panel); }

    void expand(int task, Runnable resize) {
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) { resize.run(); return; }
        try { register(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("RoadHomeLive", "Maps animator unavailable; expanding without motion", error);
            resize.run();
            return;
        }
        expectedTask = task;
        cover.hold(resize);
        handler.postDelayed(missingCallback, 1400);
    }

    void initialize() throws ReflectiveOperationException {
        crop = SurfaceControl.Transaction.class.getMethod("setWindowCrop", SurfaceControl.class,
                int.class, int.class);
        position = SurfaceControl.Transaction.class.getMethod("setPosition", SurfaceControl.class,
                float.class, float.class);
    }

    private void register() throws ReflectiveOperationException {
        RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        // Only window-mode changes. Normal app launches keep their system animations.
        definition.addRemoteAnimation(27, new RemoteAnimationAdapter(this, 280, 0, true));
        Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Class.forName("android.app.IActivityTaskManager")
                .getMethod("registerRemoteAnimationsForDisplay", int.class, RemoteAnimationDefinition.class)
                .invoke(service, 0, definition);
        registered = true;
    }

    @Override public void onAnimationStart(RemoteAnimationTarget[] apps,
            IRemoteAnimationFinishedCallback callback) {
        handler.post(() -> {
            for (RemoteAnimationTarget target : apps) {
                if (target.taskId != expectedTask || target.startBounds == null) continue;
                if (running != null) running.finish();
                handler.removeCallbacks(missingCallback);
                // The runner already owns this transition. Do not intercept later mode changes.
                unregister();
                running = new Running(target, callback);
                running.prepare();
                return;
            }
            finishRemote(callback);
        });
    }

    void cancel() {
        handler.removeCallbacks(missingCallback);
        expectedTask = -1;
        if (running != null) running.finish();
        cover.cancel();
        unregister();
    }

    void release() { cancel(); }

    private void unregister() {
        if (!registered) return;
        try {
            Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Class.forName("android.app.IActivityTaskManager")
                    .getMethod("registerRemoteAnimationsForDisplay", int.class, RemoteAnimationDefinition.class)
                    .invoke(service, 0, new RemoteAnimationDefinition());
            registered = false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("RoadHomeLive", "Could not unregister the Maps window animator", error);
        }
    }

    @Override public void onAnimationCancelled() { handler.post(this::cancel); }

    private static void finishRemote(IRemoteAnimationFinishedCallback callback) {
        try { callback.onAnimationFinished(); }
        catch (RemoteException error) { Log.w("RoadHomeLive", "Window manager could not finish Maps animation", error); }
    }

    private final class Running {
        private final RemoteAnimationTarget target;
        private final IRemoteAnimationFinishedCallback callback;
        private final Rect start;
        private final Rect end;
        private final Rect current = new Rect();
        private final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        private MapFrame frame;
        private boolean finished;
        private final Runnable deadline = () -> {
            Log.w("RoadHomeLive", "Maps buffers were not ready; completing window change without motion");
            finish();
        };

        Running(RemoteAnimationTarget target, IRemoteAnimationFinishedCallback callback) {
            this.target = target; this.callback = callback;
            start = new Rect(target.startBounds); end = new Rect(target.sourceContainerBounds);
            end.offsetTo(target.position.x, target.position.y);
        }

        void prepare() {
            Log.i("RoadHomeLive", "Preparing live task " + target.taskId + " " + start + " -> " + end);
            if (!apply(0)) { finish(); return; }
            frame = new MapFrame(end.width(), () -> handler.post(this::animate));
            handler.postDelayed(deadline, 900);
        }

        void animate() {
            if (finished || running != this) return;
            handler.removeCallbacks(deadline);
            if (frame != null) frame.close();
            animator.setDuration(280);
            animator.setInterpolator(new PathInterpolator(0.22f, 1, 0.36f, 1));
            animator.addUpdateListener(value -> {
                if (!apply((float) value.getAnimatedValue())) finish();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { finish(); }
            });
            cover.cancel();
            Log.i("RoadHomeLive", "Animating live Maps surface");
            animator.start();
        }

        boolean apply(float progress) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                MapWindowMotion.interpolate(start, end, progress, current);
                position.invoke(transaction, target.leash, (float) current.left, (float) current.top);
                crop.invoke(transaction, target.leash, current.width(), current.height());
                transaction.setAlpha(target.leash, 1).setLayer(target.leash, 10000);
                if (target.startLeash != null) transaction.setAlpha(target.startLeash, 0);
                transaction.apply();
                return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("RoadHomeLive", "Could not apply Maps surface geometry", error);
                return false;
            }
        }

        void finish() {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(deadline);
            if (frame != null) frame.close();
            animator.removeAllListeners(); animator.removeAllUpdateListeners(); animator.cancel();
            finishRemote(callback);
            cover.cancel();
            if (running == this) { running = null; expectedTask = -1; }
        }
    }
}
