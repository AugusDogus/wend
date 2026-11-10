package dev.augie.wend;

import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MapsTripsTest {
    private static final String MAPS = "com.google.android.apps.maps";
    private static final String MOTEL = "Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482";
    private static final int MAPS_UID = 10111, OTHER_UID = 10200;
    private final MapsTrips trips = Robolectric.buildContentProvider(MapsTrips.class).create("dev.augie.wend.mapstrips").get();

    @Before public void installMaps() {
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).setPackagesForUid(MAPS_UID, MAPS);
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).setPackagesForUid(OTHER_UID, "com.example.other");
    }

    @After public void forget() { MapsTrips.forget(); ShadowBinder.reset(); }

    /** The Maps bridge module, inside Maps, tells Wend where each trip goes as it starts. */
    @Test public void aTripMapsReportsBecomesTheLinkThatNavigatesThereAgain() {
        ShadowBinder.setCallingUid(MAPS_UID);
        trips.call("trip", MOTEL, null);
        Intent link = MapsTrips.navigation(SystemClock.uptimeMillis(), MAPS);
        assertNotNull(link);
        assertEquals(Intent.ACTION_VIEW, link.getAction());
        assertEquals(MAPS, link.getPackage());
        assertEquals("google.navigation", link.getData().getScheme());
        assertEquals("q=" + MOTEL, Uri.decode(link.getData().getEncodedSchemeSpecificPart()));
    }

    @Test public void onlyMapsMayReportATrip() {
        ShadowBinder.setCallingUid(OTHER_UID);
        assertThrows(SecurityException.class, () -> trips.call("trip", "Somewhere else", null));
        assertNull(MapsTrips.navigation(SystemClock.uptimeMillis(), MAPS));
    }

    /**
     * Maps also saves directions the driver only looked at. A trip counts for the guidance that
     * died only if Maps reported it while that guidance ran, or within the 30 s before it began.
     */
    @Test public void aTripReportedBeforeThisGuidanceIsNotItsTrip() {
        ShadowBinder.setCallingUid(MAPS_UID);
        trips.call("trip", MOTEL, null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertNotNull("Guidance began 20 s after the report", MapsTrips.navigation(SystemClock.uptimeMillis(), MAPS));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertNull("Guidance began 40 s after the report", MapsTrips.navigation(SystemClock.uptimeMillis(), MAPS));
        assertNull("Guidance never began", MapsTrips.navigation(null, MAPS));
    }

    /** The module also hands over Maps' guidance notification, for a head unit that shows none. */
    @Test public void guidanceMapsReportsReachesTheTurnCard() {
        Notification guidance = new Notification.Builder(RuntimeEnvironment.getApplication(), "1_foreground_1")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)))
                .setCategory(Notification.CATEGORY_NAVIGATION).setContentTitle("0.4 mi").setContentText("US-101 S").build();
        Bundle extras = new Bundle();
        extras.putParcelable("notification", guidance);
        ShadowBinder.setCallingUid(OTHER_UID);
        assertThrows(SecurityException.class, () -> trips.call("guidance", null, extras));
        ShadowBinder.setCallingUid(MAPS_UID);
        trips.call("guidance", null, extras);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("US-101 S", NavigationNotifications.current().road.toString());
        trips.call("guidance", null, new Bundle());
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(NavigationNotifications.current());
        NavigationNotifications.forget();
    }

    @Test public void nothingReportedIsNoTrip() {
        assertNull(MapsTrips.navigation(SystemClock.uptimeMillis(), MAPS));
        ShadowBinder.setCallingUid(MAPS_UID);
        trips.call("trip", " ", null);
        trips.call("something else", MOTEL, null);
        assertNull(MapsTrips.navigation(SystemClock.uptimeMillis(), MAPS));
    }
}
