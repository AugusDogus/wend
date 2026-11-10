package dev.augie.wend.mapsbridge;

/** Where Google Maps is asked to go. */
final class Trip {
    /** What to ask Maps for to get there again: the place's name and address, or its coordinates. */
    final String destination;
    final double latitude, longitude;

    Trip(String destination, double latitude, double longitude) {
        this.destination = destination;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
