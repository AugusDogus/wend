package dev.augie.roadhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The target screen: 1280 x 720 px at 240 dpi, so the 320 x 180 px card is 213 x 120 dp. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "w853dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VideoCardCoverTest {
    private final Activity host = Robolectric.buildActivity(Activity.class).setup().get();
    private final VideoCardCover cover = new VideoCardCover(host);

    /** Device reports RH-4 and BUG-3: no artwork drew a black card with a 16 px glyph. */
    @Test public void withoutArtworkAPausedCardIsAPlayControlOnASubtleSurface() {
        cover.show(VideoCardCover.Control.PLAY, null);
        Bitmap card = draw();
        int surface = card.getPixel(4, card.getHeight() - 4);
        assertNotEquals("Never a black box", Color.BLACK, surface);
        assertTrue("A subtle surface, not a bright one", luminance(surface) < 0.25);
        // The play control is 48 dp (72 px) across, centered: its fill reaches 30 px from center.
        int center = card.getPixel(160, 90);
        int edge = card.getPixel(160 + 30, 90);
        assertNotEquals(surface, edge);
        assertTrue("The control stands out from the surface", luminance(edge) > luminance(surface));
        assertTrue("Its play glyph is the brightest part", luminance(center) > luminance(edge));
        assertEquals("Nothing past the control", surface, card.getPixel(160 + 40, 90));
    }

    @Test public void aStillOfTheLiveVideoWinsOverArtworkAndFillsTheCard() {
        Bitmap artwork = solid(480, 360, Color.RED);
        cover.show(VideoCardCover.Control.PLAY, artwork);
        assertEquals("Artwork is center-cropped to fill the card", Color.RED, draw().getPixel(2, 2));
        cover.keep(solid(320, 180, Color.BLUE));
        Bitmap card = draw();
        assertEquals(Color.BLUE, card.getPixel(2, 2));
        assertEquals(Color.BLUE, card.getPixel(317, 177));
        assertNotEquals("A paused still shows the play control", Color.BLUE, card.getPixel(160 + 30, 90));
        cover.show(VideoCardCover.Control.NONE, artwork);
        assertEquals("While the live video is on its way back, no control", Color.BLUE, draw().getPixel(160 + 30, 90));
        cover.keep(null);
        assertEquals("Without a still, the artwork again", Color.RED, draw().getPixel(2, 2));
        assertFalse("Artwork belongs to the media session and is never recycled", artwork.isRecycled());
    }

    @Test public void stillsAreReleasedOnceReplacedUnlessTheyMayStillBeOnScreen() {
        Bitmap first = solid(320, 180, Color.BLUE);
        Bitmap second = solid(320, 180, Color.GREEN);
        Bitmap third = solid(320, 180, Color.YELLOW);
        cover.keep(first);
        cover.keep(second);
        assertTrue("Never drawn, so released at once", first.isRecycled());
        draw();
        cover.keep(third);
        assertFalse("Drawn, so the renderer may still hold it; it is left to the collector", second.isRecycled());
        cover.release();
        assertTrue("Never drawn", third.isRecycled());
    }

    private Bitmap draw() {
        cover.measure(exactly(320), exactly(180));
        cover.layout(0, 0, 320, 180);
        Bitmap card = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        cover.draw(new Canvas(card));
        return card;
    }

    private static int exactly(int size) {
        return android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY);
    }

    private static Bitmap solid(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static double luminance(int color) {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255;
    }
}
