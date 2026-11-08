package dev.augie.roadhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LayoutPreviewTest {
    // Screen dp: the dock and its divider take the first 61 dp of 853.33.
    private static final float CONTENT_START = 61;
    private static final float CONTENT_WIDTH = 853.33f - 61;

    @Test public void mapOnTheLeftSitsAgainstTheDock() {
        assertEquals(CONTENT_START, LayoutPreview.mapStart(0, 0.7f), 0.01f);
    }

    @Test public void mapOnTheRightSitsAgainstTheScreenEdge() {
        float width = LayoutPreview.mapWidth(0.6f);
        assertEquals(853.33f, LayoutPreview.mapStart(1, 0.6f) + width, 0.01f);
    }

    @Test public void widthsAreTheirShareOfTheSpaceBesideTheDock() {
        assertEquals(CONTENT_WIDTH * 0.6f, LayoutPreview.mapWidth(0.6f), 0.01f);
        assertEquals(CONTENT_WIDTH * 0.7f, LayoutPreview.mapWidth(0.7f), 0.01f);
    }

    @Test public void halfwayThroughASideChangeTheMapIsCenteredInTheContent() {
        float start = LayoutPreview.mapStart(0.5f, 0.7f);
        float end = start + LayoutPreview.mapWidth(0.7f);
        assertEquals(start - CONTENT_START, CONTENT_START + CONTENT_WIDTH - end, 0.01f);
    }

    @Test public void onlyCornersFacingTheMediaPanelAreRounded() {
        // Map on the right: rounded toward the media panel on its left, square on the screen edge.
        assertEquals(1, LayoutPreview.cornerRoundness(LayoutPreview.mapStart(1, 0.7f) - CONTENT_START), 0);
        assertEquals(0, LayoutPreview.cornerRoundness(0), 0);
        // Corners round in as the gap opens, rather than popping.
        float partial = LayoutPreview.cornerRoundness(6);
        assertTrue(partial > 0 && partial < 1);
    }
}
