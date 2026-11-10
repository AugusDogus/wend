package dev.augie.wend;

import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        RectF crop = FloatingMapCrop.of(833, 720, 1.5f, false, 0);
        assertRect(new RectF(236.5f, 150, 596.5f, 600), crop);
        assertEquals(.8f, crop.width() / crop.height(), .001f);
    }

    /**
     * With the next-turn card across the window's bottom 26.5%, the same portrait moves 47 dp down:
     * the car, which Maps draws 468 px down in guidance, sits just above the middle of the map left
     * visible, and the top of the trip sheet goes under the card.
     */
    @Test public void underTheTurnCardTheCarStaysInView() {
        RectF crop = FloatingMapCrop.of(833, 720, 1.5f, true, 0);
        assertRect(new RectF(236.5f, 220.5f, 596.5f, 670.5f), crop);
        float visibleBottom = crop.bottom - crop.height() * .265f;
        assertTrue(468 < visibleBottom - 60);
        assertTrue(607 > visibleBottom);
    }

    /**
     * With lane arrows Maps' own card ends 273 px down, not 143, and a strip of it showed along the
     * window's top. The portrait starts under the card as measured, as far down as still keeps
     * Maps' trip sheet, from 607 px, under the window's card.
     */
    @Test public void theMapStartsBelowMapsOwnCardHoweverTallItIs() {
        assertEquals("One road line", 220.5f, FloatingMapCrop.of(833, 720, 1.5f, true, 143).top, .01f);
        RectF lanes = FloatingMapCrop.of(833, 720, 1.5f, true, 273);
        assertEquals(275, lanes.top, .01f);
        assertEquals(450, lanes.height(), .01f);
        assertTrue(607.5f >= lanes.bottom - lanes.height() * .265f);
        RectF taller = FloatingMapCrop.of(833, 720, 1.5f, true, 330);
        assertEquals("No further than the trip sheet allows", 607.5f, taller.bottom - taller.height() * .265f, .01f);
        assertEquals("Without the window's card Maps is not guiding", 150, FloatingMapCrop.of(833, 720, 1.5f, false, 273).top, .01f);
    }

    @Test public void theNarrowerPanelKeepsTheSameMapCentered() {
        RectF crop = FloatingMapCrop.of(713, 720, 1.5f, false, 0);
        assertRect(new RectF(176.5f, 150, 536.5f, 600), crop);
    }

    @Test public void aDisplayTooSmallForThePortraitShowsWhole() {
        assertRect(new RectF(0, 0, 300, 200), FloatingMapCrop.of(300, 200, 1.5f, false, 0));
        assertRect(new RectF(0, 0, 250, 720), FloatingMapCrop.of(250, 720, 1.5f, false, 0));
    }
}
