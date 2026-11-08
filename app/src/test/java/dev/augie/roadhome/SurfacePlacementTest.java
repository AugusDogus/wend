package dev.augie.roadhome;

import android.graphics.Rect;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SurfacePlacementTest {
    // The video fixture's decoder region inside its 1280 x 720 virtual display.
    private final RectF decoder = new RectF(16, 122, 496, 392);

    private static void assertRect(RectF expected, RectF actual) {
        assertEquals(expected.left, actual.left, .01f);
        assertEquals(expected.top, actual.top, .01f);
        assertEquals(expected.right, actual.right, .01f);
        assertEquals(expected.bottom, actual.bottom, .01f);
    }

    @Test public void fittingShowsOnlyTheDecoderRegionFillingTheTarget() {
        SurfacePlacement floating = SurfacePlacement.fit(decoder, new Rect(824, 453, 1256, 696));
        assertEquals(.9f, floating.scale, .0001f);
        assertRect(new RectF(824, 453, 1256, 696), floating.visible());
        assertRect(decoder, floating.crop);
    }

    @Test public void aPortraitDecoderStaysCenteredWithoutDistortion() {
        SurfacePlacement fitted = SurfacePlacement.fit(new RectF(400, 0, 805, 720), new Rect(0, 0, 320, 180));
        RectF visible = fitted.visible();
        assertEquals(160f, visible.centerX(), .01f);
        assertEquals(180f, visible.height(), .01f);
        assertEquals(405f / 720, visible.width() / visible.height(), .001f);
    }

    @Test public void movingBetweenCardAndCornerKeepsTheCropAndMovesTheEdgesLinearly() {
        SurfacePlacement card = SurfacePlacement.fit(decoder, new Rect(109, 111, 429, 291));
        SurfacePlacement corner = SurfacePlacement.fit(decoder, new Rect(824, 453, 1256, 696));
        for (int i = 0; i <= 10; i++) {
            float t = i / 10f;
            SurfacePlacement between = SurfacePlacement.lerp(card, corner, t);
            assertRect(decoder, between.crop);
            assertRect(new RectF(109 + 715 * t, 111 + 342 * t, 429 + 827 * t, 291 + 405 * t), between.visible());
        }
    }

    @Test public void fullscreenIsTheUnscaledDisplay() {
        SurfacePlacement fullscreen = SurfacePlacement.fullscreen(1280, 720);
        assertEquals(1f, fullscreen.scale, 0f);
        assertRect(new RectF(0, 0, 1280, 720), fullscreen.visible());
        Rect crop = new Rect();
        fullscreen.crop(crop);
        assertEquals(new Rect(0, 0, 1280, 720), crop);
    }

    @Test public void aPanelSizedDisplayIsCenteredUnscaledOnTheScreen() {
        // Maps' 832 x 720 panel display, focused on the 1280 x 720 screen before going fullscreen.
        SurfacePlacement centered = SurfacePlacement.centered(832, 720, 1280, 720);
        assertEquals(1f, centered.scale, 0f);
        assertRect(new RectF(224, 0, 1056, 720), centered.visible());
        assertRect(new RectF(0, 0, 832, 720), centered.crop);
    }

    @Test public void aScreenSizedDisplayCenteredIsFullscreen() {
        SurfacePlacement centered = SurfacePlacement.centered(1280, 720, 1280, 720);
        assertRect(SurfacePlacement.fullscreen(1280, 720).visible(), centered.visible());
    }

    @Test public void scalingKeepsTheVisibleCenterAndTheCrop() {
        SurfacePlacement corner = SurfacePlacement.fit(decoder, new Rect(824, 453, 1256, 696));
        SurfacePlacement smaller = corner.scaled(.9f);
        assertEquals(.81f, smaller.scale, .0001f);
        assertRect(decoder, smaller.crop);
        RectF visible = smaller.visible();
        assertEquals(1040f, visible.centerX(), .01f);
        assertEquals(574.5f, visible.centerY(), .01f);
        assertEquals(432 * .9f, visible.width(), .01f);
        assertEquals(243 * .9f, visible.height(), .01f);
    }
}
