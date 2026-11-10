package dev.augie.wend;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Columns as real Google Maps drew them in the 70% panel (833 x 720 px at 1.5 density) at night,
 * read 32 px in from each side on a simulated drive through Exit 551 of US-101 S.
 */
public class MapsCardEdgeTest {
    private static final int CARD = 0xff015f61, LANES = 0xff005153, LAND = 0xff404b65, WATER = 0xff004d66;
    private static final int HEIGHT = 330;

    /** A column of {@code runs}: pairs of a height and its color, from the top; the last fills the rest. */
    private static int[] column(int... runs) {
        int[] column = new int[HEIGHT];
        int y = 0;
        for (int i = 0; i < runs.length; i += 2) {
            int end = i + 2 == runs.length ? HEIGHT : y + runs[i];
            while (y < end) column[y++] = runs[i + 1];
        }
        return column;
    }

    @Test public void aCardWithOneRoadLineEndsWhereTheMapBegins() {
        assertEquals(143, MapsCardEdge.bottom(column(13, LAND, 130, CARD, 0, LAND), column(13, LAND, 130, CARD, 0, LAND), 1.5f));
    }

    /** "0.1 mi / 551 N State St / Ukiah" above two lane arrows: 175 px of card, then 85 px darker. */
    @Test public void laneArrowsUnderTheCardBelongToIt() {
        int[] side = column(13, LAND, 175, CARD, 85, LANES, 0, LAND);
        assertEquals(273, MapsCardEdge.bottom(side, side, 1.5f));
    }

    /** Past The Forks the map under the card was one flat blue for 42 px on the left and 22 on the right. */
    @Test public void flatMapUnderTheCardIsNotLaneArrows() {
        assertEquals(143, MapsCardEdge.bottom(column(13, WATER, 130, CARD, 42, WATER, 0, LAND),
                column(13, LAND, 130, CARD, 22, WATER, 0, LAND), 1.5f));
        // Even as tall as lane arrows, and alike on both sides: lane arrows are a darker shade of the card.
        int[] side = column(13, LAND, 130, CARD, 85, WATER, 0, LAND);
        assertEquals(143, MapsCardEdge.bottom(side, side, 1.5f));
        // A darker shade that does not end is the map too.
        int[] endless = column(13, LAND, 130, CARD, 0, LANES);
        assertEquals(143, MapsCardEdge.bottom(endless, endless, 1.5f));
    }

    /** At the destination Maps shows no card: map, then its "Arriving at" sheet. */
    @Test public void aDisplayWithoutACardHasNoEdge() {
        assertEquals(0, MapsCardEdge.bottom(column(96, LAND, 100, 0xff313849, 0, 0xff131314),
                column(124, LAND, 70, 0xff1f3962, 0, 0xff131314), 1.5f));
        // One flat color from the top is the map, not a card.
        assertEquals(0, MapsCardEdge.bottom(column(0, WATER), column(0, WATER), 1.5f));
        // A card on one side only is some other layout.
        assertEquals(0, MapsCardEdge.bottom(column(13, LAND, 130, CARD, 0, LAND), column(0, LAND), 1.5f));
        assertEquals(0, MapsCardEdge.bottom(column(13, LAND, 130, CARD, 0, LAND), column(13, LAND, 175, CARD, 0, LAND), 1.5f));
        assertEquals(0, MapsCardEdge.bottom(new int[10], new int[10], 1.5f));
    }
}
