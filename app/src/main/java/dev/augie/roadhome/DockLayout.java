package dev.augie.roadhome;

/**
 * The dock column's vertical budget, in dp. From top to bottom: the clock and date, the
 * pinned apps, the fixed Apps button, a flexible gap, then the fixed Settings button.
 */
final class DockLayout {
    static final int WIDTH_DP = 60;
    static final int CLOCK_DP = 42;
    static final int DATE_DP = 22;
    static final int BUTTON_DP = 48;
    /** Space above each button. */
    static final int GAP_DP = 4;
    static final int SLOT_DP = BUTTON_DP + GAP_DP;
    static final int BOTTOM_PADDING_DP = 4;

    private DockLayout() { }

    /** How many pinned apps fit in a dock this tall beside the Apps and Settings buttons. */
    static int pinCapacity(int heightDp) {
        int free = heightDp - CLOCK_DP - DATE_DP - BOTTOM_PADDING_DP - 2 * SLOT_DP;
        return Math.max(0, free / SLOT_DP);
    }
}
