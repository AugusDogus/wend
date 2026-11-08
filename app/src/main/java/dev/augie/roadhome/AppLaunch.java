package dev.augie.roadhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

/**
 * Opens apps from the dock, the drawer and the media panel. Maps moves its existing task
 * fullscreen and the video app reuses its live-video task; neither is relaunched or duplicated.
 */
final class AppLaunch {
    private AppLaunch() { }

    static void open(Activity activity, LauncherApp app) {
        if (Process.myUserHandle().equals(app.key.user)) open(activity, app.key.packageName);
        else openInProfile(activity, app);
    }

    static void open(Activity activity, String packageName) {
        if (EmbeddedVideo.PACKAGE.equals(packageName) && activity instanceof MainActivity
                && ((MainActivity) activity).openVideoFullscreen(() -> openDirect(activity, packageName))) return;
        openDirect(activity, packageName);
    }

    /** Opens an Android screen such as Settings in its own task over the launcher, never inside Home's task. */
    static void openSystemScreen(Activity activity, Intent intent, String name) {
        MainActivity home = activity instanceof MainActivity ? (MainActivity) activity : null;
        if (home != null) home.externalAppOpening();
        try {
            activity.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), mainDisplay(activity));
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e("RoadHome", "Cannot open " + name + " with " + intent, error);
            Toast.makeText(activity, "Android could not open " + name + ". Open it from the Android settings app.",
                    Toast.LENGTH_LONG).show();
            if (home != null) home.externalAppFailed();
        }
    }

    private static void openDirect(Activity activity, String packageName) {
        if (EmbeddedMaps.PACKAGE.equals(packageName) && activity instanceof MainActivity) {
            ((MainActivity) activity).openMapsFullscreen();
            return;
        }
        MainActivity home = activity instanceof MainActivity ? (MainActivity) activity : null;
        boolean video = EmbeddedVideo.PACKAGE.equals(packageName);
        if (home != null) {
            if (video) home.videoAppOpening();
            else home.externalAppOpening();
        }
        if (openExternal(activity, packageName)) {
            if (home != null && video) home.videoOpened();
        } else if (home != null) home.externalAppFailed();
    }

    private static boolean openExternal(Activity activity, String packageName) {
        if (EmbeddedVideo.PACKAGE.equals(packageName)) {
            try {
                if (VideoTask.openFullscreen(activity)) return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.e("RoadHome", "Could not restore the existing video task", error);
                Toast.makeText(activity, "The video could not be restored. Open it from Recents; existing tasks were left intact.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            Log.w("RoadHome", "No launch intent for " + packageName + "; it is not installed or has no launcher activity");
            Toast.makeText(activity, "This app is not installed or cannot be opened.", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            activity.startActivity(launch, mainDisplay(activity));
            return true;
        }
        catch (ActivityNotFoundException | SecurityException error) {
            Log.e("RoadHome", "Cannot launch " + packageName, error);
            Toast.makeText(activity, "Android could not open this app. Try another app.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Apps from another profile, such as a work profile, open through LauncherApps. */
    private static void openInProfile(Activity activity, LauncherApp app) {
        MainActivity home = activity instanceof MainActivity ? (MainActivity) activity : null;
        if (home != null) home.externalAppOpening();
        try {
            LauncherApps launcherApps = activity.getSystemService(LauncherApps.class);
            if (launcherApps == null) throw new SecurityException("LauncherApps service is unavailable");
            launcherApps.startMainActivity(app.component, app.key.user, null, mainDisplay(activity));
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.e("RoadHome", "Cannot launch " + app.component + " in profile " + app.key.user, error);
            Toast.makeText(activity, "Android could not open this app in its profile. "
                    + "Check that the profile is turned on.", Toast.LENGTH_LONG).show();
            if (home != null) home.externalAppFailed();
        }
    }

    private static Bundle mainDisplay(Activity activity) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(activity.getWindowManager().getDefaultDisplay().getDisplayId());
        return options.toBundle();
    }
}
