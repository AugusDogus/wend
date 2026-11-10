package dev.augie.wend;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Hears Google Maps' guidance notification and keeps its next turn ({@link NavigationCard}) for a
 * floating Maps window. Android calls it on Wend's main thread, where its listeners are called too.
 * Wend allows itself to bind it ({@link #allow}); as the system user it may.
 */
public final class NavigationNotifications extends NotificationListenerService {
    interface Listener {
        /** @param card the next turn, or null while Maps shows no guidance or this cannot hear it */
        void changed(NavigationCard card);
    }

    private static final String TAG = "WendNav";
    private static final List<Listener> listeners = new ArrayList<>();
    private static NavigationCard current;
    /** When guidance was last seen to end, in uptime ms, or null if it never was. */
    private static Long endedAt;
    /** When guidance last began, in uptime ms, or null if it never did. */
    private static Long beganAt;
    /** The key of guidance Maps reports itself, where Android's notifications have Android's key. */
    private static final String FROM_MAPS = "maps-bridge";
    /** Whether Maps reports its guidance itself. Android's copy of it, if it delivers one, is then left alone. */
    private static boolean reportedByMaps;

    /** @return the next turn, or null */
    static NavigationCard current() { return current; }

    /**
     * @return whether Maps guides now or stopped within the last {@code ms}. Android removes the
     *     notification of a Maps whose process died about when it destroys its task, in either order.
     */
    static boolean guidedWithin(long ms) {
        return current != null || endedAt != null && SystemClock.uptimeMillis() - endedAt <= ms;
    }

    /** @return when the guidance that runs now, or ran last, began, in uptime ms; null if none ever did */
    static Long began() { return beganAt; }

    /**
     * Maps handed over its guidance notification itself, through the Maps bridge module
     * ({@link MapsTrips}), or null once its guidance ended. Android hands a listener nothing while
     * Maps' notifications are turned off; this needs none of Android's. Main thread.
     */
    static void reportedByMaps(Notification guidance) {
        reportedByMaps = true;
        boolean reported = current != null && FROM_MAPS.equals(current.key);
        NavigationCard card = guidance == null ? null
                : NavigationCard.from(EmbeddedMaps.PACKAGE, FROM_MAPS, guidance, EmbeddedMaps.PACKAGE, current);
        // The first report takes over from Android's copy; an end ends only what Maps reported.
        if (card != null || reported) publish(card);
    }

    /** Maps' process is gone. Guidance it reported itself ended with it, as Android removes a dead app's notification. */
    static void mapsGone() {
        if (current != null && FROM_MAPS.equals(current.key)) publish(null);
    }

    /** As in a new process: no guidance, and no word from Maps yet. */
    static void forget() {
        reportedByMaps = false;
        current = null;
        endedAt = null;
        beganAt = null;
    }

    static void listen(Listener listener) { listeners.add(listener); }

    static void stop(Listener listener) { listeners.remove(listener); }

    /** Allows this listener to bind, if it may not yet. Without it, floating Maps shows no next turn. */
    static void allow(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        ComponentName listener = new ComponentName(context, NavigationNotifications.class);
        if (notifications.isNotificationListenerAccessGranted(listener)) return;
        try {
            // A system API (MANAGE_NOTIFICATION_LISTENERS), not in the public SDK.
            NotificationManager.class.getMethod("setNotificationListenerAccessGranted", ComponentName.class, boolean.class)
                    .invoke(notifications, listener, true);
            Log.i(TAG, "Allowed Wend to read Maps' guidance notification for floating Maps' next turn");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "Floating Maps will show no next turn: Wend could not allow itself to read Maps'"
                    + " guidance notification. Maps still floats and navigates; to show the turn, allow"
                    + " Wend's notification access in Android's settings", error);
        }
    }

    @Override public void onListenerConnected() {
        if (reportedByMaps) return;
        NavigationCard found = null;
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification notification : active) {
                NavigationCard card = cardOf(notification);
                if (card != null) found = card;
            }
        }
        publish(found);
    }

    @Override public void onListenerDisconnected() { if (!reportedByMaps) publish(null); }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        if (reportedByMaps) return;
        NavigationCard card = cardOf(posted);
        if (card != null) publish(card);
        // The same notification, no longer guidance.
        else if (current != null && current.key.equals(posted.getKey())) publish(null);
    }

    @Override public void onNotificationRemoved(StatusBarNotification removed) {
        if (!reportedByMaps && current != null && current.key.equals(removed.getKey())) publish(null);
    }

    private static NavigationCard cardOf(StatusBarNotification notification) {
        return NavigationCard.from(notification.getPackageName(), notification.getKey(),
                notification.getNotification(), EmbeddedMaps.PACKAGE, current);
    }

    private static void publish(NavigationCard card) {
        if (current != null && card == null) endedAt = SystemClock.uptimeMillis();
        if (current == null && card != null) beganAt = SystemClock.uptimeMillis();
        current = card;
        for (Listener listener : new ArrayList<>(listeners)) listener.changed(card);
    }
}
