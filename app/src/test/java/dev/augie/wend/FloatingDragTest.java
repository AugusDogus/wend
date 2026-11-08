package dev.augie.wend;

import android.graphics.Rect;
import android.os.Looper;
import android.view.MotionEvent;
import dev.augie.wend.FloatingPlacement.Corner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class FloatingDragTest {
    private final FloatingPlacement placement = new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24);
    private final Rect resting = placement.rest(Corner.BOTTOM_RIGHT);
    /** A control drawn over the window's top right, as the close control is. */
    private final Rect control = new Rect(1208, 453, 1256, 501);
    private final List<String> events = new ArrayList<>();
    private final List<Rect> moves = new ArrayList<>();
    private final FloatingDrag drag = new FloatingDrag(placement, 12, 750, new FloatingDrag.Listener() {
        @Override public void moved(Rect bounds) { moves.add(new Rect(bounds)); events.add("moved"); }
        @Override public void released(Rect bounds, Corner corner) { events.add("released " + corner); }
        @Override public void dismissed(Rect bounds) { events.add("dismissed"); }
        @Override public boolean pressed(float x, float y) {
            if (!control.contains(Math.round(x), Math.round(y))) return false;
            events.add("control");
            return true;
        }
        @Override public void tapped() { events.add("tapped"); }
        @Override public void doubleTapped() { events.add("double tapped"); }
    });

    private boolean send(int action, long time, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, time, action, x, y, 0);
        try { return drag.handle(event, resting); }
        finally { event.recycle(); }
    }

    /** A tap whose finger goes down at {@code time} and lifts 60 ms later. */
    private void tap(long time, float x, float y) {
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(time, action == MotionEvent.ACTION_DOWN ? time : time + 60,
                    action, x, y, 0);
            try { assertTrue(drag.handle(event, resting)); }
            finally { event.recycle(); }
        }
    }

    private void tap(long time) { tap(time, 1000, 560); }

    private static void wait(int ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    @Test public void aTouchThatStaysWithinSlopIsATapOnceNoSecondTapFollows() {
        assertTrue(send(MotionEvent.ACTION_DOWN, 0, 1000, 560));
        send(MotionEvent.ACTION_MOVE, 40, 1006, 564);
        send(MotionEvent.ACTION_UP, 90, 1006, 564);
        assertEquals("It may still become a double tap", List.of(), events);
        wait(FloatingDrag.DOUBLE_TAP_MS);
        assertEquals(List.of("tapped"), events);
    }

    @Test public void twoQuickTapsAreOneDoubleTap() {
        tap(0);
        tap(200);
        wait(1000);
        assertEquals(List.of("double tapped"), events);
    }

    @Test public void twoSlowTapsAreTwoTaps() {
        tap(0);
        wait(FloatingDrag.DOUBLE_TAP_MS);
        tap(1000);
        wait(FloatingDrag.DOUBLE_TAP_MS);
        assertEquals(List.of("tapped", "tapped"), events);
    }

    @Test public void aTapOnAControlActsAtOnceAndStartsNoDoubleTap() {
        tap(0, 1230, 470);
        assertEquals(List.of("control"), events);
        tap(150);
        wait(1000);
        assertEquals("The next tap is a tap of its own", List.of("control", "tapped"), events);
    }

    @Test public void aDragAfterATapIsNotADoubleTap() {
        tap(0);
        send(MotionEvent.ACTION_DOWN, 150, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 600, 400, 300);
        send(MotionEvent.ACTION_UP, 1000, 400, 300);
        wait(1000);
        assertEquals("released TOP_LEFT", events.get(events.size() - 1));
        assertTrue(!events.contains("tapped") && !events.contains("double tapped"));
    }

    @Test public void theWindowFollowsTheGrabPointExactly() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 16, 940, 530);
        send(MotionEvent.ACTION_MOVE, 32, 700, 400);
        Rect last = moves.get(moves.size() - 1);
        assertEquals(resting.left - 300, last.left);
        assertEquals(resting.top - 160, last.top);
        assertEquals(resting.width(), last.width());
    }

    @Test public void aQuickThrowLeftSnapsToTheOppositeSideCorner() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 16, 960, 560);
        send(MotionEvent.ACTION_MOVE, 32, 900, 560);
        send(MotionEvent.ACTION_MOVE, 48, 820, 560);
        send(MotionEvent.ACTION_UP, 56, 800, 560);
        assertEquals("released BOTTOM_LEFT", events.get(events.size() - 1));
    }

    @Test public void aSlowDragReleasedNearItsStartSettlesBack() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 200, 970, 550);
        send(MotionEvent.ACTION_MOVE, 600, 960, 545);
        send(MotionEvent.ACTION_UP, 1000, 960, 545);
        assertEquals("released BOTTOM_RIGHT", events.get(events.size() - 1));
    }

    @Test public void aWindowDraggedHalfOffTheBottomIsDismissed() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 400, 1000, 700);
        send(MotionEvent.ACTION_MOVE, 800, 1000, 690);
        send(MotionEvent.ACTION_UP, 1200, 1000, 690);
        assertEquals("dismissed", events.get(events.size() - 1));
        assertTrue(!events.contains("released BOTTOM_RIGHT"));
    }

    @Test public void aWindowDraggedSlightlyOffTheBottomReturnsToItsCorner() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 400, 1000, 600);
        send(MotionEvent.ACTION_UP, 1200, 1000, 600);
        assertEquals("released BOTTOM_RIGHT", events.get(events.size() - 1));
    }

    @Test public void aDownwardFlingFromABottomCornerIsDismissed() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 16, 1000, 580);
        send(MotionEvent.ACTION_MOVE, 32, 1000, 610);
        send(MotionEvent.ACTION_UP, 40, 1000, 625);
        assertEquals("dismissed", events.get(events.size() - 1));
    }

    @Test public void aTapThatLandsBeforeTapsCountIsTakenButDoesNothing() {
        // A second tap on the dock's Maps button can land where the video has just arrived.
        drag.ignoreTapsUntil(5000);
        tap(4999);
        wait(1000);
        assertEquals(List.of(), events);
        tap(5000);
        wait(1000);
        assertEquals(List.of("tapped"), events);
    }

    @Test public void theWindowCanStillBeDraggedBeforeTapsCount() {
        drag.ignoreTapsUntil(5000);
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 500, 400, 300);
        send(MotionEvent.ACTION_UP, 1000, 400, 300);
        assertEquals("released TOP_LEFT", events.get(events.size() - 1));
    }

    @Test public void aCanceledDragStillSettlesIntoACorner() {
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 500, 400, 300);
        send(MotionEvent.ACTION_CANCEL, 1000, 400, 300);
        assertEquals("released TOP_LEFT", events.get(events.size() - 1));
        assertTrue(!events.contains("tapped"));
    }

    @Test public void aCanceledDragOffTheBottomStillReturnsToACorner() {
        // An interrupted gesture never closes the window.
        send(MotionEvent.ACTION_DOWN, 0, 1000, 560);
        send(MotionEvent.ACTION_MOVE, 500, 1000, 700);
        send(MotionEvent.ACTION_CANCEL, 1000, 1000, 700);
        assertEquals("released BOTTOM_RIGHT", events.get(events.size() - 1));
    }
}
