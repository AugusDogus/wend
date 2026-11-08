package dev.augie.roadhome;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Keeps a recent still of the video playing live in its card, so the card can show it once the
 * live video leaves. Captures the card's screen area about once a second with Android 10's
 * screenshot overload, which leaves out secure layers; stills stay in memory, never stored or sent.
 *
 * <p>YouTube draws its pause overlay over the video about 160 ms after it stops moving, and keeps
 * it. A frame is therefore kept only after the card has played live for {@link #CONFIRM_MS} more
 * without interruption; a frame taken as playback paused is dropped.
 */
final class VideoStills {
    interface Card {
        /** The card's screen area while it shows live video that nothing covers, else null. */
        Rect liveArea();
        /** The main display's rotation, as the screenshot overload expects it. */
        int rotation();
        /** A newer still of the playing video; the card owns it from now on. */
        void kept(Bitmap still);
    }

    static final int INTERVAL_MS = 1000;
    static final int CONFIRM_MS = 500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker;
    private final Card card;
    private final Runnable tick = this::tick;
    /** Changes whenever the card stops showing live video, so captures in flight are dropped. */
    private int generation;
    private boolean running;
    private boolean capturing;
    private Bitmap pending;
    private long pendingTakenAt;
    /** Logged once; the card asks again each time it shows live video. */
    private boolean unavailableLogged;
    private boolean failureLogged;

    /** @param worker runs the screenshots, off the main thread */
    VideoStills(Executor worker, Card card) {
        this.worker = worker;
        this.card = card;
    }

    /**
     * The card shows live video again: capture until it stops, starting one interval from now, once
     * Road Home has drawn the card without its cover.
     */
    void start() {
        if (running) return;
        if (Build.VERSION.SDK_INT != 29 || Process.myUid() != 1000) {
            if (!unavailableLogged) {
                unavailableLogged = true;
                Log.w("RoadHomeVideo", "Stills of the video card need Android 10 and the platform signature;"
                        + " a paused card shows the session's artwork or its placeholder");
            }
            return;
        }
        running = true;
        main.postDelayed(tick, INTERVAL_MS);
    }

    /** The card stops showing live video, or something covers it: whatever was taken last is dropped. */
    void interrupt() {
        generation++;
        running = false;
        capturing = false;
        main.removeCallbacks(tick);
        drop();
    }

    void release() { interrupt(); }

    private void tick() {
        if (!running) return;
        Rect area = card.liveArea();
        if (area == null || area.isEmpty()) { interrupt(); return; }
        if (pending != null && SystemClock.uptimeMillis() - pendingTakenAt >= CONFIRM_MS) {
            Bitmap confirmed = pending;
            pending = null;
            card.kept(confirmed);
        }
        if (!capturing && pending == null) capture(area, card.rotation());
        main.postDelayed(tick, INTERVAL_MS);
    }

    private void capture(Rect area, int rotation) {
        int request = generation;
        capturing = true;
        try {
            worker.execute(() -> {
                long takenAt = SystemClock.uptimeMillis();
                Bitmap still;
                try { still = screenshot(area, rotation); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    main.post(() -> {
                        if (request != generation) return;
                        if (!failureLogged) {
                            failureLogged = true;
                            Log.w("RoadHomeVideo", "Could not capture a still of the video card at " + area
                                    + "; the card keeps its previous still, artwork, or placeholder. Retrying"
                                    + " each time it plays live again, logged once until a capture succeeds", error);
                        }
                        interrupt();
                    });
                    return;
                }
                main.post(() -> {
                    if (request != generation) { still.recycle(); return; }
                    failureLogged = false;
                    capturing = false;
                    drop();
                    pending = still;
                    pendingTakenAt = takenAt;
                });
            });
        } catch (RejectedExecutionException error) {
            // The card is being released.
            capturing = false;
            running = false;
        }
    }

    private static Bitmap screenshot(Rect area, int rotation) throws ReflectiveOperationException {
        // This Android 10 overload excludes secure layers.
        Object result = SurfaceControl.class.getDeclaredMethod("screenshot", Rect.class, int.class, int.class, int.class)
                .invoke(null, area, area.width(), area.height(), rotation);
        if (!(result instanceof Bitmap)) throw new IllegalStateException("The screenshot returned no bitmap");
        Bitmap shot = (Bitmap) result;
        if (shot.getConfig() != Bitmap.Config.HARDWARE) return shot;
        // Copied once into ordinary memory, so the graphics buffer is freed now, not when the
        // collector next runs: one capture a second would otherwise pile buffers up.
        try {
            Bitmap copy = shot.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("Could not copy the video card's still into memory");
            return copy;
        } finally { shot.recycle(); }
    }

    private void drop() {
        if (pending != null) pending.recycle();
        pending = null;
    }
}
