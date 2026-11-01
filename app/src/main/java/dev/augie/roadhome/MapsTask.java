package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Reuses one Maps task, including when it moves between the panel and display zero. */
final class MapsTask {
    static final class Task {
        final int id;
        final int displayId;
        final int windowingMode;
        Task(int id, int displayId, int windowingMode) {
            this.id = id; this.displayId = displayId; this.windowingMode = windowingMode;
        }
    }
    private final SharedPreferences preferences;
    private final ActivityManager activityManager;
    private final Activity host;

    MapsTask(Activity context) {
        host = context;
        preferences = context.getSharedPreferences("maps-task", Context.MODE_PRIVATE);
        activityManager = context.getSystemService(ActivityManager.class);
    }

    void remember(int taskId) { preferences.edit().putInt("taskId", taskId).apply(); }

    Task find() throws ReflectiveOperationException {
        Object result = api().getMethod("getTasks", int.class).invoke(service(), Integer.MAX_VALUE);
        if (!(result instanceof List<?>)) throw new IllegalStateException("Task service returned no task list");
        List<ActivityManager.RunningTaskInfo> maps = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (Object item : (List<?>) result) {
            if (!(item instanceof ActivityManager.RunningTaskInfo)) continue;
            ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) item;
            if (task.baseActivity != null && EmbeddedMaps.PACKAGE.equals(task.baseActivity.getPackageName())) {
                maps.add(task);
                ids.add(task.taskId);
            }
        }
        int selected = selectId(ids, preferences.getInt("taskId", -1));
        for (ActivityManager.RunningTaskInfo task : maps) {
            if (task.taskId == selected) {
                remember(selected);
                Object configuration = task.getClass().getField("configuration").get(task);
                Object window = configuration.getClass().getField("windowConfiguration").get(configuration);
                Object mode = window.getClass().getMethod("getWindowingMode").invoke(window);
                if (!(mode instanceof Integer)) throw new IllegalStateException("Missing Maps window mode");
                return new Task(selected, task.getClass().getField("displayId").getInt(task), (Integer) mode);
            }
        }
        return null;
    }

    static int selectId(List<Integer> ids, int remembered) {
        if (ids.contains(remembered)) return remembered;
        if (ids.isEmpty()) return -1;
        if (ids.size() == 1) return ids.get(0);
        // Never guess which duplicate owns a route, or launch a third task.
        throw new IllegalStateException("Multiple Maps tasks exist and none is the remembered navigation task");
    }

    void move(Task task, int displayId) throws ReflectiveOperationException {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(host, 0, 0);
        options.setLaunchDisplayId(displayId);
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, 1);
        Object result = api().getMethod("startActivityFromRecents", int.class, Bundle.class)
                .invoke(service(), task.id, options.toBundle());
        if (!(result instanceof Integer) || (Integer) result < 0) {
            throw new IllegalStateException("Moving Maps task " + task.id + " failed: " + result);
        }
        // Android 10 foregrounds the Home stack as a side effect of this API. Our
        // activity can be in a standard stack after an update or direct app launch.
        if (displayId != 0) activityManager.moveTaskToFront(host.getTaskId(), 0);
        remember(task.id);
        Log.i("RoadHome", "Moved Maps task " + task.id + " from display "
                + task.displayId + " to " + displayId);
    }

    void bounded(Task task, android.graphics.Rect bounds) throws ReflectiveOperationException {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(0);
        options.setLaunchBounds(bounds);
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, 5);
        Object result = api().getMethod("startActivityFromRecents", int.class, Bundle.class)
                .invoke(service(), task.id, options.toBundle());
        if (!(result instanceof Integer) || (Integer) result < 0) {
            throw new IllegalStateException("Bounding Maps task " + task.id + " failed: " + result);
        }
        remember(task.id);
        Log.i("RoadHomeLive", "Bounded task " + task.id + " " + bounds + " result=" + result);
    }

    void fullscreen(Task task) throws ReflectiveOperationException {
        api().getMethod("setTaskWindowingMode", int.class, int.class, boolean.class)
                .invoke(service(), task.id, 1, true);
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName("android.app.IActivityTaskManager");
    }

    private static Object service() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }
}
