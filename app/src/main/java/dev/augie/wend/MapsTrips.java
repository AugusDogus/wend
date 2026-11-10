package dev.augie.wend;

import android.app.Notification;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import java.util.Arrays;

/**
 * Where Google Maps is navigating to, as Maps itself reports it through the optional Maps bridge
 * module (an LSPosed module that runs inside Maps; see {@code maps-bridge/README.md}). Maps'
 * guidance notification names no destination, and Maps restores no route for a trip that is
 * nearly over, so without this Wend cannot resume such a trip after Maps' process dies.
 *
 * <p>The module calls {@code trip} on this provider with the destination as the argument, as a trip
 * starts and whenever Maps saves its directions again, and {@code guidance} with each guidance
 * notification Maps builds (extra {@code notification}; none once guidance ended), which reaches
 * Wend even where Android delivers no notifications ({@link NavigationNotifications#reportedByMaps}).
 * Only Maps may call it. Both stay in memory. Without the module nothing is ever reported, and
 * Wend resumes as before ({@link GuidanceResume}) and hears guidance from Android alone.
 */
public final class MapsTrips extends ContentProvider {
    /**
     * Maps saves the directions of a trip up to 3 s after it starts guiding, and also directions
     * the driver only looked at: a report counts for a guidance from this long before it began.
     */
    private static final long BEFORE_GUIDANCE_MS = 30_000;
    private static volatile Trip last;
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final class Trip {
        final String destination;
        /** When Maps reported it, in uptime ms. */
        final long reportedAt;

        Trip(String destination, long reportedAt) { this.destination = destination; this.reportedAt = reportedAt; }
    }

    /**
     * @param guidanceBegan when the guidance to resume began, in uptime ms, or null if none did
     * @return the link that makes {@code mapsPackage} navigate to that guidance's destination, or
     *     null if Maps did not report one for it
     */
    static Intent navigation(Long guidanceBegan, String mapsPackage) {
        Trip trip = last;
        if (trip == null || guidanceBegan == null || trip.reportedAt < guidanceBegan - BEFORE_GUIDANCE_MS) return null;
        return new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(trip.destination)))
                .setPackage(mapsPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Maps reported where it is going; nothing usable forgets nothing. */
    static void reported(String destination) {
        if (!TextUtils.isEmpty(destination) && !destination.trim().isEmpty()) {
            last = new Trip(destination, SystemClock.uptimeMillis());
        }
    }

    static void forget() { last = null; }

    /**
     * Asks a running Maps to report again: a Wend that has just started has heard nothing yet, and
     * Maps posts no new guidance while the car stands still. Without the module nothing answers.
     */
    static void ask(Context context) {
        context.sendBroadcast(new Intent("dev.augie.wend.mapsbridge.TRIP").setPackage(EmbeddedMaps.PACKAGE));
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        boolean trip = "trip".equals(method), guidance = "guidance".equals(method);
        if (!trip && !guidance) return null;
        Context context = getContext();
        int caller = Binder.getCallingUid();
        String[] callers = context == null ? null : context.getPackageManager().getPackagesForUid(caller);
        if (callers == null || !Arrays.asList(callers).contains(EmbeddedMaps.PACKAGE)) {
            throw new SecurityException("Only " + EmbeddedMaps.PACKAGE + " may report its trip and guidance to Wend; uid " + caller
                    + " is " + Arrays.toString(callers));
        }
        if (trip) reported(arg);
        else {
            // Maps' own guidance notification, or none once its guidance ended.
            Notification notification = extras == null ? null : extras.getParcelable("notification");
            main.post(() -> NavigationNotifications.reportedByMaps(notification));
        }
        return null;
    }

    @Override public boolean onCreate() { return true; }
    // Not a store of anything: only call() is answered.
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
