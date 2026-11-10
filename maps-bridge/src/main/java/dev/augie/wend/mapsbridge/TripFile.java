package dev.augie.wend.mapsbridge;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads the destination out of the directions Google Maps saves in its own files
 * ({@code saved_directions.data.cs}, {@code directions_as_changed_in_navigation.data.cs}): an
 * 8-byte header, then a protocol buffer. As Maps 26.43 writes it, message 1 holds message 1, which
 * holds one message 1 per waypoint, the origin first; a waypoint has its name and address in
 * string 1 and, in message 3, its latitude and longitude as doubles 3 and 4.
 */
final class TripFile {
    private static final int HEADER = 8;

    private TripFile() { }

    /** @return the last waypoint after the origin, or null if {@code file} holds no such trip */
    static Trip destination(byte[] file) {
        try {
            Fields directions = Fields.of(file, HEADER, file.length).message(1);
            Fields route = directions == null ? null : directions.message(1);
            if (route == null) return null;
            Fields last = null;
            int waypoints = 0;
            while (route.next()) {
                if (route.field != 1 || route.wire != 2) continue;
                last = route.inside();
                waypoints++;
            }
            return waypoints < 2 ? null : trip(last);
        } catch (IndexOutOfBoundsException | IllegalArgumentException cut) {
            // Not this format, or read while Maps was still writing it.
            return null;
        }
    }

    private static Trip trip(Fields waypoint) {
        String name = "";
        Double latitude = null, longitude = null;
        while (waypoint.next()) {
            if (waypoint.field == 1 && waypoint.wire == 2) name = waypoint.text();
            else if (waypoint.field == 3 && waypoint.wire == 2) {
                Fields place = waypoint.inside();
                while (place.next()) {
                    if (place.field == 3 && place.wire == 1) latitude = place.real();
                    else if (place.field == 4 && place.wire == 1) longitude = place.real();
                }
            }
        }
        boolean placed = latitude != null && longitude != null && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
        if (latitude != null && !placed) return null;
        if (name.trim().isEmpty()) {
            if (!placed) return null;
            name = String.format(Locale.US, "%.6f,%.6f", latitude, longitude);
        }
        return new Trip(name, placed ? latitude : Double.NaN, placed ? longitude : Double.NaN);
    }

    /** A cursor over the fields of one protocol buffer message. */
    private static final class Fields {
        private final byte[] data;
        private final int end;
        private int at;
        int field, wire;
        /** The current field's value, or for a length-delimited one where it starts and its length. */
        private long value;
        private int start, length;

        private Fields(byte[] data, int start, int end) {
            if (start < 0 || end > data.length || start > end) throw new IndexOutOfBoundsException();
            this.data = data;
            this.at = start;
            this.end = end;
        }

        static Fields of(byte[] data, int start, int end) { return new Fields(data, start, end); }

        boolean next() {
            if (at >= end) return false;
            long key = varint();
            field = (int) (key >>> 3);
            wire = (int) (key & 7);
            switch (wire) {
                case 0: value = varint(); break;
                case 1: value = fixed(8); break;
                case 5: value = fixed(4); break;
                case 2:
                    long size = varint();
                    if (size < 0 || size > end - at) throw new IndexOutOfBoundsException();
                    start = at;
                    length = (int) size;
                    at += length;
                    break;
                default: throw new IllegalArgumentException("wire type " + wire);
            }
            return true;
        }

        /** The first length-delimited field numbered {@code number}, as a message, or null. */
        Fields message(int number) {
            while (next()) if (field == number && wire == 2) return inside();
            return null;
        }

        Fields inside() { return new Fields(data, start, start + length); }
        String text() { return new String(data, start, length, StandardCharsets.UTF_8); }
        double real() { return Double.longBitsToDouble(value); }

        private long varint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at >= end) throw new IndexOutOfBoundsException();
                byte b = data[at++];
                result |= (long) (b & 0x7f) << shift;
                if (b >= 0) return result;
            }
            throw new IllegalArgumentException("varint too long");
        }

        private long fixed(int bytes) {
            if (bytes > end - at) throw new IndexOutOfBoundsException();
            long result = 0;
            for (int i = 0; i < bytes; i++) result |= (long) (data[at + i] & 0xff) << 8 * i;
            at += bytes;
            return result;
        }
    }
}
