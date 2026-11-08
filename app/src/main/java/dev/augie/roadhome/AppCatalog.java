package dev.augie.roadhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Launcher-scope catalog of launchable apps, modeled on Launcher3's LauncherModel. It loads
 * once on a background thread when the launcher starts, then LauncherApps callbacks reload
 * only the affected packages. Labels and pre-rendered icons stay in memory, so opening a
 * screen never queries PackageManager. Call it and its listeners on the main thread.
 */
final class AppCatalog {
    enum Status { LOADING, READY, FAILED }

    interface Listener { void onAppsChanged(AppCatalog catalog); }

    static final int ICON_DP = 48;
    private static final String TAG = "RoadHome";
    private static AppCatalog instance;

    private final LauncherApps launcherApps;
    private final PackageManager packageManager;
    private final int iconSize;
    private final int iconDensity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;
    private final List<Listener> listeners = new ArrayList<>();
    // Main-thread state. Apps are only nonempty when the status is READY.
    private Status status = Status.LOADING;
    private List<LauncherApp> apps = Collections.emptyList();
    /** The apps that pins can name, by package. */
    private Map<String, LauncherApp> pinnable = Collections.emptyMap();
    // Worker-thread state.
    private final AppList model;
    private boolean loaded;

    static AppCatalog get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AppCatalog.get must be called on the main thread");
        }
        if (instance == null) {
            HandlerThread thread = new HandlerThread("RoadHome-apps", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            instance = new AppCatalog(context.getApplicationContext(), thread.getLooper());
        }
        return instance;
    }

    /** Loads apps and applies package changes on {@code workerLooper}; listeners hear about them on the main thread. */
    AppCatalog(Context context, Looper workerLooper) {
        launcherApps = context.getSystemService(LauncherApps.class);
        packageManager = context.getPackageManager();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        iconSize = Math.round(ICON_DP * metrics.density);
        iconDensity = metrics.densityDpi;
        model = new AppList(context.getPackageName());
        worker = new Handler(workerLooper);
        worker.post(this::loadAll);
        if (launcherApps != null) launcherApps.registerCallback(packageChanges, worker);
        // Labels are localized; Launcher3 also reloads everything when the locale changes.
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { loadAll(); }
        }, new IntentFilter(Intent.ACTION_LOCALE_CHANGED), null, worker);
    }

    Status status() { return status; }

    /** Sorted by label; unmodifiable. Empty until the first load completes. */
    List<LauncherApp> apps() { return apps; }

    /** Dock pins name packages in Road Home's own profile; apps in a work profile cannot be pinned. */
    static boolean canPin(LauncherApp app) { return Process.myUserHandle().equals(app.key.user); }

    /** The app a dock pin names, or null when its package is not in the list. */
    LauncherApp pinnedApp(String packageName) { return pinnable.get(packageName); }

    void addListener(Listener listener) { listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    /** Starts another full load if the last one failed; otherwise does nothing. */
    void retryIfFailed() {
        if (status != Status.FAILED) return;
        publishOnMain(Status.LOADING, Collections.<LauncherApp>emptyList());
        worker.post(this::loadAll);
    }

    private void loadAll() {
        long started = SystemClock.uptimeMillis();
        try {
            if (launcherApps == null) throw new IllegalStateException("LauncherApps service is unavailable");
            List<LauncherApp> activities = new ArrayList<>();
            for (UserHandle user : launcherApps.getProfiles()) {
                activities.addAll(entries(launcherApps.getActivityList(null, user)));
            }
            model.replaceAll(activities);
            loaded = true;
            List<LauncherApp> sorted = model.sorted();
            Log.i(TAG, "Loaded " + sorted.size() + " launchable apps in "
                    + (SystemClock.uptimeMillis() - started) + " ms");
            publish(Status.READY, sorted);
        } catch (RuntimeException error) {
            if (loaded) {
                Log.e(TAG, "Could not reload launchable apps; the drawer keeps its previous list, "
                        + "whose labels may be stale until the next package change", error);
                return;
            }
            Log.e(TAG, "Could not load launchable apps from LauncherApps. The drawer shows a "
                    + "retry message and loads again the next time it opens.", error);
            publish(Status.FAILED, Collections.<LauncherApp>emptyList());
        }
    }

    private void reload(String[] packages, UserHandle user, String event) {
        if (!loaded) return; // The pending or retried full load includes this change.
        for (String packageName : packages) {
            try {
                model.replacePackage(packageName, user, entries(launcherApps.getActivityList(packageName, user)));
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not reload launcher activities for " + packageName + " after it was "
                        + event + "; its previous drawer entry is kept until its next change", error);
            }
        }
        changed(packages, event);
    }

    private void remove(String[] packages, UserHandle user, String event) {
        if (!loaded) return;
        for (String packageName : packages) model.removePackage(packageName, user);
        changed(packages, event);
    }

    private void changed(String[] packages, String event) {
        List<LauncherApp> sorted = model.sorted();
        Log.i(TAG, "Apps " + event + ": " + Arrays.toString(packages) + "; " + sorted.size() + " launchable apps");
        publish(Status.READY, sorted);
    }

    /** One app that cannot be read is left out; it never fails the rest of the list. */
    private List<LauncherApp> entries(List<LauncherActivityInfo> activities) {
        List<LauncherApp> entries = new ArrayList<>(activities.size());
        for (LauncherActivityInfo activity : activities) {
            try {
                entries.add(new LauncherApp(activity.getComponentName(), activity.getUser(),
                        activity.getLabel().toString().trim(), icon(activity)));
            } catch (RuntimeException error) {
                Log.e(TAG, "Left " + activity.getComponentName() + " for " + activity.getUser()
                        + " out of the app list because its label or icon could not be read; it returns "
                        + "when its package changes or the list reloads", error);
            }
        }
        return entries;
    }

    private Bitmap icon(LauncherActivityInfo activity) {
        try {
            return AppIcon.render(activity.getBadgedIcon(iconDensity), iconSize);
        } catch (RuntimeException error) {
            Log.w(TAG, "Using Android's default icon for " + activity.getComponentName()
                    + " because its own icon could not be loaded or drawn", error);
            return AppIcon.render(packageManager.getDefaultActivityIcon(), iconSize);
        }
    }

    private void publish(Status next, List<LauncherApp> sorted) { main.post(() -> publishOnMain(next, sorted)); }

    private void publishOnMain(Status next, List<LauncherApp> sorted) {
        status = next;
        apps = sorted;
        Map<String, LauncherApp> byPackage = new HashMap<>();
        for (LauncherApp app : sorted) if (canPin(app)) byPackage.put(app.key.packageName, app);
        pinnable = byPackage;
        for (Listener listener : new ArrayList<>(listeners)) listener.onAppsChanged(this);
    }

    // Delivered on the worker thread, after any load that was already queued.
    private final LauncherApps.Callback packageChanges = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String packageName, UserHandle user) {
            reload(new String[]{packageName}, user, "added");
        }
        @Override public void onPackageChanged(String packageName, UserHandle user) {
            reload(new String[]{packageName}, user, "changed");
        }
        @Override public void onPackageRemoved(String packageName, UserHandle user) {
            remove(new String[]{packageName}, user, "removed");
        }
        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) {
            reload(packageNames, user, "available");
        }
        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) {
            // An update in progress is followed by onPackagesAvailable; keep its icon meanwhile.
            if (!replacing) remove(packageNames, user, "unavailable");
        }
    };
}
