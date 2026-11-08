package dev.augie.wend;

import android.graphics.Point;
import android.graphics.Rect;
import dev.augie.wend.FloatingPlacement.Corner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class FloatingPlacementTest {
    // 288 x 162 dp with 16 dp margins at the head unit's 1.5 density, below a 36 px status bar.
    private final FloatingPlacement placement = new FloatingPlacement(new Rect(0, 36, 1280, 720), 432, 243, 24);

    @Test public void eachCornerRestsOneMarginFromItsEdges() {
        assertEquals(new Rect(24, 60, 456, 303), placement.rest(Corner.TOP_LEFT));
        assertEquals(new Rect(824, 60, 1256, 303), placement.rest(Corner.TOP_RIGHT));
        assertEquals(new Rect(24, 453, 456, 696), placement.rest(Corner.BOTTOM_LEFT));
        assertEquals(new Rect(824, 453, 1256, 696), placement.rest(Corner.BOTTOM_RIGHT));
    }

    @Test public void videoSizeKeepsSixteenByNine() {
        Rect rest = placement.rest(Corner.BOTTOM_RIGHT);
        assertEquals(16f / 9, rest.width() / (float) rest.height(), 0.01f);
    }

    @Test public void draggingFollowsTheFingerOneToOne() {
        Rect start = placement.rest(Corner.BOTTOM_RIGHT);
        Rect moved = placement.dragged(start, -317.4f, -120.6f);
        assertEquals(new Rect(507, 332, 939, 575), moved);
    }

    @Test public void draggingNeverLeavesTheVisibleArea() {
        Rect start = placement.rest(Corner.BOTTOM_RIGHT);
        assertEquals(new Rect(848, 477, 1280, 720), placement.dragged(start, 400, 400));
        assertEquals(new Rect(0, 36, 432, 279), placement.dragged(start, -2000, -2000));
    }

    @Test public void aSlowReleaseSnapsToTheNearestCorner() {
        assertEquals(Corner.TOP_LEFT, placement.release(new Rect(300, 100, 732, 343), 0, 0));
        assertEquals(Corner.BOTTOM_RIGHT, placement.release(new Rect(600, 400, 1032, 643), 0, 0));
        assertEquals(Corner.TOP_RIGHT, placement.release(new Rect(700, 120, 1132, 363), 0, 0));
    }

    @Test public void aFlingCarriesTheWindowToTheCornerItIsThrownToward() {
        Rect bottomRight = placement.rest(Corner.BOTTOM_RIGHT);
        assertEquals(Corner.BOTTOM_LEFT, placement.release(bottomRight, -2500, 0));
        assertEquals(Corner.TOP_LEFT, placement.release(bottomRight, -2500, -1500));
        assertEquals(Corner.TOP_RIGHT, placement.release(bottomRight, 0, -1500));
    }

    @Test public void aGentleNudgeDoesNotLeaveTheCurrentCorner() {
        assertEquals(Corner.BOTTOM_RIGHT, placement.release(placement.rest(Corner.BOTTOM_RIGHT), -300, -200));
    }

    @Test public void projectionMatchesANormalScrollDeceleration() {
        assertEquals(499f, FloatingPlacement.projection(1000), 0.5f);
        assertEquals(-998f, FloatingPlacement.projection(-2000), 0.5f);
    }

    @Test public void savedCornerNamesFallBackWhenUnknown() {
        assertEquals(Corner.TOP_LEFT, Corner.parse("TOP_LEFT", Corner.BOTTOM_RIGHT));
        assertEquals(Corner.BOTTOM_RIGHT, Corner.parse(null, Corner.BOTTOM_RIGHT));
        assertEquals(Corner.BOTTOM_RIGHT, Corner.parse("MIDDLE", Corner.BOTTOM_RIGHT));
    }

    @Test public void aFloatingMapMatchesTheFloatingVideosAreaAtThePanelsShape() {
        // The 70% map panel is 832 x 720 px; the floating video is 432 x 243 px.
        Point size = FloatingPlacement.matchingArea(432, 243, 832f / 720);
        assertEquals(new Point(348, 301), size);
        assertEquals(432 * 243, size.x * size.y, 432 * 243 * 0.01f);
        assertEquals(832f / 720, size.x / (float) size.y, 0.01f);
    }

    @Test public void aNarrowerPanelGivesANarrowerWindowOfTheSameArea() {
        // The 60% map panel is 713 x 720 px.
        Point size = FloatingPlacement.matchingArea(432, 243, 713f / 720);
        assertEquals(new Point(322, 326), size);
    }
}
