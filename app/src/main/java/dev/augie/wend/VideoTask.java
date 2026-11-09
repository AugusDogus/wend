package dev.augie.wend;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import java.util.List;
import java.util.ArrayList;

/** Restores one existing video task in fullscreen mode on our virtual display. */
final class VideoTask {
    /**
     * Must match {@code dev.augie.wend.piphook.CardGrow.GROW_FROM}: the card's screen bounds, as
     * {left, top, right, bottom}, that the pip-hook module grows a moved task from.
     */
    private static final String GROW_FROM = "dev.augie.wend.GROW_FROM";

    private VideoTask() { }

    static boolean embed(Activity host, int displayId) throws ReflectiveOperationException {
        if (displayId <= 0) return false;
        return move(host, displayId, true, ActivityOptions.makeBasic());
    }

    /**
     * @param allowAutoPip true lets the app this move backgrounds enter its own native PiP on the
     *     task switch (the video covering a floated Maps). Which app actually pins is still gated
     *     by its PICTURE_IN_PICTURE app-op, which Wend allows only for the deliberately floated app.
     */
    static boolean openFullscreen(Activity host, boolean allowAutoPip) throws ReflectiveOperationException {
        return move(host, 0, !allowAutoPip, ActivityOptions.makeBasic());
    }

    /**
     * Moves the video task fullscreen onto the main display, growing out of {@code card} instead of
     * cutting to fullscreen where the pip-hook module is installed ({@link #GROW_FROM}); elsewhere
     * Android ignores the request and the video cuts to fullscreen as before. The app it covers may not
     * enter PiP on this move.
     */
    static boolean growFullscreen(Activity host, View card) throws ReflectiveOperationException {
        return move(host, 0, true, ActivityOptions.makeBasic(), card);
    }

    /**
     * Brings the existing video task forward over the fullscreen app on the main display with a
     * plain task switch (a launcher start, not {@code startActivityFromRecents}), so the video must
     * already be a task on the main display under that app. Unlike {@code startActivityFromRecents},
     * this does not move the Home stack to the front first, so Android keeps the covered app as the
     * top activity and marks it {@code supportsEnterPipOnTaskSwitch}: with that app's PiP app-op
     * allowed, it enters its own native PiP via {@code onUserLeaveHint} as the video covers it.
     *
     * @return false when no video task is found to bring forward
     */
    static boolean coverViaTaskSwitch(Activity host) throws ReflectiveOperationException {
        int selected = find(host);
        if (selected < 0) return false;
        // moveTaskToFront, unlike startActivityFromRecents, does not move the Home stack to the
        // front first, so the covered app stays the top activity and is marked
        // supportsEnterPipOnTaskSwitch; and unlike a background startActivity it goes through
        // findTaskToMoveToFront, which sets mUserLeaving so Android delivers onUserLeaveHint to the
        // covered app. With that app's PiP app-op allowed, it enters its own native PiP.
        Class<?> appThread = Class.forName("android.app.IApplicationThread");
        api().getMethod("moveTaskToFront", appThread, String.class, int.class, int.class, Bundle.class)
                .invoke(service(), null, host.getPackageName(), selected, 0, null);
        remember(host, selected);
        Log.i("WendPip", "Covered the fullscreen app with video task " + selected
                + " via moveTaskToFront so it enters its own native PiP");
        return true;
    }

    /** Closes the video's picture-in-picture, if it has one, as SystemUI's close button does. */
    static void dismissPip(Activity host) throws ReflectiveOperationException {
        int selected = find(host);
        if (selected >= 0 && SystemPip.pinned(selected)) SystemPip.dismiss();
    }

    /** @return the video's task, or -1 when it has none */
    static int find(Activity host) throws ReflectiveOperationException {
        Object result = api().getMethod("getTasks", int.class).invoke(service(), Integer.MAX_VALUE);
        if (!(result instanceof List<?>)) throw new IllegalStateException("Task service returned no task list");
        List<Integer> ids = new ArrayList<>();
        for (Object item : (List<?>) result) {
            if (!(item instanceof ActivityManager.RunningTaskInfo)) continue;
            ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) item;
            if (task.baseActivity == null || !EmbeddedVideo.PACKAGE.equals(task.baseActivity.getPackageName())) continue;
            ids.add(task.taskId);
        }
        return selectId(ids, preferences(host).getInt("taskId", -1));
    }

    private static boolean move(Activity host, int displayId, boolean suppressAutoPip, ActivityOptions base)
            throws ReflectiveOperationException {
        return move(host, displayId, suppressAutoPip, base, null);
    }

    private static boolean move(Activity host, int displayId, boolean suppressAutoPip, ActivityOptions base, View growFrom)
            throws ReflectiveOperationException {
        int selected = find(host);
        if (selected < 0) return false;
        Bundle options = fullscreenOptions(base, displayId, suppressAutoPip).toBundle();
        if (growFrom != null) {
            int[] at = new int[2];
            growFrom.getLocationOnScreen(at);
            options.putIntArray(GROW_FROM, new int[] { at[0], at[1], at[0] + growFrom.getWidth(), at[1] + growFrom.getHeight() });
        }
        // Moving a pinned stack directly leaves PiP as the only stack on the
        // virtual display, crashing Android 10's ActivityMetricsLogger. Restore
        // the exact task with fullscreen placement, as we do for Maps.
        Object moved = api().getMethod("startActivityFromRecents", int.class, Bundle.class)
                .invoke(service(), selected, options);
        if (!(moved instanceof Integer) || (Integer) moved < 0) {
            throw new IllegalStateException("Moving video task " + selected + " failed: " + moved);
        }
        remember(host, selected);
        if (displayId != 0) host.getSystemService(ActivityManager.class).moveTaskToFront(host.getTaskId(), 0);
        else Log.i("WendVideo", "Moved video task " + selected + " to the main display, fullscreen");
        return true;
    }

    private static void remember(Activity host, int taskId) { preferences(host).edit().putInt("taskId", taskId).apply(); }

    private static android.content.SharedPreferences preferences(Activity host) {
        return host.getSharedPreferences("video-task", Activity.MODE_PRIVATE);
    }

    static int selectId(List<Integer> ids, int remembered) {
        if (ids.contains(remembered)) return remembered;
        if (ids.isEmpty()) return -1;
        if (ids.size() == 1) return ids.get(0);
        throw new IllegalStateException("Multiple video tasks exist; cannot identify the playing task");
    }

    static ActivityOptions fullscreenOptions(int displayId) throws ReflectiveOperationException {
        return fullscreenOptions(displayId, true);
    }

    static ActivityOptions fullscreenOptions(int displayId, boolean suppressAutoPip)
            throws ReflectiveOperationException {
        return fullscreenOptions(ActivityOptions.makeBasic(), displayId, suppressAutoPip);
    }

    private static ActivityOptions fullscreenOptions(ActivityOptions basic, int displayId, boolean suppressAutoPip)
            throws ReflectiveOperationException {
        // When suppressed, the app this move backgrounds must not enter PiP itself as the video
        // comes forward. When allowed, a floated Maps enters its own native PiP on the task switch.
        ActivityOptions options = suppressAutoPip ? SystemPip.withoutAutoPip(basic) : basic;
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
