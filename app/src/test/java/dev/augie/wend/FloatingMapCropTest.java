package dev.augie.wend;

import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class FloatingMapCropTest {
    private static void assertRect(RectF expected, RectF actual) {
        assertEquals(expected.left, actual.left, .01f);
        assertEquals(expected.top, actual.top, .01f);
        assertEquals(expected.right, actual.right, .01f);
        assertEquals(expected.bottom, actual.bottom, .01f);
    }

    /**
     * Real Google Maps in guidance in the 70% panel (833 x 720 px at 1.5 density): the next-turn
     * card ends 143 px down, the trip sheet starts at 607 px, the speed box ends 107 px from the
     * left and the compass and search buttons start 718 px from it, and the car is centered.
     */
    @Test public void aPortraitMapBetweenMapsOwnControls() {
        RectF crop = FloatingMapCrop.of(833, 720, 1.5f);
        assertRect(new RectF(236.5f, 150, 596.5f, 600), crop);
        assertEquals(.8f, crop.width() / crop.height(), .001f);
    }

    @Test public void theNarrowerPanelKeepsTheSameMapCentered() {
        RectF crop = FloatingMapCrop.of(713, 720, 1.5f);
        assertRect(new RectF(176.5f, 150, 536.5f, 600), crop);
    }

    @Test public void aDisplayTooSmallForThePortraitShowsWhole() {
        assertRect(new RectF(0, 0, 300, 200), FloatingMapCrop.of(300, 200, 1.5f));
        assertRect(new RectF(0, 0, 250, 720), FloatingMapCrop.of(250, 720, 1.5f));
    }
}
