package dev.augie.wend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The apps Wend opened most recently, newest first: distinct, valid package names in Wend's
 * own profile, at most one drawer row of them. Immutable; every change returns a new value.
 */
final class RecentApps {
    static final int CAPACITY = AppBrowser.COLUMNS;
    private static final String SEPARATOR = ",";
    private static final RecentApps EMPTY = new RecentApps(Collections.<String>emptyList());

    private final List<String> packages;

    private RecentApps(List<String> packages) { this.packages = Collections.unmodifiableList(packages); }

    static RecentApps empty() { return EMPTY; }

    /** Reads a stored value; null is no history. Invalid names, repeats and extras are dropped. */
    static RecentApps parse(String stored) {
        if (stored == null || stored.isEmpty()) return EMPTY;
        List<String> packages = new ArrayList<>();
        for (String candidate : stored.split(SEPARATOR, -1)) {
            String name = candidate.trim();
            if (packages.size() == CAPACITY) break;
            if (DockPins.isPackageName(name) && !packages.contains(name)) packages.add(name);
        }
        return new RecentApps(packages);
    }

    String serialize() { return String.join(SEPARATOR, packages); }

    /** Newest first; unmodifiable. */
    List<String> packages() { return packages; }

    /** Moves {@code packageName} to the front, adding it if new and dropping the oldest past the capacity. */
    RecentApps launched(String packageName) {
        if (!DockPins.isPackageName(packageName)) {
            throw new IllegalArgumentException("Cannot record a launch of \"" + packageName
                    + "\": it is not an Android package name");
        }
        if (!packages.isEmpty() && packages.get(0).equals(packageName)) return this;
        List<String> next = new ArrayList<>(CAPACITY);
        next.add(packageName);
        for (String existing : packages) {
            if (next.size() == CAPACITY) break;
            if (!existing.equals(packageName)) next.add(existing);
        }
        return new RecentApps(next);
    }

    /** Keeps only the apps {@code installed} accepts, in order; for example, drops uninstalled apps. */
    RecentApps retain(Predicate<String> installed) {
        List<String> next = new ArrayList<>(packages.size());
        for (String packageName : packages) if (installed.test(packageName)) next.add(packageName);
        return next.size() == packages.size() ? this : new RecentApps(next);
    }

    @Override public boolean equals(Object other) {
        return other instanceof RecentApps && packages.equals(((RecentApps) other).packages);
    }

    @Override public int hashCode() { return packages.hashCode(); }
    @Override public String toString() { return "RecentApps" + packages; }
}
