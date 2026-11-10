package dev.augie.wend;

import android.app.Notification;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.TextUtils;

/**
 * The next turn as Google Maps posts it in its ongoing guidance notification: the turn arrow, the
 * distance to it, the road, and the trip's time, distance and ETA. A floating Maps window draws it
 * along its bottom, as Maps' own PiP card does, because the window crops Maps' panel layout to the
 * map and so leaves out Maps' next-turn card (see {@link FloatingMapCrop}). At the destination it
 * fills the window instead, as Maps' own PiP does, where Maps covers its map with a sheet.
 */
final class NavigationCard {
    /** Maps' own PiP card takes the bottom 26.5% of its window. */
    static final float BAND = .265f;

    /** The notification's key, to tell when that notification goes. */
    final String key;
    /** The turn arrow, or null if Maps posted none. */
    final Icon arrow;
    /** Each may be empty, but not both of {@link #distance} and {@link #road}. */
    final CharSequence distance, road, eta;
    /** Maps' guidance color, the card's background. */
    final int color;
    /**
     * Whether Maps has reached the destination. Maps posts no arrival, only the place without a
     * distance, as it also posts a road for a second or two mid-route; it is arriving when it
     * follows the last turn. A notification first heard at the destination does not say.
     */
    final boolean arrived;

    private NavigationCard(String key, Icon arrow, CharSequence distance, CharSequence road, CharSequence eta, int color,
            boolean arrived) {
        this.key = key;
        this.arrow = arrow;
        this.distance = distance;
        this.road = road;
        this.eta = eta;
        this.color = color;
        this.arrived = arrived;
    }

    /**
     * @param packageName the app that posted {@code notification}
     * @param previous what this returned for the guidance notification heard before, or null
     * @return the next turn, or null unless {@code notification} is the guidance notification of
     *     {@code mapsPackage} with a distance or a road to show
     */
    static NavigationCard from(String packageName, String key, Notification notification, String mapsPackage,
            NavigationCard previous) {
        if (!mapsPackage.equals(packageName) || !Notification.CATEGORY_NAVIGATION.equals(notification.category)) return null;
        Bundle extras = notification.extras;
        CharSequence distance = text(extras, Notification.EXTRA_TITLE);
        CharSequence road = text(extras, Notification.EXTRA_TEXT);
        if (distance.length() == 0 && road.length() == 0) return null;
        Icon arrow = notification.getLargeIcon();
        // "Rerouting..." has no distance either, and no arrow.
        boolean arrived = distance.length() == 0 && arrow != null && previous != null && previous.key.equals(key)
                && (previous.arrived || previous.lastTurn());
        return new NavigationCard(key, arrow, distance, road, text(extras, Notification.EXTRA_SUB_TEXT),
                notification.color, arrived);
    }

    /**
     * @return whether this turn is the destination: the distance to it is all that is left of the
     *     trip ("350 ft", of "0 min · 350 ft · 5:53 AM PST ETA")
     */
    private boolean lastTurn() {
        if (distance.length() == 0) return false;
        for (String part : eta.toString().split("·")) if (part.trim().contentEquals(distance)) return true;
        return false;
    }

    private static CharSequence text(Bundle extras, String name) {
        CharSequence value = extras.getCharSequence(name);
        return TextUtils.isEmpty(value) ? "" : value;
    }

    /** A name too long for the card shrinks to no less than this share of its size before it is cut. */
    private static final float SMALLEST_TEXT = 2 / 3f;

    /**
     * @param size the text size a name is drawn at when it fits
     * @param width how wide the name is at that size
     * @param room how wide it may be
     * @return the size to draw it at: smaller if it is too wide, down to two thirds; the rest is cut
     */
    static float fittedTextSize(float size, float width, float room) {
        if (room <= 0 || width <= room) return size;
        return size * Math.max(SMALLEST_TEXT, room / width);
    }

    /** Where the card sits in a floating window at {@code window}: across its bottom, scaled with it. */
    static Rect band(Rect window) {
        int top = window.bottom - Math.round(window.height() * BAND);
        return new Rect(window.left, top, window.right, window.bottom);
    }
}
