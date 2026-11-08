package dev.augie.wend;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

/**
 * Turns touches on a floating window into what Android 10's PiP does with them: a 1:1 drag that
 * snaps to a corner or, off the bottom edge, closes it; a tap; or a double tap.
 */
final class FloatingDrag {
    interface Listener {
        void moved(Rect bounds);
        void released(Rect bounds, FloatingPlacement.Corner corner);
        /** Dragged or flung off the bottom edge: the window closes from {@code bounds}. */
        void dismissed(Rect bounds);
        /**
         * Each tap first, at its screen point.
         * @return true if a control there took it, so it is neither a tap nor half a double tap
         */
        boolean pressed(float x, float y);
        /** A tap that no second tap followed. */
        void tapped();
        void doubleTapped();
    }

    /** How soon a second tap must follow the first to make a double tap. */
    static final int DOUBLE_TAP_MS = ViewConfiguration.getDoubleTapTimeout();
    private final FloatingPlacement placement;
    private final int touchSlop;
    private final float flingVelocity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable singleTap = this::singleTap;
    private final Rect start = new Rect();
    private Rect current = new Rect();
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private boolean dragging;
    /** A first tap waiting to see whether a second follows; its finger came up at this event time. */
    private boolean tapPending;
    private long tapUpTime;
    /** This touch went down soon enough after a tap to make a double tap. */
    private boolean secondTap;
    /** Uptime from which a touch that starts can be a tap. */
    private long tapsFrom = Long.MIN_VALUE;

    /** @param flingVelocity px/s from which a downward release is a fling that can close the window */
    FloatingDrag(FloatingPlacement placement, int touchSlop, float flingVelocity, Listener listener) {
        this.placement = placement;
        this.touchSlop = touchSlop;
        this.flingVelocity = flingVelocity;
        this.listener = listener;
    }

    /**
     * A tap whose touch starts before {@code uptimeMillis} is taken but does nothing, because it
     * was aimed at what the window has just covered. Drags still move the window.
     */
    void ignoreTapsUntil(long uptimeMillis) { tapsFrom = uptimeMillis; }

    /** Forgets a first tap still waiting for a second, as when the window leaves its corner. */
    void cancelTap() {
        tapPending = false;
        handler.removeCallbacks(singleTap);
    }

    /** @param bounds where the window is drawn when this touch starts; read only on ACTION_DOWN */
    boolean handle(MotionEvent event, Rect bounds) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                downX = event.getRawX();
                downY = event.getRawY();
                start.set(bounds);
                current = new Rect(bounds);
                dragging = false;
                secondTap = tapPending && event.getEventTime() - tapUpTime <= DOUBLE_TAP_MS;
                if (secondTap) handler.removeCallbacks(singleTap);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocity == null) return false;
                velocity.addMovement(event);
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragging = true;
                    // A drag is never part of a double tap, nor follows a tap that still counts.
                    cancelTap();
                    secondTap = false;
                }
                if (dragging) {
                    current = placement.dragged(start, dx, dy);
                    listener.moved(current);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (velocity == null) return false;
                velocity.addMovement(event);
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity(), vy = velocity.getYVelocity();
                    if (placement.offscreenDismisses(current)
                            || placement.flingDismisses(downY, event.getRawY(), vx, vy, flingVelocity)) {
                        listener.dismissed(current);
                    } else listener.released(current, placement.release(current, vx, vy));
                } else if (event.getDownTime() >= tapsFrom) tap(event);
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (velocity == null) return false;
                // An interrupted gesture has no intent to throw or close; settle where it was left.
                if (dragging) listener.released(current, placement.release(current, 0, 0));
                cancelTap();
                recycle();
                return true;
            default:
                return velocity != null;
        }
    }

    private void tap(MotionEvent up) {
        if (secondTap) {
            cancelTap();
            listener.doubleTapped();
            return;
        }
        if (listener.pressed(up.getRawX(), up.getRawY())) { cancelTap(); return; }
        tapPending = true;
        tapUpTime = up.getEventTime();
        handler.removeCallbacks(singleTap);
        handler.postDelayed(singleTap, DOUBLE_TAP_MS);
    }

    private void singleTap() {
        if (!tapPending) return;
        tapPending = false;
        listener.tapped();
    }

    private void recycle() {
        if (velocity != null) velocity.recycle();
        velocity = null;
        dragging = false;
        secondTap = false;
    }
}
