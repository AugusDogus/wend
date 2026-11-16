package dev.augie.wend;

import android.os.Looper;
import java.time.Duration;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapsGuidanceTest {
    @After public void forget() { MapsGuidance.forget(); }

    @Test public void mapsWasGuidingWhileItGuidesAndShortlyAfterItStops() {
        MapsGuidance.reportedByMaps(GuidanceReports.guiding());
        assertNotNull(MapsGuidance.current());
        assertTrue(MapsGuidance.guidedWithin(5000));
        MapsGuidance.reportedByMaps(GuidanceReports.off());
        assertNull(MapsGuidance.current());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue("Still within the window", MapsGuidance.guidedWithin(5000));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertFalse("Guidance that ended a while ago", MapsGuidance.guidedWithin(5000));
    }

    @Test public void guidanceBeginsOnceAndEndsWithMapsProcess() {
        MapsGuidance.reportedByMaps(GuidanceReports.guiding());
        Long began = MapsGuidance.began();
        assertNotNull(began);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        MapsGuidance.reportedByMaps(GuidanceReports.guiding("0.1 mi", "CA-20 W"));
        assertEquals("The next step of the same guidance", began, MapsGuidance.began());
        MapsGuidance.mapsGone();
        assertNull(MapsGuidance.current());
    }

    @Test public void aReportOfNothingChangesNothing() {
        MapsGuidance.reportedByMaps(GuidanceReports.guiding());
        MapsGuidance.reportedByMaps(null);
        assertNotNull(MapsGuidance.current());
    }

    @Test public void listenersHearEachTurnAndTheEnd() {
        java.util.List<NavigationCard> heard = new java.util.ArrayList<>();
        MapsGuidance.Listener listener = heard::add;
        MapsGuidance.listen(listener);
        MapsGuidance.reportedByMaps(GuidanceReports.guiding());
        MapsGuidance.reportedByMaps(GuidanceReports.off());
        MapsGuidance.stop(listener);
        assertEquals(2, heard.size());
        assertNotNull(heard.get(0));
        assertNull(heard.get(1));
    }

    @Test public void mapsShieldsJoinTheTurnWhoseRoadTheyName() {
        RoadShields twenty = RoadShields.from(RoadShieldsTest.reported("CA-20 W WEST", new int[]{0}, new int[]{7}, RoadShieldsTest.shield()));
        MapsGuidance.reportedByMaps(GuidanceReports.guiding("0 ft", "CA-20 W"));
        assertNull(MapsGuidance.current().shields);
        MapsGuidance.shieldsReportedByMaps(twenty);
        assertEquals(twenty, MapsGuidance.current().shields);
        MapsGuidance.reportedByMaps(GuidanceReports.guiding("0.2 mi", "CA-20 W"));
        assertEquals("The same road at the next update", twenty, MapsGuidance.current().shields);
        MapsGuidance.reportedByMaps(GuidanceReports.guiding("400 ft", "N State St"));
        assertNull("A turn onto another road", MapsGuidance.current().shields);
        MapsGuidance.shieldsReportedByMaps(null);
        MapsGuidance.reportedByMaps(GuidanceReports.off());
        MapsGuidance.shieldsReportedByMaps(twenty);
        MapsGuidance.reportedByMaps(GuidanceReports.guiding("0 ft", "CA-20 W"));
        assertEquals("Shields that came first wait for their turn", twenty, MapsGuidance.current().shields);
        MapsGuidance.reportedByMaps(GuidanceReports.off());
        MapsGuidance.reportedByMaps(GuidanceReports.guiding("0 ft", "CA-20 W"));
        assertNull("Ended guidance forgets them", MapsGuidance.current().shields);
    }
}
