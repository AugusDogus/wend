package dev.augie.roadhome;

import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

/** Reports each app task Android creates or brings to the front, on the main thread, while started. */
final class TaskFrontWatcher {
    interface Listener { void movedToFront(String packageName, int displayId); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final String tag;
    private final Listener listener;
    private Registration registration;

    /** @param tag the log tag of the window that watches */
    TaskFrontWatcher(String tag, Listener listener) { this.tag = tag; this.listener = listener; }

    void start() throws ReflectiveOperationException {
        if (registration != null) return;
        Registration next = new Registration();
        api().getMethod("registerTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                .invoke(service(), next);
        registration = next;
    }

    void stop() {
        Registration current = registration;
        registration = null;
        if (current == null) return;
        try {
            api().getMethod("unregisterTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                    .invoke(service(), current);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Its callbacks are ignored from now on, so the stale registration has no effect.
            Log.w(tag, "Could not unregister the app-switch listener; ignoring its later callbacks", error);
        }
    }

    // Binder-thread callbacks. Hidden Android 10 API; the platform UID may implement it.
    private final class Registration extends TaskStackListener {
        // Android reports an existing task brought forward here.
        @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo task) {
            ComponentName component = task.baseActivity != null ? task.baseActivity : task.topActivity;
            report(component, displayId(task, tag));
        }

        // A newly launched app gets a new task instead, without a display or a move-to-front.
        @Override public void onTaskCreated(int taskId, ComponentName component) {
            report(component, Display.INVALID_DISPLAY);
        }

        private void report(ComponentName component, int displayId) {
            String packageName = component == null ? "" : component.getPackageName();
            main.post(() -> { if (registration == this) listener.movedToFront(packageName, displayId); });
        }
    }

    private static int displayId(ActivityManager.RunningTaskInfo task, String tag) {
        try { return task.getClass().getField("displayId").getInt(task); }
        catch (ReflectiveOperationException error) {
            Log.w(tag, "Android did not report the display of task " + task.taskId
                    + "; treating it as a different display", error);
            return Display.INVALID_DISPLAY;
        }
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName("android.app.IActivityTaskManager");
    }

    private static Object service() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }
}
