package dev.augie.wend.mapsbridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Files shaped as Google Maps 26.43 wrote {@code files/saved_directions.data.cs} on the emulator
 * (read with {@code protoc --decode_raw} past its 8-byte header): message 1, in it message 1, in
 * it one message 1 per waypoint, the origin first.
 */
public class TripFileTest {
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7fL) != 0) { out.write((int) (value & 0x7f | 0x80)); value >>>= 7; }
        out.write((int) value);
        return out.toByteArray();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] bytes(int field, byte[] value) { return join(varint(field << 3 | 2), varint(value.length), value); }
    private static byte[] text(int field, String value) { return bytes(field, value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] number(int field, long value) { return join(varint(field << 3), varint(value)); }

    private static byte[] real(int field, double value) {
        byte[] out = new byte[9];
        out[0] = (byte) (field << 3 | 1);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) out[1 + i] = (byte) (bits >>> 8 * i);
        return out;
    }

    private static byte[] place(double latitude, double longitude) { return bytes(3, join(real(3, latitude), real(4, longitude))); }

    private static byte[] file(byte[]... waypoints) {
        byte[][] wrapped = new byte[waypoints.length + 1][];
        for (int i = 0; i < waypoints.length; i++) wrapped[i] = bytes(1, waypoints[i]);
        // Maps writes its route options after the waypoints.
        wrapped[waypoints.length] = bytes(6, number(5, 1));
        return join(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}, bytes(1, bytes(1, join(wrapped))), bytes(2, text(1, "11 min")));
    }

    private static final byte[] HERE = join(place(39.237255, -123.150032), number(5, 0), number(6, 4));
    private static final byte[] MOTEL = join(text(1, "Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482"),
            text(2, "0x808164d83d8e798d:0xb9d3ca51d074c5ac"), place(39.168705, -123.212274));

    @Test public void theDestinationIsTheLastWaypoint() {
        Trip trip = TripFile.destination(file(HERE, MOTEL));
        assertEquals("Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482", trip.destination);
        assertEquals(39.168705, trip.latitude, 1e-9);
        assertEquals(-123.212274, trip.longitude, 1e-9);
        assertEquals(Collections.emptyList(), trip.stops);
        // "directions_as_changed_in_navigation" leaves the origin's place out.
        assertEquals(39.168705, TripFile.destination(file(join(number(5, 0), number(6, 4)), MOTEL)).latitude, 1e-9);
    }

    /**
     * A stop added on the way is a waypoint between the origin and the destination. Once Maps
     * goes on from a stop it saves that stop as the origin, so the waypoints after the first are
     * always what is left of the trip.
     */
    @Test public void theWaypointsBetweenAreTheStopsStillAhead() {
        byte[] chevron = join(text(1, "Chevron, 50 W Lake Mendocino Dr, Ukiah, CA 95482"),
                text(2, "0x80817b25dc6ccf55:0x38140700890c4e40"), place(39.191483, -123.208007));
        Trip ahead = TripFile.destination(file(HERE, chevron, MOTEL));
        assertEquals("Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482", ahead.destination);
        assertEquals(Collections.singletonList("Chevron, 50 W Lake Mendocino Dr, Ukiah, CA 95482"), ahead.stops);
        Trip passed = TripFile.destination(file(chevron, MOTEL));
        assertEquals("Motel 6 Ukiah, CA - North, 1340 N State St, Ukiah, CA 95482", passed.destination);
        assertEquals("After \"Next stop\" the Chevron is where the trip starts", Collections.emptyList(), passed.stops);
        // A stop without a name is its coordinates, like a destination; one with neither spoils the trip.
        assertEquals(Collections.singletonList("39.200000,-123.200000"), TripFile.destination(file(HERE, place(39.2, -123.2), MOTEL)).stops);
        assertNull(TripFile.destination(file(HERE, number(5, 0), MOTEL)));
    }

    /** A dropped pin has no name: Maps can still be sent to its coordinates. */
    @Test public void aDestinationWithoutANameIsItsCoordinates() {
        assertEquals("39.168705,-123.212274", TripFile.destination(file(HERE, place(39.168705, -123.212274))).destination);
    }

    @Test public void whatIsNotATripIsNoDestination() {
        assertNull("An origin alone", TripFile.destination(file(HERE)));
        assertNull("No waypoints", TripFile.destination(file()));
        assertNull("A destination with neither name nor place", TripFile.destination(file(HERE, number(5, 0))));
        assertNull("Coordinates off the globe", TripFile.destination(file(HERE, place(139.1, -123.2))));
        assertNull("Too short for the header", TripFile.destination(new byte[]{0, 0, 1}));
        assertNull("Not this format", TripFile.destination("<?xml version='1.0'?><map></map>".getBytes(StandardCharsets.UTF_8)));
        byte[] cut = file(HERE, MOTEL);
        assertNull("Cut off mid-write", TripFile.destination(java.util.Arrays.copyOf(cut, 40)));
    }
}
