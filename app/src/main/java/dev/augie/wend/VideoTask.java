package dev.augie.wend;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.os.Bundle;
import java.util.List;
import java.util.ArrayList;

/** Restores one existing video task in fullscreen mode on our virtual display. */
final class VideoTask {
    private VideoTask() { }

    static boolean embed(Activity host, int displayId) throws ReflectiveOperationException {
        if (displayId <= 0) return false;
        return move(host, displayId);
    }

    static boolean openFullscreen(Activity host) throws ReflectiveOperationException {
        return move(host, 0);
    }

    private static boolean move(Activity host, int displayId) throws ReflectiveOperationException {
        Object service = service();
        Object result = api().getMethod("getTasks", int.class).invoke(service, Integer.MAX_VALUE);
        if (!(result instanceof List<?>)) throw new IllegalStateException("Task service returned no task list");
        List<Integer> ids = new ArrayList<>();
        for (Object item : (List<?>) result) {
            if (!(item instanceof ActivityManager.RunningTaskInfo)) continue;
            ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) item;
            if (task.baseActivity == null || !EmbeddedVideo.PACKAGE.equals(task.baseActivity.getPackageName())) continue;
            ids.add(task.taskId);
        }
        android.content.SharedPreferences preferences = host.getSharedPreferences("video-task", Activity.MODE_PRIVATE);
        int selected = selectId(ids, preferences.getInt("taskId", -1));
        if (selected < 0) return false;
        // Moving a pinned stack directly leaves PiP as the only stack on the
        // virtual display, crashing Android 10's ActivityMetricsLogger. Restore
        // the exact task with fullscreen placement, as we do for Maps.
        Object moved = api().getMethod("startActivityFromRecents", int.class, Bundle.class)
                .invoke(service, selected, fullscreenOptions(displayId).toBundle());
        if (!(moved instanceof Integer) || (Integer) moved < 0) {
            throw new IllegalStateException("Moving video task " + selected + " failed: " + moved);
        }
        preferences.edit().putInt("taskId", selected).apply();
        if (displayId != 0) host.getSystemService(ActivityManager.class).moveTaskToFront(host.getTaskId(), 0);
        return true;
    }

    static int selectId(List<Integer> ids, int remembered) {
        if (ids.contains(remembered)) return remembered;
        if (ids.isEmpty()) return -1;
        if (ids.size() == 1) return ids.get(0);
        throw new IllegalStateException("Multiple video tasks exist; cannot identify the playing task");
    }

    static ActivityOptions fullscreenOptions(int displayId) throws ReflectiveOperationException {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, 1);
        return options;
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName("android.app.IActivityTaskManager");
    }

    private static Object service() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }
}
