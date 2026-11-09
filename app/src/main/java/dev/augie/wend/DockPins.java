package dev.augie.wend;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The apps pinned to the dock: an ordered list of distinct, valid package names that never
 * holds more than the dock's capacity. Immutable; every change returns a new value. A pin
 * whose app is not installed stays stored, so reinstalling the app restores it in place.
 */
final class DockPins {
    static final String MUSIC = BuildConfig.MUSIC_PACKAGE;
    private static final String SEPARATOR = ",";
    // Android package names: two or more dot-separated segments, each starting with a letter.
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private final List<String> packages;
    private final int capacity;

    private DockPins(List<String> packages, int capacity) {
        this.packages = Collections.unmodifiableList(packages);
        this.capacity = capacity;
    }

    /** Google Maps, then the music app: the dock Wend shipped with. */
    static DockPins defaults(int capacity) {
        return from(Arrays.asList(BuildConfig.MAPS_PACKAGE, MUSIC), capacity);
    }

    /**
     * Reads a stored value. Null (never saved) gives the defaults; an empty string is a dock
     * the user emptied. Invalid names, duplicates and pins beyond the capacity are dropped.
     */
    static DockPins parse(String stored, int capacity) {
        if (stored == null) return defaults(capacity);
        return from(Arrays.asList(stored.split(SEPARATOR, -1)), capacity);
    }

    static boolean isPackageName(String value) { return PACKAGE.matcher(value).matches(); }

    private static DockPins from(List<String> candidates, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Dock capacity must not be negative: " + capacity);
        List<String> packages = new ArrayList<>();
        for (String candidate : candidates) {
            String name = candidate.trim();
            if (packages.size() == capacity) break;
            if (isPackageName(name) && !packages.contains(name)) packages.add(name);
        }
        return new DockPins(packages, capacity);
    }

    String serialize() { return String.join(SEPARATOR, packages); }

    /** In dock order, top to bottom; unmodifiable. */
    List<String> packages() { return packages; }
    int capacity() { return capacity; }
    int size() { return packages.size(); }
    boolean isFull() { return packages.size() >= capacity; }
    boolean contains(String packageName) { return packages.contains(packageName); }

    boolean canAdd(String packageName) {
        return isPackageName(packageName) && !contains(packageName) && !isFull();
    }

    /** Appends an app to the bottom of the pins. Pinning an app twice changes nothing. */
    DockPins add(String packageName) {
        if (!isPackageName(packageName)) {
            throw new IllegalArgumentException("Cannot pin \"" + packageName + "\": it is not an Android package name");
        }
        if (contains(packageName)) return this;
        if (isFull()) {
            throw new IllegalStateException("Cannot pin " + packageName + ": the dock already holds its "
                    + capacity + " apps. Remove one first.");
        }
        List<String> next = new ArrayList<>(packages);
        next.add(packageName);
        return new DockPins(next, capacity);
    }

    DockPins remove(String packageName) {
        if (!contains(packageName)) return this;
        List<String> next = new ArrayList<>(packages);
        next.remove(packageName);
        return new DockPins(next, capacity);
    }

    /** Moves the pin at {@code from} to index {@code to}, shifting the pins between them. */
    DockPins move(int from, int to) {
        if (from < 0 || from >= packages.size() || to < 0 || to >= packages.size()) {
            throw new IndexOutOfBoundsException("Cannot move dock pin " + from + " to " + to
                    + "; the dock holds " + packages.size() + " pins");
        }
        if (from == to) return this;
        List<String> next = new ArrayList<>(packages);
        next.add(to, next.remove(from));
        return new DockPins(next, capacity);
    }

    @Override public String toString() { return "DockPins" + packages + " of " + capacity; }
}
