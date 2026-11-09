package dev.augie.wend.piphook;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.view.SurfaceControl;
import android.view.animation.PathInterpolator;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs in system_server. Grows a task Wend moves out of a card (or the icon tapped to open it) to
 * fullscreen on the main display, instead of the cut Android 10 makes (no app transition runs for a
 * task that was already visible in its card, nor here for one that was not).
 *
 * <p>Wend asks for it by putting the source's screen bounds under {@link #GROW_FROM} in the options
 * of {@code startActivityFromRecents}. The moved task's surface then scales from there to fullscreen
 * on the PiP shrink's curve and duration, over a still of the screen as it was before the move
 * (without the PiP), since Android hides Wend the moment the task covers it. Across the move itself a
 * still of the card stands in for the task, so the task is never seen fullscreen before its first
 * transform; nothing defers Android's layout, which would also hold a PiP shrink running alongside.
 */
final class CardGrow {
    /** Must match {@code dev.augie.wend.VideoTask.GROW_FROM}: the source's screen bounds as {left, top, right, bottom}. */
    static final String GROW_FROM = "dev.augie.wend.GROW_FROM";
    /** BoundsAnimationController.DEFAULT_TRANSITION_DURATION, so the grow and Maps' shrink move together. */
    private static final long DURATION_MS = 425;
    private static final String MOVE = "wendGrowMove";

    /** The card each grown task came from, by task id, until Home returns it there ({@link CardReturn}). */
    private static final Map<Integer, Rect> cards = new ConcurrentHashMap<>();

    private CardGrow() { }

    /** The card {@code taskId} grew from, once: null if it did not grow from one. */
    static Rect cardOf(int taskId) { return cards.remove(taskId); }

    /** What the move needs from before it to after it. */
    private static final class Move {
        final Rect card;
        final ScreenStill.Capture screen;
        final ScreenStill standIn;
        final SurfaceControl hidden;

        Move(Rect card, ScreenStill.Capture screen, ScreenStill standIn, SurfaceControl hidden) {
            this.card = card;
            this.screen = screen;
            this.standIn = standIn;
            this.hidden = hidden;
        }

        /** Shows the task again and drops the stand-in, as if nothing had been done. */
        void undo() {
            try {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                if (hidden != null) t.setAlpha(hidden, 1f);
                t.apply();
            } catch (Throwable error) {
                XposedBridge.log(PipHook.TAG + ": could not show the task again: " + error);
            }
            if (standIn != null) standIn.remove();
        }
    }

    static void install(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", loader),
                "startActivityFromRecents", int.class, Bundle.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Bundle options = (Bundle) param.args[1];
                        int[] card = options == null ? null : options.getIntArray(GROW_FROM);
                        if (card == null || card.length != 4) return;
                        options.remove(GROW_FROM);
                        if (Binder.getCallingUid() != Process.SYSTEM_UID) return;
                        param.setObjectExtra(MOVE, prepare(param.thisObject, (int) param.args[0],
                                new Rect(card[0], card[1], card[2], card[3])));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Move move = (Move) param.getObjectExtra(MOVE);
                        if (move == null) return;
                        if (!(param.getResult() instanceof Integer) || (Integer) param.getResult() < 0) {
                            move.undo();
                            return;
                        }
                        start(param.thisObject, (int) param.args[0], move);
                    }
                });
        XposedBridge.log(PipHook.TAG + ": card grow hook installed");
    }

    /**
     * Before the move: a still of the screen without its PiP, and, cut from it, a stand-in for the
     * card over the task, which is hidden until it is in place.
     */
    private static Move prepare(Object atms, int taskId, Rect card) {
        try {
            Object displayContent = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mWindowManager"), "getDefaultDisplayContentLocked");
            Rect full = new Rect();
            XposedHelpers.callMethod(displayContent, "getBounds", full);
            ScreenStill.Capture screen = ScreenStill.Capture.takeWithout(displayContent, full,
                    XposedHelpers.callMethod(displayContent, "getPinnedStack"));
            if (screen == null) return null;
            ScreenStill standIn = ScreenStill.show(displayContent, screen.part(card), card, "WendGrowStandIn", Integer.MAX_VALUE, 1f);
            Object record = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mRootActivityContainer"), "anyTaskForId", taskId);
            SurfaceControl task = record == null ? null
                    : (SurfaceControl) XposedHelpers.callMethod(XposedHelpers.callMethod(record, "getTask"), "getSurfaceControl");
            if (standIn != null && task != null) new SurfaceControl.Transaction().setAlpha(task, 0f).apply();
            return new Move(card, screen, standIn, standIn == null ? null : task);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not prepare the card grow; the task cuts to fullscreen: " + error);
            return null;
        }
    }

    private static void start(Object atms, int taskId, Move move) {
        try {
            Object record = XposedHelpers.callMethod(XposedHelpers.getObjectField(atms, "mRootActivityContainer"), "anyTaskForId", taskId);
            Object task = XposedHelpers.callMethod(record, "getTask");
            SurfaceControl stack = (SurfaceControl) XposedHelpers.callMethod(XposedHelpers.getObjectField(task, "mStack"), "getSurfaceControl");
            final SurfaceControl surface = (SurfaceControl) XposedHelpers.callMethod(task, "getSurfaceControl");
            Rect full = new Rect();
            XposedHelpers.callMethod(task, "getBounds", full);
            if (surface == null || full.isEmpty()) { move.undo(); return; }
            final ScreenStill behind = ScreenStill.showIn(stack, move.screen, move.screen.bounds, "WendGrowBackdrop", 1f);
            final Rect size = new Rect(0, 0, full.width(), full.height());
            final Rect card = move.card;
            final Rect from = new Rect(card.left - full.left, card.top - full.top, card.right - full.left, card.bottom - full.top);
            // One transaction: the task, in place at the card, replaces its stand-in.
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            if (behind != null) t.setLayer(behind.layer(), Integer.MIN_VALUE);
            place(t, surface, size, from);
            t.setAlpha(surface, 1f);
            t.apply();
            if (move.standIn != null) move.standIn.remove();
            MotionThread.get().post(() -> {
                ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION_MS);
                grow.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
                final Rect at = new Rect();
                grow.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    at.set(lerp(from.left, size.left, f), lerp(from.top, size.top, f), lerp(from.right, size.right, f),
                            lerp(from.bottom, size.bottom, f));
                    try {
                        SurfaceControl.Transaction frame = new SurfaceControl.Transaction();
                        place(frame, surface, size, at);
                        frame.apply();
                        if (f >= 1f) finish(surface, behind);
                    } catch (Throwable error) {
                        XposedBridge.log(PipHook.TAG + ": card grow frame failed; showing the task fullscreen: " + error);
                        a.cancel();
                        finish(surface, behind);
                    }
                });
                grow.start();
            });
            cards.put(taskId, new Rect(card));
            XposedBridge.log(PipHook.TAG + ": growing task " + taskId + " from " + card);
        } catch (Throwable error) {
            XposedBridge.log(PipHook.TAG + ": could not grow task " + taskId + " from its card; it shows fullscreen at once: " + error);
            move.undo();
        }
    }

    /**
     * Shows the task, {@code size} in its own coordinates, covering {@code at} without distortion:
     * scaled to cover it and cropped to it around its center, so it also grows cleanly out of an
     * icon of another shape than the app.
     */
    private static void place(SurfaceControl.Transaction t, SurfaceControl surface, Rect size, Rect at) {
        float scale = Math.max(at.width() / (float) size.width(), at.height() / (float) size.height());
        float cropWidth = at.width() / scale;
        float cropHeight = at.height() / scale;
        int cropLeft = Math.round((size.width() - cropWidth) / 2f);
        int cropTop = Math.round((size.height() - cropHeight) / 2f);
        XposedHelpers.callMethod(t, "setWindowCrop", surface,
                new Rect(cropLeft, cropTop, Math.round(cropLeft + cropWidth), Math.round(cropTop + cropHeight)));
        XposedHelpers.callMethod(t, "setMatrix", surface, scale, 0f, 0f, scale);
        XposedHelpers.callMethod(t, "setPosition", surface, at.left - cropLeft * scale, at.top - cropTop * scale);
    }

    private static int lerp(int from, int to, float f) { return Math.round(from + (to - from) * f); }

    private static void finish(SurfaceControl surface, ScreenStill behind) {
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        XposedHelpers.callMethod(t, "setWindowCrop", surface, -1, -1);
        XposedHelpers.callMethod(t, "setMatrix", surface, 1f, 0f, 0f, 1f);
        XposedHelpers.callMethod(t, "setPosition", surface, 0f, 0f);
        t.setAlpha(surface, 1f);
        t.apply();
        if (behind != null) behind.remove();
    }
}
