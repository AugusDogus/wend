package dev.augie.wend;

import android.graphics.Rect;
import android.view.MotionEvent;
import dev.augie.wend.FloatingPlacement.Corner;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class FloatingDragTest {
    private final FloatingPlacement placement = new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24);
    private final Rect resting = placement.rest(Corner.BOTTOM_RIGHT);
    private final List<String> events = new ArrayList<>();
    private final List<Rect> moves = new ArrayList<>();
    private final FloatingDrag drag = new FloatingDrag(placement, 12, new FloatingDrag.Listener() {
        @Override public void moved(Rect bounds) { moves.add(new Rect(bounds)); events.add("moved"); }
        @Override public void released(Rect bounds, Corner corner) { events.add("released " + corner); }
        @Override public void tapped() { events.add("tapped"); }
    });

    private boolean send(int action, long time, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, time, action, x, y, 0);
        try { return drag.handle(event, resting); }
        finally { event.recycle(); }
    }

    @Test public void aTouchThatStaysWithinSlopIsATap() {
        assertTrue(send(MotionEvent.ACTION_DOWN, 0, 1000, 560));
        send(MotionEvent.ACTION_MOVE, 40, 1006, 564);
        send(MotionEvent.ACTION_UP, 90, 1006, 564);
        assertEquals(List.of("tapped"), events);
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

    private void tap(long downTime) {
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(downTime, downTime + 60, action, 1000, 560, 0);
            try { assertTrue(drag.handle(event, resting)); }
            finally { event.recycle(); }
        }
    }

    @Test public void aTapThatLandsBeforeTapsCountIsTakenButDoesNothing() {
        // A second tap on the dock's Maps button can land where the video has just arrived.
        drag.ignoreTapsUntil(5000);
        tap(4999);
        assertEquals(List.of(), events);
        tap(5000);
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
}
