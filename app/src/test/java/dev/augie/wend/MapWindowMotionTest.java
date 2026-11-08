package dev.augie.wend;

import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapWindowMotionTest {
    @Test public void rightPanelGrowsTowardSidebarWithoutMovingItsOuterEdge() {
        Rect out = new Rect();
        MapWindowMotion.interpolate(new Rect(440, 36, 1280, 720), new Rect(0, 0, 1280, 720), .5f, out);
        assertEquals(new Rect(220, 18, 1280, 720), out);
    }
    @Test public void leftPanelGrowsRightWithoutMovingItsLeftEdge() {
        Rect out = new Rect();
        MapWindowMotion.interpolate(new Rect(0, 36, 840, 720), new Rect(0, 0, 1280, 720), .5f, out);
        assertEquals(new Rect(0, 18, 1060, 720), out);
    }
    @Test public void completionExactlyMatchesDestinationEvenWithOddDimensions() {
        Rect end = new Rect(0, 0, 1280, 720), out = new Rect();
        MapWindowMotion.interpolate(new Rect(447, 36, 1280, 720), end, 1, out);
        assertEquals(end, out);
    }
    @Test public void returningRightPanelKeepsTheOuterScreenEdgeFixed() {
        Rect out = new Rect();
        MapWindowMotion.interpolate(new Rect(0, 0, 1280, 720), new Rect(447, 36, 1280, 720), .5f, out);
        assertEquals(new Rect(224, 18, 1280, 720), out);
    }
    @Test public void returningLeftPanelLeavesSpaceForTheDockAndMedia() {
        Rect out = new Rect();
        MapWindowMotion.interpolate(new Rect(0, 0, 1280, 720), new Rect(91, 36, 804, 720), 1, out);
        assertEquals(new Rect(91, 36, 804, 720), out);
    }
}
