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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
        NavigationCard card = NavigationCard.from(MAPS, KEY, guidance().build(), MAPS);
        assertNotNull(card);
        assertEquals(KEY, card.key);
        assertEquals("0 ft", card.distance.toString());
        assertEquals("CA-20 W", card.road.toString());
        assertEquals("10 min · 8.4 mi · 5:23 PM PST ETA", card.eta.toString());
        assertEquals(0xff015f61, card.color);
        assertNotNull(card.arrow);
    }

    @Test public void ignoresOtherAppsAndMapsOtherNotifications() {
        assertNull(NavigationCard.from("com.example.nav", KEY, guidance().build(), MAPS));
        assertNull(NavigationCard.from(MAPS, KEY, guidance().setCategory(Notification.CATEGORY_STATUS).build(), MAPS));
    }

    @Test public void ignoresGuidanceWithNothingToShow() {
        assertNull(NavigationCard.from(MAPS, KEY, guidance().setContentTitle("").setContentText(null).build(), MAPS));
    }

    @Test public void keepsATurnWithoutAnArrowOrRoad() {
        NavigationCard card = NavigationCard.from(MAPS, KEY,
                guidance().setLargeIcon((Icon) null).setContentText(null).build(), MAPS);
        assertNotNull(card);
        assertNull(card.arrow);
        assertEquals("", card.road.toString());
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
