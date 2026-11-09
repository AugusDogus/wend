package dev.augie.wend;

import android.app.Activity;
import android.app.ActivityView;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LiveSurfaceWindowTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();
    private final View card = new View(host);
    private final LiveSurfaceWindow window = new LiveSurfaceWindow(host, "Wend test window", "WendTest");
    private final List<String> events = new ArrayList<>();

    @Before public void drawFramesAtDisplayRate() {
        // Otherwise Robolectric runs every animation frame at once, and nothing is caught moving.
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
    }

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
                FloatingPlacement.Corner.BOTTOM_RIGHT, entry, listener(), () -> events.add("restored"));
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

    @Test public void homeStillReachesWendAfterADismissalUntilWendIsShown() {
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

    @Test public void aWholePresentationAboveWendIsNotFloating() {
        open();
        assertTrue(window.active());
        assertFalse(window.floating());
        window.cancel();
    }

    @Test public void everyFloatingEntryCountsAsFloatingFromOpeningUntilItRestsInItsCorner() throws Exception {
        // EmbeddedVideo.suspend keeps a floating window, and its playing display, when Wend stops.
        for (LiveSurfaceWindow.Entry entry : LiveSurfaceWindow.Entry.values()) {
            DrawnSurface surface = DrawnSurface.of(host);
            openFloating(surface, entry);
            assertTrue(entry + " before its first frame", window.floating());
            commitFrame();
            assertTrue(entry + " drawn at its card or hidden", window.floating());
            window.leaveCard();
            settle();
            assertEquals(entry + " rests in its corner", PLACEMENT.rest(FloatingPlacement.Corner.BOTTOM_RIGHT),
                    touchRegion());
            assertTrue(entry + " in its corner", window.floating());
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

    @Test public void aPresentationThatFloatedStaysOverAnotherAppWhileItGrowsWhole() throws Exception {
        openWhole(DrawnSurface.of(host));
        assertFalse("A whole video opened from its card is above Wend", window.overAnotherApp());
        window.cancel();
        openFloating(DrawnSurface.of(host), LiveSurfaceWindow.Entry.FROM_CARD);
        commitFrame();
        settle();
        assertTrue(window.overAnotherApp());
        // Tapped: it grows above Maps, and Wend only comes forward once it is whole.
        window.expand(() -> events.add("reached"));
        assertFalse(window.floating());
        assertTrue("An app coming to the front now must still end it", window.overAnotherApp());
        window.cancel();
        assertFalse(window.overAnotherApp());
    }

    @Test public void aWholeDisplayNotYetDrawnCannotHideForACorner() {
        open();
        assertFalse(window.hideForCorner(PLACEMENT, FloatingPlacement.Corner.BOTTOM_RIGHT, null));
        window.emerge();
        assertTrue(window.active());
        assertFalse(window.floating());
        assertTrue(events.isEmpty());
        window.cancel();
    }

    /**
     * Found on the fixture emulator: a whole, interactive video below its window's buffer, where
     * floating controls draw, left every touch with that window and none for the video app.
     */
    @Test public void aWholeInteractiveDisplayTakesTouchesAboveItsWindowAndFloatsBelowIt() throws Exception {
        DrawnSurface surface = DrawnSurface.of(host);
        openWhole(surface);
        commitFrame();
        settle();
        assertEquals(List.of("reached"), events);
        assertEquals(List.of(ActivityViewSurface.ABOVE_WINDOW), surface.layers);
        assertTrue(window.hideForCorner(PLACEMENT, FloatingPlacement.Corner.TOP_LEFT, listener()));
        assertEquals("Floating, its controls draw over it again",
                List.of(ActivityViewSurface.ABOVE_WINDOW, ActivityViewSurface.BELOW_WINDOW), surface.layers);
        window.cancel();
    }

    @Test public void onlyAWholeDisplayCanHideForACorner() throws Exception {
        DrawnSurface floatingSurface = DrawnSurface.of(host);
        openFloating(floatingSurface, LiveSurfaceWindow.Entry.FROM_CARD);
        commitFrame();
        settle();
        assertFalse("A floating window is already in its corner",
                window.hideForCorner(PLACEMENT, FloatingPlacement.Corner.TOP_LEFT, listener()));
        assertEquals(PLACEMENT.rest(FloatingPlacement.Corner.BOTTOM_RIGHT), touchRegion());
        window.cancel();
        events.clear();

        DrawnSurface wholeSurface = DrawnSurface.of(host);
        openWhole(wholeSurface);
        commitFrame();
        settle();
        assertEquals(List.of("reached"), events);
        assertTrue(window.hideForCorner(PLACEMENT, FloatingPlacement.Corner.TOP_LEFT, listener()));
        assertTrue(window.floating());
        assertEquals("Hidden: touches reach the app below", new Rect(), touchRegion());
        window.emerge();
        settle();
        assertEquals(PLACEMENT.rest(FloatingPlacement.Corner.TOP_LEFT), touchRegion());
        Rect shown = new Rect();
        wholeSurface.last().round(shown);
        assertEquals(PLACEMENT.rest(FloatingPlacement.Corner.TOP_LEFT), shown);
        window.cancel();
    }

    @Test public void aFloatingWindowIgnoresTapsUntilShortlyAfterItArrives() throws Exception {
        openFloating(DrawnSurface.of(host), LiveSurfaceWindow.Entry.FROM_CARD);
        commitFrame();
        settle();
        Rect corner = PLACEMENT.rest(FloatingPlacement.Corner.BOTTOM_RIGHT);
        assertEquals(corner, touchRegion());
        // A second tap on the dock's Maps button, where the video has just arrived.
        tap(corner.exactCenterX(), corner.exactCenterY());
        tap(corner.exactCenterX(), corner.exactCenterY());
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        assertEquals(List.of(), events);
        assertFalse(window.controlsShown());
        waitFor(LiveSurfaceWindow.ENTRY_TAP_GRACE_MS);
        tap(corner.exactCenterX(), corner.exactCenterY());
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        assertTrue(window.controlsShown());
        window.cancel();
    }

    /** The floating window resting in its corner, past its entry's tap grace. */
    private DrawnSurface restingFloating() throws Exception {
        DrawnSurface surface = DrawnSurface.of(host);
        openFloating(surface, LiveSurfaceWindow.Entry.FROM_CARD);
        commitFrame();
        settle();
        waitFor(LiveSurfaceWindow.ENTRY_TAP_GRACE_MS);
        return surface;
    }

    private static final Rect CORNER = new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24)
            .rest(FloatingPlacement.Corner.BOTTOM_RIGHT);
    /** Robolectric's density is 1, so the 48 dp controls are 48 px. */
    private static final Rect CLOSE = new Rect(CORNER.right - 48, CORNER.top, CORNER.right, CORNER.top + 48);

    @Test public void aTapShowsTheControlsAndTheyFadeOnTheirOwn() throws Exception {
        restingFloating();
        tap(900, 650);
        assertFalse("A second tap could still make it a double tap", window.controlsShown());
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        assertTrue(window.controlsShown());
        // Robolectric's frames make each wait a little longer than asked.
        waitFor(LiveSurfaceWindow.MENU_MS - 400);
        assertTrue(window.controlsShown());
        waitFor(400);
        assertFalse(window.controlsShown());
        assertEquals("A tap only shows the controls", List.of(), events);
        window.cancel();
    }

    @Test public void aTapAwayFromTheShownControlsHidesThem() throws Exception {
        restingFloating();
        tap(900, 650);
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        tap(900, 650);
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        assertFalse(window.controlsShown());
        assertEquals(List.of(), events);
        window.cancel();
    }

    @Test public void aDoubleTapOpensTheWindowFullscreen() throws Exception {
        restingFloating();
        tap(900, 650);
        tap(900, 650);
        waitFor(1000);
        assertEquals(List.of("fullscreen"), events);
        assertFalse(window.controlsShown());
        window.cancel();
    }

    @Test public void theFullscreenControlOpensTheWindowFullscreen() throws Exception {
        restingFloating();
        tap(900, 650);
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        tap(CORNER.exactCenterX(), CORNER.exactCenterY());
        assertEquals(List.of("fullscreen"), events);
        window.cancel();
    }

    @Test public void theCloseControlClosesTheWindow() throws Exception {
        restingFloating();
        tap(CLOSE.exactCenterX(), CLOSE.exactCenterY());
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        assertEquals("Hidden controls take no taps", List.of(), events);
        assertTrue("That tap showed them", window.controlsShown());
        tap(CLOSE.exactCenterX(), CLOSE.exactCenterY());
        assertEquals("A shown control acts at once", List.of("closed"), events);
        assertEquals("Touches reach the app below at once", new Rect(), touchRegion());
        settle();
        assertEquals(List.of("closed", "restored"), events);
        assertFalse(window.active());
    }

    /** Feedback: floating Maps was an odd shape showing all of Maps' controls. */
    @Test public void aWindowCanFloatOnlyPartOfWhatItsCardShows() throws Exception {
        DrawnSurface surface = DrawnSurface.of(host);
        card.layout(0, 0, 320, 180);
        RectF panel = new RectF(0, 0, 833, 720), map = new RectF(236.5f, 150, 596.5f, 600);
        Rect corner = new Rect(966, 334, 1256, 696);
        window.openFloating(card, surface, panel, map, new Point(833, 720),
                new FloatingPlacement(new Rect(0, 0, 1280, 720), 290, 362, 24),
                FloatingPlacement.Corner.BOTTOM_RIGHT, LiveSurfaceWindow.Entry.FROM_CARD, listener(),
                () -> events.add("restored"));
        commitFrame();
        RectF leaving = surface.drawn.get(0);
        assertEquals("It leaves the card showing all of the panel", 833f / 720,
                leaving.width() / leaving.height(), .001f);
        settle();
        assertEquals(corner, touchRegion());
        Rect shown = new Rect();
        surface.last().round(shown);
        assertEquals("Floating, it shows only the map", corner, shown);
        window.cancel();
    }

    /** Feedback: tapped, floating Maps grew to a small area first and snapped to fullscreen later. */
    @Config(qualifiers = "w1280dp-h720dp-land-mdpi")
    @Test public void aHeldWindowGrowsStraightToItsResizedDisplay() throws Exception {
        DrawnSurface surface = DrawnSurface.of(host);
        card.layout(0, 0, 320, 180);
        window.openFloating(card, surface, new RectF(0, 0, 833, 720), new RectF(236.5f, 150, 596.5f, 600),
                new Point(833, 720), new FloatingPlacement(new Rect(0, 0, 1280, 720), 290, 362, 24),
                FloatingPlacement.Corner.BOTTOM_RIGHT, LiveSurfaceWindow.Entry.FROM_CARD, listener(),
                () -> events.add("restored"));
        commitFrame();
        settle();
        waitFor(LiveSurfaceWindow.ENTRY_TAP_GRACE_MS);
        window.freeze(android.graphics.Bitmap.createBitmap(360, 450, android.graphics.Bitmap.Config.ARGB_8888));
        tap(1100, 500);
        tap(1100, 500);
        waitFor(1000);
        assertEquals("Held, it takes no gestures", List.of(), events);
        int before = surface.drawn.size();
        // Maps has laid out at the screen's size behind it.
        window.focus(new RectF(460, 150, 820, 600), new Point(1280, 720), () -> events.add("reached"));
        settle();
        assertEquals(List.of("reached"), events);
        List<RectF> growth = surface.drawn.subList(before, surface.drawn.size());
        Rect last = new Rect();
        growth.get(growth.size() - 1).round(last);
        assertEquals("It ends as the whole screen", new Rect(0, 0, 1280, 720), last);
        for (int i = 1; i < growth.size(); i++) {
            assertTrue("It only grows, frame " + i, growth.get(i).width() >= growth.get(i - 1).width()
                    && growth.get(i).height() >= growth.get(i - 1).height());
        }
        assertEquals("No touches reach it until it is moved", new Rect(), touchRegion());
        window.cancel();
    }

    /** Below the fullscreen control, as Android 10 puts a PiP's media action. */
    private static final Rect PLAY_PAUSE = new Rect(CORNER.centerX() - 24, CORNER.bottom - 48,
            CORNER.centerX() + 24, CORNER.bottom);

    @Test public void thePlaybackControlPlaysOrPausesAndKeepsTheControlsUp() throws Exception {
        List<String> toggles = new ArrayList<>();
        window.playback(FloatingMenu.Playback.PLAYING, () -> toggles.add("pause"));
        restingFloating();
        tap(900, 560);
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        waitFor(LiveSurfaceWindow.MENU_MS - 1000);
        tap(PLAY_PAUSE.exactCenterX(), PLAY_PAUSE.exactCenterY());
        assertEquals(List.of("pause"), toggles);
        // The session reports the pause; the control now offers play.
        window.playback(FloatingMenu.Playback.PAUSED, () -> toggles.add("play"));
        waitFor(1500);
        assertTrue("A press restarts the controls' fade", window.controlsShown());
        tap(PLAY_PAUSE.exactCenterX(), PLAY_PAUSE.exactCenterY());
        assertEquals(List.of("pause", "play"), toggles);
        assertEquals("Only playback changed", List.of(), events);
        window.cancel();
    }

    @Test public void aWindowWithoutMediaHasNoPlaybackControl() throws Exception {
        restingFloating();
        tap(900, 560);
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        tap(PLAY_PAUSE.exactCenterX(), PLAY_PAUSE.exactCenterY());
        waitFor(FloatingDrag.DOUBLE_TAP_MS);
        assertFalse("That tap was on the dimmed window, which hides the controls", window.controlsShown());
        window.cancel();
    }

    /** Feedback: the floating video disappeared when it ended, so another could not be chosen. */
    @Test public void anEndedVideoKeepsFloatingAndOffersPlayUntilItPlaysAgain() throws Exception {
        List<String> toggles = new ArrayList<>();
        window.playback(FloatingMenu.Playback.PLAYING, () -> toggles.add("pause"));
        restingFloating();
        assertFalse(window.controlsShown());
        window.playback(FloatingMenu.Playback.ENDED, () -> toggles.add("play"));
        assertTrue(window.floating());
        assertTrue("It offers play at once", window.controlsShown());
        waitFor(LiveSurfaceWindow.MENU_MS * 3);
        assertTrue("and keeps offering it", window.controlsShown());
        tap(PLAY_PAUSE.exactCenterX(), PLAY_PAUSE.exactCenterY());
        assertEquals(List.of("play"), toggles);
        window.playback(FloatingMenu.Playback.PLAYING, () -> toggles.add("pause"));
        waitFor(LiveSurfaceWindow.MENU_MS + 500);
        assertFalse("Playing again, the controls fade", window.controlsShown());
        // A double tap still brings it fullscreen, where another video can be chosen.
        tap(900, 560);
        tap(900, 560);
        assertEquals(List.of("fullscreen"), events);
        window.cancel();
    }

    @Test public void aWindowDraggedOffTheBottomLeavesDownwardAndCloses() throws Exception {
        DrawnSurface surface = restingFloating();
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down, 1040, 574);
        for (int y = 600; y <= 700; y += 25) {
            waitFor(100);
            touch(MotionEvent.ACTION_MOVE, down, 1040, y);
        }
        waitFor(100);
        touch(MotionEvent.ACTION_UP, down, 1040, 700);
        assertEquals(List.of("closed"), events);
        settle();
        assertEquals(List.of("closed", "restored"), events);
        assertTrue("It left below the screen", surface.last().top >= 720);
        assertFalse(window.active());
    }

    private static void waitFor(int ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    @Test public void aSnappingWindowTakesTouchesWhereItIsDrawn() throws Exception {
        DrawnSurface surface = DrawnSurface.of(host);
        openFloating(surface, LiveSurfaceWindow.Entry.FROM_CARD);
        commitFrame();
        settle();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LiveSurfaceWindow.ENTRY_TAP_GRACE_MS));
        // Thrown left from the bottom-right corner.
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down, 1040, 574);
        for (int x = 940; x >= 740; x -= 100) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            touch(MotionEvent.ACTION_MOVE, down, x, 574);
        }
        touch(MotionEvent.ACTION_UP, down, 740, 574);
        assertEquals(List.of("settled BOTTOM_LEFT"), events);
        Rect released = new Rect();
        surface.last().round(released);
        assertEquals("Released where it was dragged", released, touchRegion());
        // One display frame at a time from here.
        ShadowChoreographer.setPaused(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
        Rect drawn = new Rect();
        surface.last().round(drawn);
        assertNotEquals("Still travelling", released, drawn);
        assertNotEquals("Still travelling", PLACEMENT.rest(FloatingPlacement.Corner.BOTTOM_LEFT), drawn);
        assertEquals(drawn, touchRegion());
        // Caught where it is drawn: it stops there and the touch is its own.
        touch(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis(), drawn.exactCenterX(), drawn.exactCenterY());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        Rect caught = new Rect();
        surface.last().round(caught);
        assertEquals(drawn, caught);
        assertEquals(drawn, touchRegion());
        window.cancel();
    }

    @Test public void aWholeDisplayFloatedForMapsNeverOpensItsTaskDirectlyWhenItFails() throws Exception {
        DrawnSurface surface = DrawnSurface.of(host);
        openWhole(surface);
        commitFrame();
        settle();
        assertTrue(window.hideForCorner(PLACEMENT, FloatingPlacement.Corner.BOTTOM_RIGHT, listener()));
        surface.failing = true;
        window.emerge();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        // Opening the task directly would move it fullscreen over Maps.
        assertEquals(List.of("reached", "restored"), events);
        assertFalse(window.active());
    }

    private static final FloatingPlacement PLACEMENT = new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24);

    private LiveSurfaceWindow.Listener listener() {
        return new LiveSurfaceWindow.Listener() {
            @Override public void settled(FloatingPlacement.Corner corner) { events.add("settled " + corner); }
            @Override public void fullscreen() { events.add("fullscreen"); }
            @Override public void closed() { events.add("closed"); }
        };
    }

    private void openFloating(ActivityViewSurface surface, LiveSurfaceWindow.Entry entry) {
        card.layout(0, 0, 320, 180);
        window.openFloating(card, surface, new RectF(16, 122, 496, 392), new Point(1280, 720), PLACEMENT,
                FloatingPlacement.Corner.BOTTOM_RIGHT, entry, listener(), () -> events.add("restored"));
    }

    private void openWhole(ActivityViewSurface surface) {
        card.layout(0, 0, 320, 180);
        window.open(card, surface, new RectF(16, 122, 496, 392), new Point(1280, 720),
                () -> events.add("fallback"), () -> events.add("restored"), () -> events.add("reached"));
    }

    /** Lets any motion under way finish. */
    private static void settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400)); }

    private View overlay() {
        ShadowWindowManagerImpl manager = Shadow.extract(host.getSystemService(WindowManager.class));
        List<View> views = manager.getViews();
        return views.get(views.size() - 1);
    }

    /** Android commits the overlay window's first frame, so it can present the display. */
    private void commitFrame() throws ReflectiveOperationException {
        Method capture = ViewTreeObserver.class.getDeclaredMethod("captureFrameCommitCallbacks");
        capture.setAccessible(true);
        Object callbacks = capture.invoke(overlay().getViewTreeObserver());
        assertTrue("The window waits for its first frame", callbacks instanceof List<?>);
        for (Object callback : (List<?>) callbacks) {
            if (callback instanceof Runnable) ((Runnable) callback).run();
        }
    }

    /** What WindowManager lets the overlay window take: null for the whole window. */
    private Rect touchRegion() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
        Object info = type.getConstructor().newInstance();
        Method compute = ViewTreeObserver.class.getDeclaredMethod("dispatchOnComputeInternalInsets", type);
        compute.setAccessible(true);
        compute.invoke(overlay().getViewTreeObserver(), info);
        Field insets = type.getDeclaredField("mTouchableInsets");
        insets.setAccessible(true);
        if (insets.getInt(info) == 0) return null;
        Object region = type.getField("touchableRegion").get(info);
        assertTrue(region instanceof Region);
        return ((Region) region).getBounds();
    }

    private void touch(int action, long downTime, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        try { overlay().dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private void tap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, down, x, y);
        touch(MotionEvent.ACTION_UP, down, x, y);
    }

    /**
     * The presented display's own surface. Robolectric windows have no compositor surface, so it
     * is attached without reparenting; every placement after that is the real one, recorded.
     */
    private static final class DrawnSurface extends ActivityViewSurface {
        final List<RectF> drawn = new ArrayList<>();
        boolean failing;

        private DrawnSurface(ActivityView view) throws ReflectiveOperationException {
            super(view, PreviewInput.FORWARDED);
        }

        static DrawnSurface of(Activity host) throws ReflectiveOperationException {
            ActivityView view = new ActivityView(host);
            // What ActivityView holds once its SurfaceView is created.
            Field root = ActivityView.class.getDeclaredField("mRootSurfaceControl");
            root.setAccessible(true);
            root.set(view, new SurfaceControl.Builder().setName("Wend test display").build());
            return new DrawnSurface(view);
        }

        @Override void attach(View destination, SurfacePlacement placement, float radius, float alpha) {
            drawn.add(placement.visible());
        }

        @Override void place(SurfaceControl.Transaction transaction, SurfacePlacement placement, float radius,
                float alpha) throws ReflectiveOperationException {
            if (failing) throw new IllegalStateException("The test compositor rejected the placement");
            drawn.add(placement.visible());
            super.place(transaction, placement, radius, alpha);
        }

        RectF last() { return drawn.get(drawn.size() - 1); }

        /** Layers it was given in its presenting window, after the one {@link #attach} sets. */
        final List<Integer> layers = new ArrayList<>();

        @Override void layer(int layer) { layers.add(layer); }
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
