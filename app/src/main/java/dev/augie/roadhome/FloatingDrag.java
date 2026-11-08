package dev.augie.roadhome;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;

/** Turns touches on a floating window into a 1:1 drag, a momentum corner snap, or a tap. */
final class FloatingDrag {
    interface Listener {
        void moved(Rect bounds);
        void released(Rect bounds, FloatingPlacement.Corner corner);
        void tapped();
    }

    private final FloatingPlacement placement;
    private final int touchSlop;
    private final Listener listener;
    private final Rect start = new Rect();
    private Rect current = new Rect();
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private boolean dragging;

    FloatingDrag(FloatingPlacement placement, int touchSlop, Listener listener) {
        this.placement = placement;
        this.touchSlop = touchSlop;
        this.listener = listener;
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
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocity == null) return false;
                velocity.addMovement(event);
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) dragging = true;
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
                    listener.released(current, placement.release(current,
                            velocity.getXVelocity(), velocity.getYVelocity()));
                } else listener.tapped();
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (velocity == null) return false;
                // An interrupted gesture has no intent to throw; settle where it was left.
                if (dragging) listener.released(current, placement.release(current, 0, 0));
                recycle();
                return true;
            default:
                return velocity != null;
        }
    }

    private void recycle() {
        if (velocity != null) velocity.recycle();
        velocity = null;
        dragging = false;
    }
}
