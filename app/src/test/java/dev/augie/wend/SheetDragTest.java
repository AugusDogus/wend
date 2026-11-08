package dev.augie.wend;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SheetDragTest {
    // The PX6 runs at 240 dpi: 1 dp is 1.5 px, and the drawer is 456 dp tall.
    private static final float DENSITY = 1.5f;
    private static final float HEIGHT = 456 * DENSITY;
    private final SheetDrag drag = new SheetDrag(DENSITY);

    @Test public void shortSlowDragSpringsBack() {
        assertEquals(SheetDrag.Release.SETTLE, drag.release(HEIGHT * 0.1f, HEIGHT, dpPerSecond(120)));
    }

    @Test public void slowDragPastAQuarterOfTheHeightDismisses() {
        assertEquals(SheetDrag.Release.DISMISS, drag.release(HEIGHT * 0.25f, HEIGHT, 0));
        assertEquals(SheetDrag.Release.SETTLE, drag.release(HEIGHT * 0.24f, HEIGHT, 0));
    }

    @Test public void downwardFlingDismissesFromAShortDistance() {
        assertEquals(SheetDrag.Release.DISMISS, drag.release(HEIGHT * 0.04f, HEIGHT, dpPerSecond(500)));
    }

    @Test public void releasingJustBelowFlingSpeedStillUsesDistance() {
        assertEquals(SheetDrag.Release.SETTLE, drag.release(HEIGHT * 0.1f, HEIGHT, dpPerSecond(490)));
        assertEquals(SheetDrag.Release.DISMISS, drag.release(HEIGHT * 0.3f, HEIGHT, dpPerSecond(490)));
    }

    @Test public void upwardFlingKeepsTheSheetOpenEvenPastAQuarter() {
        assertEquals(SheetDrag.Release.SETTLE, drag.release(HEIGHT * 0.6f, HEIGHT, dpPerSecond(-500)));
    }

    @Test public void downwardFlingFromAnOverdraggedSheetDismisses() {
        assertEquals(SheetDrag.Release.DISMISS, drag.release(-10, HEIGHT, dpPerSecond(800)));
    }

    @Test public void sheetFollowsTheFingerOneToOneDownward() {
        assertEquals(0f, drag.follow(0), 0f);
        assertEquals(137f, drag.follow(137), 0f);
        assertEquals(HEIGHT, drag.follow(HEIGHT), 0f);
    }

    @Test public void upwardOverdragRubberBandsWithinItsLimit() {
        float limit = SheetDrag.OVERDRAG_DP * DENSITY;
        float previous = 0;
        for (int pull = 1; pull <= 2000; pull++) {
            float offset = drag.follow(-pull);
            assertTrue("Overdrag moves up", offset < previous);
            assertTrue("Overdrag resists the finger", -offset < pull);
            assertTrue("Overdrag stays above the sheet's hidden extension", -offset < limit);
            previous = offset;
        }
        // It starts at roughly half the finger's speed instead of hitting a wall.
        assertEquals(-0.55f, drag.follow(-1), 0.01f);
    }

    @Test public void opennessDrivesTheBackdropDimFromHiddenToOpen() {
        assertEquals(1f, SheetDrag.openness(0, HEIGHT), 0f);
        assertEquals(0.75f, SheetDrag.openness(HEIGHT * 0.25f, HEIGHT), 0.0001f);
        assertEquals(0f, SheetDrag.openness(HEIGHT, HEIGHT), 0f);
        assertEquals(0f, SheetDrag.openness(HEIGHT * 2, HEIGHT), 0f);
        assertEquals(1f, SheetDrag.openness(-20, HEIGHT), 0f);
    }

    private static float dpPerSecond(float value) { return value * DENSITY; }
}
