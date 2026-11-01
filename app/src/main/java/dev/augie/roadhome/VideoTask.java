package dev.augie.roadhome;

import android.content.ComponentName;
import java.util.List;

/** Moves only an existing YouTube task into our full-size virtual display. */
final class VideoTask {
    private VideoTask() { }

    static boolean embed(int displayId) throws ReflectiveOperationException {
        if (displayId <= 0) return false;
        Object service = service();
        if (findStack(service, displayId) != null) return true;
        Object stack = findStack(service, 0);
        if (stack == null) return false;
        int stackId = stack.getClass().getField("stackId").getInt(stack);
        api().getMethod("moveStackToDisplay", int.class, int.class).invoke(service, stackId, displayId);
        return true;
    }

    private static Class<?> api() throws ClassNotFoundException {
        return Class.forName("android.app.IActivityTaskManager");
    }

    private static Object service() throws ReflectiveOperationException {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    private static Object findStack(Object service, int displayId) throws ReflectiveOperationException {
        if (displayId < 0) return null;
        Object result = api().getMethod("getAllStackInfos").invoke(service);
        if (!(result instanceof List<?>)) throw new IllegalStateException("Task service returned no stack list");
        for (Object stack : (List<?>) result) {
            if (stack == null || stack.getClass().getField("displayId").getInt(stack) != displayId) continue;
            Object top = stack.getClass().getField("topActivity").get(stack);
            if (top instanceof ComponentName
                    && EmbeddedVideo.PACKAGE.equals(((ComponentName) top).getPackageName())) return stack;
            // SystemUI briefly puts its PiP menu above YouTube during entry. Identify the
            // underlying task by its original component instead of losing it during that menu.
            if (top == null || (top instanceof ComponentName
                    && "com.android.systemui.pip.phone.PipMenuActivity".equals(((ComponentName) top).getClassName()))) {
                Object names = stack.getClass().getField("taskNames").get(stack);
                if (names instanceof String[]) {
                    for (String name : (String[]) names) {
                        if (name != null && name.startsWith(EmbeddedVideo.PACKAGE + "/")) return stack;
                    }
                }
            }
        }
        return null;
    }
}
