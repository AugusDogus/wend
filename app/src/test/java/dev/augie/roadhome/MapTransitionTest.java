package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.FrameLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSurfaceControl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapTransitionTest {
    @Test public void withoutACoverMapsMovesAndIsRevealedAtOnceInOrder() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        // Never laid out, as before Road Home's first frame: no frozen frame can be held.
        View panel = new View(host);
        MapTransition transition = new MapTransition(host, panel);
        List<String> events = new ArrayList<>();
        transition.move(true, () -> events.add("moved"), () -> events.add("revealed"));
        assertEquals(List.of("moved", "revealed"), events);
        assertFalse(transition.active());
    }

    @Test public void aHeldTakeoverWithoutACoverIsRevealedOnlyOnceMapsHasMoved() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        MapTransition transition = new MapTransition(host, new View(host));
        List<String> events = new ArrayList<>();
        transition.holdThenCrossfade(() -> events.add("covered"), () -> events.add("revealed"));
        // The video must not emerge over Road Home before Maps reaches the main display.
        assertEquals(List.of("covered"), events);
        transition.settleHeld();
        assertEquals(List.of("covered", "revealed"), events);
        transition.settleHeld();
        assertEquals(List.of("covered", "revealed"), events);
        assertFalse(transition.active());
    }

    @Test public void aCanceledHeldTakeoverWithoutACoverIsNeverRevealed() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        MapTransition transition = new MapTransition(host, new View(host));
        List<String> events = new ArrayList<>();
        transition.holdThenCrossfade(() -> events.add("covered"), () -> events.add("revealed"));
        transition.cancel();
        transition.settleHeld();
        assertEquals(List.of("covered"), events);
    }

    /**
     * Maps has moved but has not drawn at its new size: a frozen frame held much longer reads as a
     * hang to the driver, so live Maps shows soon after the move, as it is.
     */
    @Config(shadows = Screenshots.class)
    @Test public void aFrozenFrameStaysAtMostAbout300MsAfterMapsHasMoved() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        View panel = new View(host);
        host.setContentView(panel, new FrameLayout.LayoutParams(833, 720));
        shadowOf(Looper.getMainLooper()).idle();
        MapTransition transition = new MapTransition(host, panel);
        List<String> events = new ArrayList<>();
        transition.holdThenCrossfade(() -> events.add("covered"), () -> events.add("revealed"));
        // The cover starts on its first frame, or at the latest after 200 ms.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(List.of("covered"), events);
        transition.settleHeld();
        // No layer query can report Maps here, as when Maps is slow to draw at its new size.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertTrue("Still frozen just after the move", transition.active());
        // The frozen frame fades out over 160 ms once its deadline passes.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(List.of("covered", "revealed"), events);
        assertFalse(transition.active());
    }

    @Config(shadows = Screenshots.class, qualifiers = "w1280dp-h720dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void aFrozenFrameCanKeepOnlyMapsAndBlankTheRest() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        View panel = new View(host);
        host.setContentView(panel, new FrameLayout.LayoutParams(833, 720));
        shadowOf(Looper.getMainLooper()).idle();
        MapTransition transition = new MapTransition(host, panel);
        transition.holdThenCrossfade(() -> { }, () -> { });
        Bitmap drawn = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        transition.concealOutside(new Rect(224, 0, 1057, 720));
        coverView(host).draw(new android.graphics.Canvas(drawn));
        assertEquals("Outside Maps is blank", android.graphics.Color.BLACK, drawn.getPixel(100, 360));
        assertEquals("Outside Maps is blank", android.graphics.Color.BLACK, drawn.getPixel(1200, 360));
        assertEquals("Maps' frozen pixels stay", Screenshots.MAPS, drawn.getPixel(640, 360));
        transition.cancel();
    }

    /**
     * Device report BUG-2 (fixtures): the frozen frame was the screen as last composited, which can
     * still show Maps a few pixels short of its final size, so its top and left edges showed a strip
     * of the frozen fullscreen video for over a second.
     */
    @Config(shadows = LaggingScreen.class, qualifiers = "w1280dp-h720dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void aFrozenFrameOfMapsShowsOnlyMapsExactlyWhereItEnds() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        View panel = new View(host);
        host.setContentView(panel, new FrameLayout.LayoutParams(833, 720));
        shadowOf(Looper.getMainLooper()).idle();
        MapTransition transition = new MapTransition(host, panel);
        Rect maps = new Rect(224, 0, 1057, 720);
        // Maps' own display, read from its layers: all of its 833 x 720 pixels.
        transition.holdThenCrossfade(new MapTransition.Still(() -> {
            Bitmap display = Bitmap.createBitmap(833, 720, Bitmap.Config.ARGB_8888);
            display.eraseColor(Screenshots.MAPS);
            return display;
        }, maps), () -> { }, () -> { });
        Bitmap drawn = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        coverView(host).draw(new android.graphics.Canvas(drawn));
        assertEquals("Maps' top left corner", Screenshots.MAPS, drawn.getPixel(224, 0));
        assertEquals("Maps' left edge", Screenshots.MAPS, drawn.getPixel(226, 360));
        assertEquals("Maps' top edge", Screenshots.MAPS, drawn.getPixel(640, 2));
        assertEquals("Maps' bottom right corner", Screenshots.MAPS, drawn.getPixel(1056, 719));
        assertEquals("Outside Maps is blank", android.graphics.Color.BLACK, drawn.getPixel(223, 360));
        assertEquals("Outside Maps is blank", android.graphics.Color.BLACK, drawn.getPixel(1057, 360));
        transition.cancel();
    }

    /** Without Maps' own layers, the screen as last composited still keeps nothing but Maps' place. */
    @Config(shadows = LaggingScreen.class, qualifiers = "w1280dp-h720dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test public void aFrozenFrameOfMapsWhoseLayersCannotBeReadKeepsOnlyItsPlaceOfTheScreen() {
        Activity host = Robolectric.buildActivity(Activity.class).setup().get();
        View panel = new View(host);
        host.setContentView(panel, new FrameLayout.LayoutParams(833, 720));
        shadowOf(Looper.getMainLooper()).idle();
        MapTransition transition = new MapTransition(host, panel);
        List<String> events = new ArrayList<>();
        transition.holdThenCrossfade(new MapTransition.Still(() -> {
            throw new IllegalStateException("Android captured none of the display's layers");
        }, new Rect(224, 0, 1057, 720)), () -> events.add("covered"), () -> events.add("revealed"));
        assertTrue("A frozen frame covers the move", transition.active());
        Bitmap drawn = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        coverView(host).draw(new android.graphics.Canvas(drawn));
        assertEquals(Screenshots.MAPS, drawn.getPixel(640, 360));
        assertEquals("Outside Maps is blank", android.graphics.Color.BLACK, drawn.getPixel(100, 360));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(List.of("covered"), events);
        transition.cancel();
    }

    private static View coverView(Activity host) {
        org.robolectric.shadows.ShadowWindowManagerImpl manager =
                org.robolectric.shadow.api.Shadow.extract(host.getSystemService(android.view.WindowManager.class));
        List<View> views = manager.getViews();
        return views.get(views.size() - 1);
    }

    /** Android 10's privileged screenshot, which Robolectric does not provide: Maps everywhere. */
    @Implements(value = SurfaceControl.class, isInAndroidSdk = false)
    public static class Screenshots extends ShadowSurfaceControl {
        static final int MAPS = 0xFF336699;

        @Implementation protected static Bitmap screenshot(Rect crop, int width, int height, int rotation) {
            Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frame.eraseColor(MAPS);
            return frame;
        }
    }

    /**
     * The screen as last composited while Maps grows over the fullscreen video from the bottom
     * right: one frame short of its final size, as in the fixture recording A5.
     */
    @Implements(value = SurfaceControl.class, isInAndroidSdk = false)
    public static class LaggingScreen extends ShadowSurfaceControl {
        static final int VIDEO = 0xFFFFD000;

        @Implementation protected static Bitmap screenshot(Rect crop, int width, int height, int rotation) {
            Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frame.eraseColor(VIDEO);
            new android.graphics.Canvas(frame).drawRect(new Rect(228, 4, 1057, 720), solid(Screenshots.MAPS));
            return frame;
        }

        private static android.graphics.Paint solid(int color) {
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(color);
            return paint;
        }
    }
}
