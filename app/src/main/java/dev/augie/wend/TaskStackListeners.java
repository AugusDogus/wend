package dev.augie.wend;

import android.app.TaskStackListener;

/** Registers listeners with Android 10's hidden task stack API, which the platform UID may use. */
final class TaskStackListeners {
    private TaskStackListeners() { }

    static void register(TaskStackListener listener) throws ReflectiveOperationException {
        call("registerTaskStackListener", listener);
    }

    static void unregister(TaskStackListener listener) throws ReflectiveOperationException {
        call("unregisterTaskStackListener", listener);
    }

    private static void call(String method, TaskStackListener listener) throws ReflectiveOperationException {
        Object service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Class.forName("android.app.IActivityTaskManager")
                .getMethod(method, Class.forName("android.app.ITaskStackListener")).invoke(service, listener);
    }
}
