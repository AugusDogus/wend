package dev.augie.wend;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.UserHandle;

/** One launchable app: a package's first launcher activity in one profile. Immutable. */
final class LauncherApp {
    /** Identity that survives reloads: one entry per package and profile. */
    static final class Key {
        final String packageName;
        final UserHandle user;

        Key(String packageName, UserHandle user) {
            this.packageName = packageName;
            this.user = user;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return packageName.equals(key.packageName) && user.equals(key.user);
        }

        @Override public int hashCode() { return 31 * packageName.hashCode() + user.hashCode(); }
        @Override public String toString() { return packageName + " for " + user; }
    }

    final Key key;
    final ComponentName component;
    final String label;
    /** Pre-rendered squircle shared by every view. Never recycle it. */
    final Bitmap icon;

    LauncherApp(ComponentName component, UserHandle user, String label, Bitmap icon) {
        key = new Key(component.getPackageName(), user);
        this.component = component;
        this.label = label;
        this.icon = icon;
    }
}
