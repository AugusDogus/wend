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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NavigationNotificationsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final NavigationNotifications notifications = new NavigationNotifications();

    @After public void disconnect() { notifications.onListenerDisconnected(); }

    private StatusBarNotification guidance() {
        Notification notification = new Notification.Builder(context, "1_foreground_1")
                .setSmallIcon(Icon.createWithBitmap(Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)))
                .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true)
                .setContentTitle("0 ft").setContentText("CA-20 W").build();
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
}
