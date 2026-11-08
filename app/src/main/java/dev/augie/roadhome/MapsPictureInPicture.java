package dev.augie.roadhome;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/** Road Home places the Maps window itself, so Maps must not shrink into system PiP on Home. */
final class MapsPictureInPicture {
    enum Outcome { DISABLED, ALREADY_DISABLED, NOT_INSTALLED }

    private MapsPictureInPicture() { }

    /**
     * Sets the same app-op as Settings > Apps > Special app access > Picture-in-picture.
     * Android 10's enterPictureInPictureMode then returns false instead of pinning the task;
     * the app is not notified otherwise and keeps running fullscreen.
     */
    static Outcome disable(Context context, String packageName) throws ReflectiveOperationException {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) throw new IllegalStateException("Android app-ops service is unavailable");
        int uid;
        try { uid = context.getPackageManager().getApplicationInfo(packageName, 0).uid; }
        catch (PackageManager.NameNotFoundException missing) { return Outcome.NOT_INSTALLED; }
        // ActivityRecord.checkEnterPictureInPictureAppOpsState requires exactly MODE_ALLOWED.
        if (appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, uid, packageName)
                != AppOpsManager.MODE_ALLOWED) return Outcome.ALREADY_DISABLED;
        // System API, hidden from the public SDK. Android 10 grants MANAGE_APP_OPS_MODES to UID 1000.
        AppOpsManager.class.getMethod("setMode", String.class, int.class, String.class, int.class)
                .invoke(appOps, AppOpsManager.OPSTR_PICTURE_IN_PICTURE, uid, packageName,
                        AppOpsManager.MODE_IGNORED);
        Log.i("RoadHome", "Disabled system picture-in-picture for " + packageName + " (uid " + uid
                + ") because Road Home returns it to the map panel on Home. Re-enable it in Settings"
                + " > Apps > Special app access > Picture-in-picture if Road Home is removed.");
        return Outcome.DISABLED;
    }
}
