package dev.augie.wend.piphook;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

/**
 * Runs in system_server. The thread this module's own animations run on. They only change surfaces,
 * so they must not share Android's {@code android.anim} thread, which also runs the window manager's
 * animation loop and blocks on its global lock while Android starts or resumes an app: there, the
 * first frames of a return to Home stalled for about 100 ms.
 */
final class MotionThread {
    private static Handler handler;

    private MotionThread() { }

    static synchronized Handler get() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("wend-pip-motion", Process.THREAD_PRIORITY_URGENT_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }
}
