package dev.augie.roadhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.view.SurfaceControl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowProcess;
import org.robolectric.shadows.ShadowSurfaceControl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = VideoStillsTest.Screenshots.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VideoStillsTest {
    private static final Rect CARD = new Rect(109, 93, 429, 273);
    private final List<Bitmap> kept = new ArrayList<>();
    private Rect live = CARD;
    private final VideoStills stills = new VideoStills(Runnable::run, new VideoStills.Card() {
        @Override public Rect liveArea() { return live == null ? null : new Rect(live); }
        @Override public int rotation() { return 0; }
        @Override public void kept(Bitmap still) { kept.add(still); }
    });

    @Before public void asThePlatformSignedLauncher() {
        ShadowProcess.setUid(1000);
        Screenshots.taken.clear();
        Screenshots.lastCopy = null;
    }

    @After public void release() { stills.release(); }

    /**
     * YouTube draws its pause overlay over the video about 160 ms after the last moving frame and
     * keeps it there. A frame is kept only once a later check still finds the card playing live.
     */
    @Test public void aStillIsKeptOnlyOnceTheCardIsFoundStillPlayingLiveAfterIt() {
        stills.start();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Not until Road Home has drawn the card without its cover", Screenshots.taken.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoStills.INTERVAL_MS));
        assertEquals("The card's own area, at its own size", List.of(CARD), Screenshots.taken);
        assertTrue("Not kept as it is taken", kept.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoStills.INTERVAL_MS));
        assertEquals(1, kept.size());
        assertEquals(Screenshots.color(1), kept.get(0).getPixel(10, 10));
        assertEquals(CARD.width(), kept.get(0).getWidth());
        assertEquals(CARD.height(), kept.get(0).getHeight());
        assertTrue("One capture a second at most", Screenshots.taken.size() <= 2);
        assertTrue("The newest frame waits for its own check", Screenshots.taken.size() < 2
                || !Screenshots.lastCopy.isRecycled());
    }

    @Test public void aFrameTakenJustBeforePlaybackLeftTheCardIsDroppedAndReleased() {
        stills.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoStills.INTERVAL_MS));
        Bitmap taken = Screenshots.lastCopy;
        // Paused: the frame may already show YouTube's pause overlay.
        stills.interrupt();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(kept.isEmpty());
        assertEquals("Nothing is captured until the card plays live again", 1, Screenshots.taken.size());
        assertTrue("Its memory is released at once", taken.isRecycled());
    }

    @Test public void nothingIsCapturedWhileTheCardDoesNotShowLiveVideo() {
        live = null;
        stills.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(Screenshots.taken.isEmpty());
        assertTrue(kept.isEmpty());
    }

    @Test public void aCardThatStopsShowingLiveVideoKeepsNothingMoreAndStopsCapturing() {
        stills.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VideoStills.INTERVAL_MS));
        // Covered, for example by a sheet or by Maps sweeping over it.
        live = null;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(kept.isEmpty());
        assertEquals(1, Screenshots.taken.size());
    }

    @Test public void withoutThePlatformSignatureNothingIsCaptured() {
        ShadowProcess.setUid(10123);
        stills.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue(Screenshots.taken.isEmpty());
        assertTrue(kept.isEmpty());
    }

    /** Android 10's privileged screenshot, which Robolectric does not provide: one color per frame. */
    @Implements(value = SurfaceControl.class, isInAndroidSdk = false)
    public static class Screenshots extends ShadowSurfaceControl {
        static final List<Rect> taken = new ArrayList<>();
        static Bitmap lastCopy;

        static int color(int frame) { return Color.rgb(frame * 40 % 256, 90, 160); }

        @Implementation protected static Bitmap screenshot(Rect crop, int width, int height, int rotation) {
            taken.add(new Rect(crop));
            assertEquals(crop.width(), width);
            assertEquals(crop.height(), height);
            Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frame.eraseColor(color(taken.size()));
            lastCopy = frame;
            return frame;
        }
    }
}
