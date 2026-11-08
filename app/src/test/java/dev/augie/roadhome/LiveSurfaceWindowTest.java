package dev.augie.roadhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LiveSurfaceWindowTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();
    private final View card = new View(host);
    private final LiveSurfaceWindow window = new LiveSurfaceWindow(host, "Road Home test window", "RoadHomeTest");
    private final List<String> events = new ArrayList<>();

    private void open() {
        card.layout(0, 0, 320, 180);
        // No compositor frame: exercise the asynchronous window setup lifecycle.
        window.open(card, null, new RectF(0, 0, 1280, 720), new Point(1280, 720),
                () -> events.add("fallback"), () -> events.add("restored"), () -> events.add("reached"));
    }

    @Test public void cancelNeverOpensAnAppAfterLeavingHome() {
        open();
        window.cancel();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("restored"), events);
        assertFalse(window.active());
        host.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    @Test public void missingFrameFallsBackExactlyOnce() {
        open();
        open();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("restored", "fallback"), events);
        assertFalse(window.active());
    }

    private void openFloating() {
        openFloating(LiveSurfaceWindow.Entry.FROM_CARD);
    }

    private void openFloating(LiveSurfaceWindow.Entry entry) {
        card.layout(0, 0, 320, 180);
        window.openFloating(card, null, new RectF(16, 122, 496, 392), new Point(1280, 720),
                new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24),
                FloatingPlacement.Corner.BOTTOM_RIGHT, entry, new LiveSurfaceWindow.Listener() {
                    @Override public void settled(FloatingPlacement.Corner corner) { events.add("settled " + corner); }
                    @Override public void tapped() { events.add("tapped"); }
                }, () -> events.add("restored"));
    }

    @Test public void aFloatingWindowThatNeverGetsAFrameRestoresWithoutOpeningAnything() {
        openFloating();
        assertTrue(window.floating());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("restored"), events);
        assertFalse(window.active());
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    @Test public void homeBeforeTheFloatingWindowIsReadyReturnsToTheCard() {
        openFloating();
        window.collapse();
        assertEquals(List.of("restored"), events);
        assertFalse(window.floating());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(1, events.size());
    }

    @Test public void anEndedSessionDismissesTheFloatingWindowOnce() {
        openFloating();
        window.dismiss("the test session ended");
        window.dismiss("the test session ended");
        window.collapse();
        assertEquals(List.of("restored"), events);
        assertFalse(window.active());
    }

    @Test public void aFloatingWindowLeavesHomeToAndroidUntilItIsFullscreen() {
        openFloating();
        host.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(host).getNextStartedActivity());
        window.cancel();
    }

    @Test public void homeStillReachesRoadHomeAfterADismissalUntilRoadHomeIsShown() {
        open();
        window.dismiss("the test session ended");
        assertFalse(window.active());
        // The last touch can still be on the video's display, where Home would do nothing.
        host.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(MainActivity.class.getName(), shadowOf(host).getNextStartedActivity().getComponent().getClassName());
        window.hostShown();
        host.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(host).getNextStartedActivity());
    }

    @Test public void onlySystemHomeNotificationReturnsToPhysicalHome() {
        open();
        host.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(window.active());
        assertNull(shadowOf(host).getNextStartedActivity());
        host.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(window.active());
        Intent started = shadowOf(host).getNextStartedActivity();
        assertEquals(Intent.ACTION_MAIN, started.getAction());
        assertTrue(started.hasCategory(Intent.CATEGORY_HOME));
        assertEquals(MainActivity.class.getName(), started.getComponent().getClassName());
        window.collapse(); // MainActivity receives the routed Home intent after Binder returns.
        assertFalse(window.active());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("restored"), events);
    }

    @Test public void aWholePresentationAboveRoadHomeIsNotFloating() {
        open();
        assertTrue(window.active());
        assertFalse(window.floating());
        window.cancel();
    }

    @Test public void everyFloatingEntryKeepsPlayingWhileRoadHomeIsHidden() {
        for (LiveSurfaceWindow.Entry entry : LiveSurfaceWindow.Entry.values()) {
            openFloating(entry);
            assertTrue(entry.name(), window.floating());
            window.cancel();
        }
        assertEquals(List.of("restored", "restored", "restored"), events);
    }

    @Test public void homeBeforeAHeldWindowLeavesItsCardRestoresItAtOnce() {
        openFloating(LiveSurfaceWindow.Entry.HELD_AT_CARD);
        window.leaveCard();
        window.collapse();
        assertEquals(List.of("restored"), events);
        assertFalse(window.active());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(1, events.size());
    }

    @Test public void onlyAWholeDisplayCanHideForACorner() {
        open();
        assertFalse(window.hideForCorner(new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24),
                FloatingPlacement.Corner.BOTTOM_RIGHT, null));
        window.emerge();
        assertTrue(window.active());
        assertFalse(window.floating());
        assertTrue(events.isEmpty());
        window.cancel();
    }

    @Test public void growingBeforeTheWindowIsReadyNeverReportsReachingTheWholeDisplay() {
        openFloating(LiveSurfaceWindow.Entry.EMERGING);
        window.expand(() -> events.add("expanded"));
        window.focus(() -> events.add("focused"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(List.of("restored"), events);
        assertFalse(window.active());
    }

    @Test public void homeBeforeAFloatingWindowIsDrawnRestoresItAndContinuesAtOnce() {
        openFloating(LiveSurfaceWindow.Entry.HELD_AT_CARD);
        window.returnOverCard(() -> events.add("arrived"));
        assertEquals(List.of("restored", "arrived"), events);
        assertFalse(window.active());
        window.returnOverCard(() -> events.add("arrived again"));
        assertEquals(List.of("restored", "arrived", "arrived again"), events);
    }
}
