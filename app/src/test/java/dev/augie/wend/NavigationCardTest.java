package dev.augie.wend;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NavigationCardTest {
    private static final String MAPS = "com.google.android.apps.maps";
    private static final String KEY = "0|com.google.android.apps.maps|1|null|10111";
    private final Context context = RuntimeEnvironment.getApplication();

    /** As Google Maps posted it in live guidance on the emulator (dumpsys notification). */
    private Notification.Builder guidance() {
        return new Notification.Builder(context, "1_foreground_1")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)))
                .setLargeIcon(Icon.createWithBitmap(Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)))
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setOngoing(true)
                .setColor(0xff015f61)
                .setContentTitle("0 ft")
                .setContentText("CA-20 W")
                .setSubText("10 min · 8.4 mi · 5:23 PM PST ETA");
    }

    @Test public void readsTheNextTurnFromMapsGuidanceNotification() {
        NavigationCard card = NavigationCard.from(MAPS, KEY, guidance().build(), MAPS, null);
        assertNotNull(card);
        assertEquals(KEY, card.key);
        assertEquals("0 ft", card.distance.toString());
        assertEquals("CA-20 W", card.road.toString());
        assertEquals("10 min · 8.4 mi · 5:23 PM PST ETA", card.eta.toString());
        assertEquals(0xff015f61, card.color);
        assertNotNull(card.arrow);
    }

    @Test public void ignoresOtherAppsAndMapsOtherNotifications() {
        assertNull(NavigationCard.from("com.example.nav", KEY, guidance().build(), MAPS, null));
        assertNull(NavigationCard.from(MAPS, KEY, guidance().setCategory(Notification.CATEGORY_STATUS).build(), MAPS, null));
    }

    @Test public void ignoresGuidanceWithNothingToShow() {
        assertNull(NavigationCard.from(MAPS, KEY, guidance().setContentTitle("").setContentText(null).build(), MAPS, null));
    }

    @Test public void keepsATurnWithoutAnArrowOrRoad() {
        NavigationCard card = NavigationCard.from(MAPS, KEY,
                guidance().setLargeIcon((Icon) null).setContentText(null).build(), MAPS, null);
        assertNotNull(card);
        assertNull(card.arrow);
        assertEquals("", card.road.toString());
    }

    private NavigationCard card(Notification.Builder guidance, NavigationCard previous) {
        return NavigationCard.from(MAPS, KEY, guidance.build(), MAPS, previous);
    }

    /** The last turn, to the destination: its distance is the whole trip's. */
    private Notification.Builder lastTurn() {
        return guidance().setContentTitle("350 ft").setContentText("Motel 6 Ukiah, CA - North  / 1340 N State St")
                .setSubText("0 min · 350 ft · 5:53 AM PST ETA");
    }

    /** What Maps posted at the destination on a simulated drive: the place, and no distance. */
    private Notification.Builder atDestination() {
        return guidance().setContentTitle("").setContentText("Motel 6 Ukiah, CA - North")
                .setSubText("0 min · 100 ft · 5:53 AM PST ETA");
    }

    /**
     * Maps posts no arrival. Its notification only loses its distance, which is arriving when it
     * follows the last turn. Until then a floating Maps window showed a piece of the "Arriving at"
     * sheet that covers Maps' map there.
     */
    @Test public void losingTheDistanceAfterTheLastTurnIsArriving() {
        NavigationCard lastTurn = card(lastTurn(), null);
        assertFalse(lastTurn.arrived);
        NavigationCard arrived = card(atDestination(), lastTurn);
        assertTrue(arrived.arrived);
        assertTrue("Maps posts it again and again", card(atDestination(), arrived).arrived);
        assertFalse("The next trip's first turn", card(guidance(), arrived).arrived);
    }

    @Test public void losingTheDistanceAnywhereElseIsNotArriving() {
        // Maps posted "CA-20 W" without its "0 ft" for a second as guidance began.
        assertFalse(card(guidance().setContentTitle(""), card(guidance(), null)).arrived);
        // "0.1 mi" to the turn is not the "10.1 mi" left of the trip.
        NavigationCard near = card(guidance().setContentTitle("0.1 mi").setSubText("9 min · 10.1 mi · 5:23 PM PST ETA"), null);
        assertFalse(card(guidance().setContentTitle(""), near).arrived);
        // "Rerouting..." comes without an arrow or the trip.
        assertFalse(card(guidance().setContentTitle("").setContentText("Rerouting...").setSubText(null)
                .setLargeIcon((Icon) null), card(lastTurn(), null)).arrived);
        // Another notification's last turn.
        NavigationCard other = NavigationCard.from(MAPS, "other", lastTurn().build(), MAPS, null);
        assertFalse(card(atDestination(), other).arrived);
        // First heard at the destination, Wend cannot tell.
        assertFalse(card(atDestination(), null).arrived);
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
