package dev.augie.wend;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import java.time.Duration;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NavigationNotificationsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final NavigationNotifications notifications = Robolectric.setupService(NavigationNotifications.class);

    @After public void disconnect() { notifications.onListenerDisconnected(); NavigationNotifications.forget(); }

    private StatusBarNotification guidance() { return guidance("0 ft", "CA-20 W", "9 min · 8.4 mi · 6:02 AM PST ETA"); }

    private StatusBarNotification guidance(String distance, String road, String trip) {
        Notification notification = new Notification.Builder(context, "1_foreground_1")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)))
                .setLargeIcon(Icon.createWithBitmap(Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)))
                .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true)
                .setContentTitle(distance).setContentText(road).setSubText(trip).build();
        return new StatusBarNotification(EmbeddedMaps.PACKAGE, EmbeddedMaps.PACKAGE, 1, null, 10111, 0, 0,
                notification, Process.myUserHandle(), 0);
    }

    @Test public void mapsWasGuidingWhileItsNotificationShowsAndShortlyAfterItGoes() {
        StatusBarNotification guidance = guidance();
        notifications.onNotificationPosted(guidance);
        assertNotNull(NavigationNotifications.current());
        assertTrue(NavigationNotifications.guidedWithin(5000));
        // Maps' process dies: Android removes its notification, before or after its task.
        notifications.onNotificationRemoved(guidance);
        assertNull(NavigationNotifications.current());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertTrue("Still within the window", NavigationNotifications.guidedWithin(5000));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertFalse("Guidance that ended a while ago", NavigationNotifications.guidedWithin(5000));
    }

    /**
     * With Maps' notifications turned off in Android's settings, Android hands a listener none of
     * them: no turn card, no arrival, no resumed trip. The Maps bridge module, inside Maps, hands
     * Wend the same notification itself.
     */
    @Test public void guidanceMapsReportsItselfNeedsNoNotification() {
        NavigationNotifications.reportedByMaps(guidance("150 ft", "1340 N State St", "0 min · 150 ft · 5:57 AM PST ETA").getNotification());
        assertEquals("150 ft", NavigationNotifications.current().distance.toString());
        assertTrue(NavigationNotifications.guidedWithin(0));
        NavigationNotifications.reportedByMaps(guidance("", "1340 N State St", "0 min · 100 ft · 5:57 AM PST ETA").getNotification());
        assertTrue("Arrival is told the same way", NavigationNotifications.current().arrived);
        NavigationNotifications.reportedByMaps(null);
        assertNull("Maps said its guidance ended", NavigationNotifications.current());
    }

    /** Where Android delivers the notification too, each step would otherwise arrive twice. */
    @Test public void androidsCopyIsLeftAloneOnceMapsReportsItself() {
        NavigationNotifications.reportedByMaps(guidance("0.4 mi", "US-101 S", "8 min · 7.3 mi · 6:03 AM PST ETA").getNotification());
        StatusBarNotification stale = guidance("0.5 mi", "US-101 S", "8 min · 7.3 mi · 6:03 AM PST ETA");
        notifications.onNotificationPosted(stale);
        assertEquals("0.4 mi", NavigationNotifications.current().distance.toString());
        notifications.onNotificationRemoved(stale);
        notifications.onListenerDisconnected();
        assertNotNull("Maps still guides", NavigationNotifications.current());
    }

    /** A dead Maps reports nothing more; Android would have removed its notification. */
    @Test public void guidanceMapsReportedEndsWithItsProcess() {
        NavigationNotifications.reportedByMaps(guidance().getNotification());
        NavigationNotifications.mapsGone();
        assertNull(NavigationNotifications.current());
        assertTrue("It guided until it died", NavigationNotifications.guidedWithin(5000));
        // Guidance Android reports ends when Android says so.
        NavigationNotifications.forget();
        notifications.onNotificationPosted(guidance());
        NavigationNotifications.mapsGone();
        assertNotNull(NavigationNotifications.current());
    }

    /**
     * Android can disconnect and reconnect the listener (notification access turned off and on).
     * Heard again at the destination, "the place, no distance" alone does not tell arrival; what
     * was heard before the cut does.
     */
    @Test public void aListenerReconnectedAtTheDestinationStillKnowsItArrived() {
        StatusBarNotification arrival = guidance("", "1340 N State St", "0 min · 100 ft · 5:57 AM PST ETA");
        notifications.onNotificationPosted(guidance("150 ft", "1340 N State St", "0 min · 150 ft · 5:57 AM PST ETA"));
        notifications.onNotificationPosted(arrival);
        notifications.onListenerDisconnected();
        assertNull(NavigationNotifications.current());
        shadowOf(notifications).addActiveNotification(arrival);
        notifications.onListenerConnected();
        assertTrue(NavigationNotifications.current().arrived);
    }

    /** Only that arrival: not another trip's first seconds, when Maps also posts a road without a distance. */
    @Test public void whatWasHeardBeforeTheCutCountsOnlyForTheSamePlace() {
        notifications.onNotificationPosted(guidance("150 ft", "1340 N State St", "0 min · 150 ft · 5:57 AM PST ETA"));
        notifications.onNotificationPosted(guidance("", "1340 N State St", "0 min · 100 ft · 5:57 AM PST ETA"));
        notifications.onListenerDisconnected();
        shadowOf(notifications).addActiveNotification(guidance("", "CA-20 W", "9 min · 8.4 mi · 6:02 AM PST ETA"));
        notifications.onListenerConnected();
        assertFalse(NavigationNotifications.current().arrived);
    }

    @Test public void mapsArrivesWhenItsLastTurnLosesItsDistance() {
        notifications.onNotificationPosted(guidance("150 ft", "1340 N State St", "0 min · 150 ft · 5:57 AM PST ETA"));
        assertFalse(NavigationNotifications.current().arrived);
        notifications.onNotificationPosted(guidance("", "1340 N State St", "0 min · 100 ft · 5:57 AM PST ETA"));
        assertTrue(NavigationNotifications.current().arrived);
    }
}
