package dev.augie.wend.piphook;

import android.os.Handler;
import de.robv.android.xposed.XposedBridge;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs in system_server. Keeps an app that Android 10 is resizing out of sight, under a
 * {@link ScreenStill} or hidden itself, until the app reports its layout settled ({@link PipSignals}),
 * then reveals it. Never longer than {@link #SETTLE_CAP_MS} after the cover has landed.
 */
final class SettleCover {
    /** What reveals the app: a still fading out, or the hidden app fading in. */
    interface Reveal { void reveal(Handler thread); }

    /**
     * Only a safety net: an app normally reports within a few hundred ms. Under heavy load Maps took
     * over 1.5 s, and revealing it then showed its half-drawn layout.
     */
    private static final long SETTLE_CAP_MS = 2500;
    private static final Object lock = new Object();
    /** The cover waiting on each app, by its task id; guarded by {@link #lock}. */
    private static final Map<Integer, SettleCover> current = new HashMap<>();

    private final int taskId;
    private final Reveal target;
    private final Handler thread;
    private final String what;
    private boolean landed;
    private boolean settled;
    private boolean revealed;

    private SettleCover(int taskId, Reveal target, Handler thread, String what) {
        this.taskId = taskId;
        this.target = target;
        this.thread = thread;
        this.what = what;
    }

    /** Starts covering task {@code taskId}; a cover still waiting on it from before is revealed. */
    static SettleCover hold(int taskId, Reveal target, Handler thread, String what) {
        SettleCover cover = new SettleCover(taskId, target, thread, what);
        SettleCover previous;
        synchronized (lock) { previous = current.put(taskId, cover); }
        if (previous != null) previous.reveal("a newer cover replaced it");
        return cover;
    }

    /** The still now covers the app where it rests; the app's report, or the cap, reveals it. */
    void landed() {
        boolean now;
        synchronized (lock) {
            landed = true;
            now = settled;
        }
        if (now) reveal("the app had already settled");
        else thread.postDelayed(() -> reveal("the app did not report its layout within " + SETTLE_CAP_MS + " ms"), SETTLE_CAP_MS);
    }

    /** The app in task {@code taskId} reported its layout settled; called on any thread. */
    static void settled(int taskId) {
        SettleCover cover;
        boolean now;
        synchronized (lock) {
            cover = current.get(taskId);
            if (cover == null) return;
            cover.settled = true;
            now = cover.landed;
        }
        if (now) cover.reveal("the app reported its layout settled");
    }

    private void reveal(String why) {
        synchronized (lock) {
            if (revealed) return;
            revealed = true;
            current.remove(taskId, this);
        }
        XposedBridge.log(PipHook.TAG + ": revealing " + what + ": " + why);
        target.reveal(thread);
    }
}
