package dev.augie.wend;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.TextUtils;
import java.text.DateFormat;
import java.util.Date;

/**
 * The next turn as Google Maps' own navigation state holds it, as the Maps bridge module reports
 * it ({@link MapsGuidance}): Maps' arrow for it, the distance to it and its road as Maps words them,
 * the time and distance left and the arrival time, and the counts behind them. A floating Maps
 * window draws it along its bottom, as Maps' own PiP card does, because the window crops Maps'
 * panel layout to the map and so leaves out Maps' next-turn card (see {@link FloatingMapCrop}). At
 * the destination it fills the window instead, as Maps' own PiP does, where Maps covers its map
 * with a sheet.
 */
final class NavigationCard {
    /** Maps' own PiP card takes the bottom 26.5% of its window. */
    static final float BAND = .265f;
    /** Maps' guidance color, for a report without one. */
    private static final int MAPS_TEAL = 0xff015f61;

    /** The turn arrow, or null while Maps draws none, as when it reroutes. */
    final Icon arrow;
    /**
     * The distance to the turn, empty while Maps does not know it; its road, or what Maps says
     * instead ("Rerouting..."); and the time and distance left with the arrival time ("10 min ·
     * 8.4 mi · 4:37 PM"), empty when Maps knows none of them.
     */
    final CharSequence distance, road, eta;
    /** Maps' guidance color, the card's background. */
    final int color;
    /** Whether Maps has finished the route, at its destination. */
    final boolean arrived;
    /** Whether the car is on Maps' route. */
    final boolean onRoute;
    /** Meters and seconds to the turn, and to the next destination; each -1 when Maps does not know it. */
    final int metersToTurn, secondsToTurn, metersLeft, secondsLeft;
    /** When the trip arrives at its next destination ("4:37 PM"), or empty if Maps does not know. */
    final String arrival;
    /** The shields Maps' own card draws in {@link #road}, or null if none were reported for it. */
    final RoadShields shields;

    private NavigationCard(Icon arrow, CharSequence distance, CharSequence road, CharSequence eta, int color, boolean arrived,
            boolean onRoute, int metersToTurn, int secondsToTurn, int metersLeft, int secondsLeft, String arrival,
            RoadShields shields) {
        this.arrow = arrow;
        this.distance = distance;
        this.road = road;
        this.eta = eta;
        this.color = color;
        this.arrived = arrived;
        this.onRoute = onRoute;
        this.metersToTurn = metersToTurn;
        this.secondsToTurn = secondsToTurn;
        this.metersLeft = metersLeft;
        this.secondsLeft = secondsLeft;
        this.arrival = arrival;
        this.shields = shields;
    }

    /** @return this turn with {@code reported} if they are its road's shields, or without any */
    NavigationCard withShields(RoadShields reported) {
        RoadShields own = reported != null && reported.names(road) ? reported : null;
        if (own == shields) return this;
        return new NavigationCard(arrow, distance, road, eta, color, arrived, onRoute, metersToTurn, secondsToTurn, metersLeft,
                secondsLeft, arrival, own);
    }

    /**
     * @param report Maps' navigation state as the Maps bridge module reports it: {@code state},
     *     and while it is {@code GUIDING} the step ahead and the trip's progress
     * @return the next turn, or null unless Maps guides
     */
    static NavigationCard from(Bundle report) {
        if (!"GUIDING".equals(report.getString("state"))) return null;
        Bitmap drawn = report.getParcelable("arrow");
        int secondsLeft = report.getInt("seconds_remaining", -1);
        String arrival = "";
        if (secondsLeft >= 0) {
            // In the route's time zone, as Maps words its arrival.
            DateFormat clock = DateFormat.getTimeInstance(DateFormat.SHORT);
            String zone = report.getString("time_zone");
            if (zone != null) clock.setTimeZone(java.util.TimeZone.getTimeZone(zone));
            arrival = clock.format(new Date(System.currentTimeMillis() + secondsLeft * 1000L));
        }
        StringBuilder eta = new StringBuilder();
        for (CharSequence part : new CharSequence[]{text(report, "time_left"), text(report, "distance_left"), arrival}) {
            if (part.length() == 0) continue;
            if (eta.length() > 0) eta.append(" · ");
            eta.append(part);
        }
        return new NavigationCard(drawn == null ? null : Icon.createWithBitmap(drawn), text(report, "distance"), text(report, "cue"),
                eta.toString(), report.getInt("color", MAPS_TEAL), report.getBoolean("done"), report.getBoolean("on_route"),
                report.getInt("meters_to_step", -1), report.getInt("seconds_to_step", -1), report.getInt("meters_remaining", -1),
                secondsLeft, arrival, null);
    }

    /** @return when the trip arrives at its next destination, as Maps' own PiP shows it under the road ("4:37 PM") */
    String arrival() { return arrival; }

    private static CharSequence text(Bundle report, String name) {
        CharSequence value = report.getCharSequence(name);
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
