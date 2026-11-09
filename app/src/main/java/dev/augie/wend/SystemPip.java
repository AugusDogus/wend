package dev.augie.wend;

import android.app.ActivityOptions;
import android.app.TaskStackListener;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 10's own picture-in-picture for the Maps and video tasks Wend otherwise embeds:
 * SystemUI's window, which drags, flings away, shows the app's actions, fullscreen and close on a
 * tap, and goes fullscreen on a double tap. SystemUI handles PiP on the main display only.
 */
final class SystemPip {
    /** WindowConfiguration's windowing modes. */
    static final int FULLSCREEN = 1;
    static final int PINNED = 2;
    private static final String TAG = "WendPip";

    private SystemPip() { }

    /** One activity stack as Android reports it: within a display, the topmost first. */
    static final class Stack {
        final int id;
        final int displayId;
        final int windowingMode;
        final boolean visible;
        /** Bottom to top. */
        final int[] taskIds;

        Stack(int id, int displayId, int windowingMode, boolean visible, int[] taskIds) {
            this.id = id;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.taskIds = taskIds;
        }

        boolean holds(int taskId) {
            for (int id : taskIds) if (id == taskId) return true;
            return false;
        }
    }

    /**
     * Pins the task's top activity, as Android does when an app enters PiP, from its current
     * fullscreen bounds. Unlike an app's own enterPictureInPictureMode, Android does not check the
     * PICTURE_IN_PICTURE app-op here, so Maps' stays off and Maps never pins itself on Home.
     * @return the pinned task: another one only if the task held more than one activity
     */
    static int pin(int taskId) throws ReflectiveOperationException {
        List<Stack> stacks = stacks();
        Stack stack = holding(stacks, taskId);
        if (stack == null) throw new IllegalStateException("Cannot pin task " + taskId + ": no stack holds it");
        if (stack.windowingMode == PINNED) return taskId;
        if (stack.displayId != Display.DEFAULT_DISPLAY) {
            throw new IllegalStateException("Cannot pin task " + taskId + " on display " + stack.displayId
                    + ": SystemUI shows PiP on the main display only, so move it there first");
        }
        Object pinned = api().getMethod("moveTopActivityToPinnedStack", int.class, Rect.class)
                .invoke(service(), stack.id, null);
        if (!Boolean.TRUE.equals(pinned)) {
            throw new IllegalStateException("Android did not pin task " + taskId + " (stack " + stack.id
                    + "): it has no running activity or does not support picture-in-picture");
        }
        int result = pinnedTask(stacks());
        if (result < 0) throw new IllegalStateException("Task " + taskId + " was pinned but no pinned stack remains");
        Log.i(TAG, "Pinned task " + taskId + (result == taskId ? "" : " as task " + result)
                + " from stack " + stack.id + " in system picture-in-picture");
        return result;
    }

    /**
     * Moves the pinned task to the bottom of the main display, hidden, as SystemUI's close does;
     * it stops, and a video app may pause.
     */
    static void unpinBehind() throws ReflectiveOperationException {
        for (Stack stack : stacks()) {
            if (stack.windowingMode != PINNED) continue;
            api().getMethod("moveTasksToFullscreenStack", int.class, boolean.class).invoke(service(), stack.id, false);
            Log.i(TAG, "Moved pinned stack " + stack.id + " behind the main display's apps");
        }
    }

    /** Whether the task shows in Android's PiP now. */
    static boolean pinned(int taskId) throws ReflectiveOperationException {
        return taskId >= 0 && pinnedTask(stacks()) == taskId;
    }

    /** Whether the task is the fullscreen app the main display shows, as after its PiP expands. */
    static boolean showsFullscreen(int taskId) throws ReflectiveOperationException {
        return showsFullscreen(stacks(), taskId);
    }

    static boolean showsFullscreen(List<Stack> stacks, int taskId) {
        for (Stack stack : stacks) {
            if (stack.displayId != Display.DEFAULT_DISPLAY || stack.windowingMode == PINNED || !stack.visible) continue;
            // The topmost visible stack below any PiP.
            return stack.windowingMode == FULLSCREEN && stack.holds(taskId);
        }
        return false;
    }

    /** @return the task in PiP, or -1 */
    static int pinnedTask(List<Stack> stacks) {
        for (Stack stack : stacks) {
            if (stack.windowingMode == PINNED && stack.taskIds.length > 0) return stack.taskIds[stack.taskIds.length - 1];
        }
        return -1;
    }

    private static Stack holding(List<Stack> stacks, int taskId) {
        for (Stack stack : stacks) if (stack.holds(taskId)) return stack;
        return null;
    }

    /**
     * Keeps the app on top of the main display from entering PiP itself as Wend brings another task
     * forward; Wend decides what floats.
     */
    static ActivityOptions withoutAutoPip(ActivityOptions options) {
        try {
            ActivityOptions.class.getMethod("setDisallowEnterPictureInPictureWhileLaunching", boolean.class)
                    .invoke(options, true);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Android 10's option against automatic PiP is missing; the app on top may"
                    + " enter PiP as Wend moves another task forward", error);
        }
        return options;
    }

    static List<Stack> stacks() throws ReflectiveOperationException {
        Object result = api().getMethod("getAllStackInfos").invoke(service());
        if (!(result instanceof List<?>)) throw new IllegalStateException("Android returned no stack list: " + result);
        List<Stack> stacks = new ArrayList<>();
        for (Object info : (List<?>) result) {
            Class<?> type = info.getClass();
            Object configuration = type.getField("configuration").get(info);
            Object window = configuration.getClass().getField("windowConfiguration").get(configuration);
            Object mode = window.getClass().getMethod("getWindowingMode").invoke(window);
            Object tasks = type.getField("taskIds").get(info);
            if (!(mode instanceof Integer) || !(tasks instanceof int[])) {
                throw new IllegalStateException("Unreadable stack " + type.getField("stackId").getInt(info));
            }
            stacks.add(new Stack(type.getField("stackId").getInt(info), type.getField("displayId").getInt(info),
                    (Integer) mode, type.getField("visible").getBoolean(info), (int[]) tasks));
        }
        return stacks;
    }

    /** Reports, on the main thread, each task Android takes out of PiP. */
    static final class Watcher {
        interface Listener {
            /** For any reason: expanded, closed, or moved by Wend. */
            void unpinned(String packageName, int taskId);
        }

        private final Handler main = new Handler(Looper.getMainLooper());
        private final Listener listener;
        private final Registration registration = new Registration();
        private boolean started;
        private String pinnedPackage;
        private int pinnedTask = -1;

        Watcher(Listener listener) { this.listener = listener; }

        void start() throws ReflectiveOperationException {
            if (started) return;
            api().getMethod("registerTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                    .invoke(service(), registration);
            started = true;
        }

        void stop() {
            if (!started) return;
            started = false;
            try {
                api().getMethod("unregisterTaskStackListener", Class.forName("android.app.ITaskStackListener"))
                        .invoke(service(), registration);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Could not unregister the PiP listener; Wend's activity is gone, so it acts on nothing", error);
            }
        }

        TaskStackListener listener() { return registration; }

        // Binder-thread callbacks, in Android's order; handled in that order on the main thread.
        private final class Registration extends TaskStackListener {
            @Override public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
                main.post(() -> {
                    pinnedPackage = packageName;
                    pinnedTask = taskId;
                });
            }

            @Override public void onActivityUnpinned() {
                main.post(() -> {
                    String packageName = pinnedPackage;
                    int taskId = pinnedTask;
                    pinnedPackage = null;
                    pinnedTask = -1;
                    if (packageName != null) listener.unpinned(packageName, taskId);
                });
            }
        }
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName("android.app.IActivityTaskManager");
    }

    private static Object service() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }
}
