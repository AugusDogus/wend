package dev.augie.roadhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DockLayoutTest {
    @Test public void thePx6ScreenFitsFivePins() {
        // 480 dp tall: the clock and date, Apps and Settings leave room for five 52 dp slots.
        assertEquals(5, DockLayout.pinCapacity(480));
    }

    @Test public void capacityCountsWholeSlotsOnly() {
        int fixed = DockLayout.CLOCK_DP + DockLayout.DATE_DP + DockLayout.BOTTOM_PADDING_DP + 2 * DockLayout.SLOT_DP;
        assertEquals(0, DockLayout.pinCapacity(fixed + DockLayout.SLOT_DP - 1));
        assertEquals(1, DockLayout.pinCapacity(fixed + DockLayout.SLOT_DP));
        assertEquals(3, DockLayout.pinCapacity(fixed + 3 * DockLayout.SLOT_DP + 51));
    }

    @Test public void aScreenTooShortForTheFixedButtonsHasNoPins() {
        assertEquals(0, DockLayout.pinCapacity(100));
        assertEquals(0, DockLayout.pinCapacity(0));
    }
}
