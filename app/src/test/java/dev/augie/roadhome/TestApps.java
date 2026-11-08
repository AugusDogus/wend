package dev.augie.roadhome;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.robolectric.Shadows.shadowOf;

/** Launchable apps for Robolectric tests, served through LauncherApps as AppCatalog reads them. */
final class TestApps {
    private TestApps() { }

    static void install(Context context, String packageName, String label) {
        install(context, Process.myUserHandle(), packageName, label, new ColorDrawable(Color.GREEN));
    }

    static void install(Context context, String packageName, String label, Drawable artwork) {
        install(context, Process.myUserHandle(), packageName, label, artwork);
    }

    static void install(Context context, UserHandle user, String packageName, String label, Drawable artwork) {
        ActivityInfo info = new ActivityInfo() {
            @Override public Drawable loadIcon(PackageManager packageManager) { return artwork; }
        };
        info.nonLocalizedLabel = label;
        add(context, user, packageName, info);
    }

    /** An app whose label cannot be read, as when its resources are unavailable. */
    static void installWithUnreadableLabel(Context context, String packageName) {
        add(context, Process.myUserHandle(), packageName, new ActivityInfo() {
            @Override public CharSequence loadLabel(PackageManager packageManager) {
                throw new IllegalStateException("The label resource of " + packageName + " is unavailable");
            }
        });
    }

    /** A catalog that loads on the main looper, after its first load has been published. */
    static AppCatalog loadedCatalog(Context context) {
        AppCatalog catalog = new AppCatalog(context, Looper.getMainLooper());
        shadowOf(Looper.getMainLooper()).idle();
        return catalog;
    }

    private static void add(Context context, UserHandle user, String packageName, ActivityInfo info) {
        info.packageName = packageName;
        info.name = packageName + ".Main";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        // Android 10 builds these only inside the framework; the constructor is package-private.
        LauncherActivityInfo activity = ReflectionHelpers.callConstructor(LauncherActivityInfo.class,
                ClassParameter.from(Context.class, context), ClassParameter.from(ActivityInfo.class, info),
                ClassParameter.from(UserHandle.class, user));
        shadowOf(context.getSystemService(LauncherApps.class)).addActivity(user, activity);
    }
}
