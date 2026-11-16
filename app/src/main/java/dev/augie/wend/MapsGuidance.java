package dev.augie.wend;

import android.os.Bundle;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Maps' guidance as the Maps bridge module reports it from Maps' own navigation state
 * ({@link MapsTrips}), and its next turn ({@link NavigationCard}) for a floating Maps window and
 * for agents. No notification is involved: without the module, Wend hears no guidance. Main thread,
 * where its listeners are called too.
 */
final class MapsGuidance {
    interface Listener {
        /** @param card the next turn, or null while Maps guides nowhere */
        void changed(NavigationCard card);
    }

    private static final List<Listener> listeners = new ArrayList<>();
    private static NavigationCard current;
    /** When guidance was last seen to end, in uptime ms, or null if it never was. */
    private static Long endedAt;
    /** When guidance last began, in uptime ms, or null if it never did. */
    private static Long beganAt;
    /** The shields Maps' own card last showed, for the turn whose road they name; null for none. */
    private static RoadShields shields;

    private MapsGuidance() { }

    /** @return the next turn, or null while Maps guides nowhere */
    static NavigationCard current() { return current; }

    /** @return whether Maps guides now or stopped within the last {@code ms} */
    static boolean guidedWithin(long ms) {
        return current != null || endedAt != null && SystemClock.uptimeMillis() - endedAt <= ms;
    }

    /** @return when the guidance that runs now, or ran last, began, in uptime ms; null if none ever did */
    static Long began() { return beganAt; }

    /** Maps' navigation state changed, as the Maps bridge module reports it; null says nothing. */
    static void reportedByMaps(Bundle report) {
        if (report == null) return;
        publish(NavigationCard.from(report));
    }

    /**
     * The Maps bridge module rendered the shields Maps' own card draws in the next turn's road
     * ({@link RoadShields}), or null once that card shows none. They join the turn whose road they
     * name, now or when it arrives.
     */
    static void shieldsReportedByMaps(RoadShields reported) {
        shields = reported;
        if (current != null && current.withShields(shields) != current) publish(current);
    }

    /** Maps' process is gone, and its guidance with it. */
    static void mapsGone() {
        if (current != null) publish(null);
    }

    /** As in a new process: no guidance, and no word from Maps yet. */
    static void forget() {
        shields = null;
        current = null;
        endedAt = null;
        beganAt = null;
    }

    static void listen(Listener listener) { listeners.add(listener); }

    static void stop(Listener listener) { listeners.remove(listener); }

    private static void publish(NavigationCard card) {
        // Shields of guidance that ended are no turn's of the next.
        if (card == null && current != null) shields = null;
        if (card != null) card = card.withShields(shields);
        if (current != null && card == null) endedAt = SystemClock.uptimeMillis();
        if (current == null && card != null) beganAt = SystemClock.uptimeMillis();
        current = card;
        for (Listener listener : new ArrayList<>(listeners)) listener.changed(card);
    }
}
