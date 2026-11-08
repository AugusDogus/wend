package dev.augie.wend;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * The sheet's column of header and body. A vertical drag that starts in the header, or a
 * downward drag in body content already scrolled to its top, moves the sheet; everything
 * else, including scrolling and taps, reaches the content. Only the first finger counts.
 */
final class SheetPanel extends LinearLayout {
    interface Callback {
        /** Returns false while the sheet cannot be dragged, for example while it closes. */
        boolean onDragStart();
        /** Finger travel in pixels since the drag started; downward is positive. */
        void onDrag(float travel);
        /** Release velocity in pixels per second; downward is positive. Zero when canceled. */
        void onDragEnd(float velocity);
    }

    private final Callback callback;
    private final int touchSlop;
    /** The scrolling content; touches above it belong to the header. */
    private final View body;
    private VelocityTracker velocity;
    private int pointer = -1;
    private boolean fromHeader;
    private boolean dragging;
    private float downX;
    private float downY;
    private float downRawY;
    private float anchorRawY;

    SheetPanel(Context context, View body, Callback callback) {
        super(context);
        this.body = body;
        this.callback = callback;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(VERTICAL);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return track(event); }

    // The panel has no click action. It claims touches no child wanted, such as on the
    // handle, so a header drag can start there; Back and the backdrop dismiss it.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        track(event);
        return true;
    }

    private boolean track(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                downRawY = event.getRawY();
                fromHeader = downY < body.getTop();
                dragging = false;
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                addMovement(event);
                return false;
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(pointer);
                if (index < 0) return dragging;
                addMovement(event);
                float rawY = event.getRawY(index);
                if (!dragging && shouldStartDrag(event.getX(index) - downX, rawY - downRawY)) {
                    dragging = true;
                    anchorRawY = rawY;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) callback.onDrag(rawY - anchorRawY);
                return dragging;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) != pointer) return dragging;
                return finish(true, event);
            case MotionEvent.ACTION_UP:
                return finish(true, event);
            case MotionEvent.ACTION_CANCEL:
                return finish(false, event);
            default:
                return dragging;
        }
    }

    private boolean shouldStartDrag(float dx, float dy) {
        if (Math.abs(dy) <= touchSlop || Math.abs(dy) <= Math.abs(dx)) return false;
        boolean allowed = fromHeader
                || dy > 0 && !canScrollUp(body, downX - body.getLeft(), downY - body.getTop());
        return allowed && callback.onDragStart();
    }

    private boolean finish(boolean released, MotionEvent event) {
        boolean wasDragging = dragging;
        if (dragging) {
            float speed = 0;
            if (released) {
                addMovement(event);
                velocity.computeCurrentVelocity(1000);
                speed = velocity.getYVelocity(pointer);
            }
            callback.onDragEnd(speed);
        }
        dragging = false;
        pointer = -1;
        return wasDragging;
    }

    // The panel moves with the finger, so velocity must be measured in display coordinates.
    private void addMovement(MotionEvent event) {
        MotionEvent screen = MotionEvent.obtain(event);
        screen.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
        velocity.addMovement(screen);
        screen.recycle();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (velocity != null) velocity.recycle();
        velocity = null;
    }

    private static boolean canScrollUp(View view, float x, float y) {
        if (view.getVisibility() != VISIBLE || x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) {
            return false;
        }
        if (view.canScrollVertically(-1)) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (canScrollUp(child, x + group.getScrollX() - child.getLeft(), y + group.getScrollY() - child.getTop())) {
                return true;
            }
        }
        return false;
    }
}
