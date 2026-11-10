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
 * map and so leaves out Maps' next-turn card (see {@link FloatingMapCrop}).
 */
final class NavigationCard {
    /** Maps' own PiP card takes the bottom 26.5% of its window. */
    private static final float BAND = .265f;

    /** The notification's key, to tell when that notification goes. */
    final String key;
    /** The turn arrow, or null if Maps posted none. */
    final Icon arrow;
    /** Each may be empty, but not both of {@link #distance} and {@link #road}. */
    final CharSequence distance, road, eta;
    /** Maps' guidance color, the card's background. */
    final int color;

    private NavigationCard(String key, Icon arrow, CharSequence distance, CharSequence road, CharSequence eta, int color) {
        this.key = key;
        this.arrow = arrow;
        this.distance = distance;
        this.road = road;
        this.eta = eta;
        this.color = color;
    }

    /**
     * @param packageName the app that posted {@code notification}
     * @return the next turn, or null unless {@code notification} is the guidance notification of
     *     {@code mapsPackage} with a distance or a road to show
     */
    static NavigationCard from(String packageName, String key, Notification notification, String mapsPackage) {
        if (!mapsPackage.equals(packageName) || !Notification.CATEGORY_NAVIGATION.equals(notification.category)) return null;
        Bundle extras = notification.extras;
        CharSequence distance = text(extras, Notification.EXTRA_TITLE);
        CharSequence road = text(extras, Notification.EXTRA_TEXT);
        if (distance.length() == 0 && road.length() == 0) return null;
        return new NavigationCard(key, notification.getLargeIcon(), distance, road,
                text(extras, Notification.EXTRA_SUB_TEXT), notification.color);
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
