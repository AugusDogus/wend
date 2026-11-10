package dev.augie.wend.mapsbridge;

import java.util.List;

/** Where Google Maps is asked to go. */
final class Trip {
    /** What to ask Maps for to get there again: the place's name and address, or its coordinates. */
    final String destination;
    final double latitude, longitude;
    /** The stops still ahead on the way there, in order, each as {@link #destination} is. */
    final List<String> stops;

    Trip(String destination, double latitude, double longitude, List<String> stops) {
        this.destination = destination;
        this.latitude = latitude;
        this.longitude = longitude;
        this.stops = stops;
    }
}
