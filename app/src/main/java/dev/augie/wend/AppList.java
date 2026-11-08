package dev.augie.wend;

import android.os.UserHandle;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wend's equivalent of Launcher3's AllAppsList: one entry per package and profile,
 * excluding the launcher itself. A package keeps its first launcher activity, which is the
 * one Android's launch intent opens. Not thread-safe; AppCatalog confines it to one thread.
 */
final class AppList {
    private final String ownPackage;
    private final Map<LauncherApp.Key, LauncherApp> apps = new HashMap<>();

    AppList(String ownPackage) { this.ownPackage = ownPackage; }

    /** Replaces every entry, after the initial load or a full reload. */
    void replaceAll(List<LauncherApp> activities) {
        apps.clear();
        for (LauncherApp activity : activities) add(activity);
    }

    /**
     * Applies an added, changed, or newly available package. {@code activities} are the
     * package's current launcher activities in {@code user}; an empty list removes it.
     */
    void replacePackage(String packageName, UserHandle user, List<LauncherApp> activities) {
        LauncherApp.Key key = new LauncherApp.Key(packageName, user);
        for (LauncherApp activity : activities) {
            if (!activity.key.equals(key)) {
                throw new IllegalArgumentException("Update for " + key + " included " + activity.key);
            }
        }
        apps.remove(key);
        for (LauncherApp activity : activities) add(activity);
    }

    void removePackage(String packageName, UserHandle user) {
        apps.remove(new LauncherApp.Key(packageName, user));
    }

    /** An unmodifiable snapshot sorted by label for the current locale. */
    List<LauncherApp> sorted() {
        List<LauncherApp> sorted = new ArrayList<>(apps.values());
        Collator collator = Collator.getInstance();
        sorted.sort((a, b) -> {
            int label = collator.compare(a.label, b.label);
            if (label != 0) return label;
            int packageName = a.key.packageName.compareTo(b.key.packageName);
            return packageName != 0 ? packageName : Integer.compare(a.key.user.hashCode(), b.key.user.hashCode());
        });
        return Collections.unmodifiableList(sorted);
    }

    private void add(LauncherApp activity) {
        if (activity.key.packageName.equals(ownPackage)) return;
        apps.putIfAbsent(activity.key, activity);
    }
}
