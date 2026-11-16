package dev.augie.wend;

import android.graphics.Rect;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NavigationCardTest {
    /** Maps' state as the Maps bridge module read it from Maps 26.43 on the emulator. */
    @Test public void readsTheNextTurnFromMapsGuidanceState() {
        NavigationCard card = NavigationCard.from(GuidanceReports.guiding("3.3 mi", "US-101 S"));
        assertNotNull(card);
        assertEquals("3.3 mi", card.distance.toString());
        assertEquals("US-101 S", card.road.toString());
        assertNotNull("Maps' arrow", card.arrow);
        assertEquals(0xff015f61, card.color);
        assertEquals(322, card.metersToTurn);
        assertEquals(20, card.secondsToTurn);
        assertTrue(card.onRoute);
        assertFalse(card.arrived);
        assertTrue(card.eta.toString(), card.eta.toString().startsWith("9 min · 8.4 mi · "));
        assertTrue("The arrival ends the trip line", card.eta.toString().endsWith(card.arrival()));
    }

    @Test public void mapsGuidingNowhereHasNoTurn() {
        assertNull(NavigationCard.from(GuidanceReports.off()));
        Bundle cruising = new Bundle();
        cruising.putString("state", "CRUISING");
        assertNull("Maps follows the car without a route", NavigationCard.from(cruising));
    }

    /** Maps reroutes: its state has no step, so no distance and no arrow, only what Maps says instead. */
    @Test public void reroutingHasNeitherArrowNorDistance() {
        Bundle report = GuidanceReports.guiding("", "Rerouting...");
        report.remove("arrow");
        report.remove("distance");
        report.putInt("meters_to_step", -1);
        NavigationCard card = NavigationCard.from(report);
        assertNull(card.arrow);
        assertEquals("", card.distance.toString());
        assertEquals("Rerouting...", card.road.toString());
        assertEquals(-1, card.metersToTurn);
    }

    @Test public void theRouteMapsFinishedHasArrived() {
        Bundle report = GuidanceReports.guiding("", "Motel 6 Ukiah, CA - North");
        report.putBoolean("done", true);
        assertTrue(NavigationCard.from(report).arrived);
    }

    /** Maps words the arrival in the route's time zone, as "1:14 PM PST" on an emulator set to Central time. */
    @Test public void theArrivalIsInTheRoutesTimeZone() {
        Bundle report = GuidanceReports.guiding();
        report.putString("time_zone", "America/Los_Angeles");
        java.text.DateFormat clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
        clock.setTimeZone(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
        String expected = clock.format(new java.util.Date(System.currentTimeMillis() + 540_000L));
        String arrival = NavigationCard.from(report).arrival();
        // The minute may turn between the two clocks.
        String later = clock.format(new java.util.Date(System.currentTimeMillis() + 540_000L));
        assertTrue(arrival, arrival.equals(expected) || arrival.equals(later));
    }

    @Test public void aTripWithoutTimeLeftHasNoArrival() {
        Bundle report = GuidanceReports.guiding();
        report.remove("time_left");
        report.putInt("seconds_remaining", -1);
        NavigationCard card = NavigationCard.from(report);
        assertEquals("", card.arrival());
        assertEquals("8.4 mi", card.eta.toString());
    }

    /**
     * On a simulated drive the 290 px window cut "Exit 551 N State St / Ukiah" to "Exit 551 N...".
     * A name too long for its room shrinks first, to no less than two thirds, and is only then cut.
     */
    @Test public void aLongRoadNameShrinksBeforeItIsCut() {
        assertEquals("A name that fits keeps its size", 29f, NavigationCard.fittedTextSize(29, 120, 190), .01f);
        assertEquals("One a little too long shrinks to fit", 29f * 190 / 240, NavigationCard.fittedTextSize(29, 240, 190), .01f);
        assertEquals("A much longer one stops at two thirds", 29f * 2 / 3, NavigationCard.fittedTextSize(29, 600, 190), .01f);
        assertEquals("No room leaves it alone", 29f, NavigationCard.fittedTextSize(29, 240, 0), .01f);
    }

    @Test public void theBandSpansTheWindowsBottomAsMapsOwnPipCardDoes() {
        // Floating Maps at rest in the bottom right corner: 193 x 241 dp at the head unit's 1.5 density.
        Rect window = new Rect(966, 334, 1256, 696);
        // Maps' own PiP card takes the bottom 26.5% of its window.
        assertEquals(new Rect(966, 600, 1256, 696), NavigationCard.band(window));
        // It scales with the window as the window moves between the panel and its corner.
        assertEquals(new Rect(0, 529, 833, 720), NavigationCard.band(new Rect(0, 0, 833, 720)));
    }
}
