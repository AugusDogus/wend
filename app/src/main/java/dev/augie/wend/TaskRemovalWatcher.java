package dev.augie.wend;

import android.app.TaskStackListener;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Reports each task Android destroys, on the main thread, while started. For an app whose process
 * died (killed, or out of memory) it is the only report: Android 10 then destroys the task without
 * the {@code onTaskRemovalStarted} that a closed or crashed app's task gets
 * ({@code ActivityStack.removeHistoryRecordsForAppLocked} in android-10.0.0_r47).
 */
final class TaskRemovalWatcher {
    interface Listener { void removed(int taskId); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final String tag;
    private final Listener listener;
    private Registration registration;

    /** @param tag the log tag of the panel that watches */
    TaskRemovalWatcher(String tag, Listener listener) { this.tag = tag; this.listener = listener; }

    void start() throws ReflectiveOperationException {
        if (registration != null) return;
        Registration next = new Registration();
        TaskStackListeners.register(next);
        registration = next;
    }

    void stop() {
        Registration current = registration;
        registration = null;
        if (current == null) return;
        try {
            TaskStackListeners.unregister(current);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Its callbacks are ignored from now on, so the stale registration has no effect.
            Log.w(tag, "Could not unregister the task-removal listener; ignoring its later callbacks", error);
        }
    }

    // Binder-thread callback. Hidden Android 10 API; the platform UID may implement it.
    private final class Registration extends TaskStackListener {
        @Override public void onTaskRemoved(int taskId) {
            main.post(() -> { if (registration == this) listener.removed(taskId); });
        }
    }
}
