package dev.augie.wend.mapsbridge;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Runs inside Google Maps. Maps saves the directions it navigates by in its own files, which no
 * other app can read, within seconds of a trip starting. This reads their destination and
 *
 * <ul>
 * <li>tells Wend each time they change, and once as Maps starts: a call of {@code trip} on Wend's
 *     provider {@code content://dev.augie.wend.mapstrips}, with the destination as its argument
 *     and {@link #LATITUDE}, {@link #LONGITUDE} and {@link #SAVED_AT} as extras;
 * <li>answers the broadcast {@link #ASK} from any sender that may manage activity stacks (Wend, or
 *     {@code adb shell}): it tells Wend again, and an ordered broadcast gets result code 1, the
 *     destination as result data, and the same extras.
 * </ul>
 *
 * It also carries {@link GuidanceReporter}'s reports to Wend, off Maps' own threads.
 */
final class TripReporter {
    static final String ASK = "dev.augie.wend.mapsbridge.TRIP";
    static final String LATITUDE = "latitude", LONGITUDE = "longitude", SAVED_AT = "savedAt";
    private static final Uri WEND = Uri.parse("content://dev.augie.wend.mapstrips");
    private static final String ASKERS = "android.permission.MANAGE_ACTIVITY_STACKS";
    /** Maps writes both as a trip starts; the second again whenever its route or stops change. */
    private static final String[] FILES = {"saved_directions.data.cs", "directions_as_changed_in_navigation.data.cs"};
    /** Maps writes the two files together: one report for both. */
    private static final int SETTLE_MS = 300;
    /** Kept so the observer is not collected. */
    private static TripReporter started;

    private final Context maps;
    private final File directory;
    private final Handler worker;
    private final FileObserver observer;
    /** Touched on the worker thread only. */
    private boolean failureLogged;
    private final Runnable report = this::report;
    private GuidanceReporter guidance;

    private TripReporter(Context maps) {
        this.maps = maps;
        directory = maps.getFilesDir();
        HandlerThread thread = new HandlerThread("wend-maps-bridge");
        thread.start();
        worker = new Handler(thread.getLooper());
        observer = new FileObserver(directory, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                for (String name : FILES) {
                    if (!name.equals(path)) continue;
                    worker.removeCallbacks(report);
                    worker.postDelayed(report, SETTLE_MS);
                }
            }
        };
    }

    static synchronized void start(Application maps) {
        if (started != null) return;
        TripReporter reporter = new TripReporter(maps);
        maps.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { reporter.answer(this); }
        }, new IntentFilter(ASK), ASKERS, reporter.worker);
        reporter.observer.startWatching();
        reporter.guidance = GuidanceReporter.install(reporter);
        reporter.worker.post(reporter.report);
        started = reporter;
        Log.i(MapsBridge.TAG, "Reporting Maps' destination to Wend from " + reporter.directory);
    }

    /** The newer of Maps' saved directions that holds a trip, or null. */
    private Saved saved() {
        Saved newest = null;
        for (String name : FILES) {
            File file = new File(directory, name);
            long at = file.lastModified();
            if (at == 0 || newest != null && at <= newest.at) continue;
            try {
                Trip trip = TripFile.destination(Files.readAllBytes(file.toPath()));
                if (trip != null) newest = new Saved(trip, at);
            } catch (IOException | RuntimeException error) {
                Log.w(MapsBridge.TAG, "Could not read " + file + "; reporting Maps' other saved directions, if any", error);
            }
        }
        return newest;
    }

    private void report() {
        Saved saved = saved();
        if (saved == null) return;
        tellWend("trip", saved.trip.destination, saved.extras());
    }

    /** Calls {@code method} on Wend's provider from this reporter's thread, in the order asked. */
    void tellWend(String method, String argument, Bundle extras) {
        worker.post(() -> {
            try {
                maps.getContentResolver().call(WEND, method, argument, extras);
                failureLogged = false;
            } catch (RuntimeException error) {
                // Wend is not installed, or is an older Wend without the provider.
                if (failureLogged) return;
                failureLogged = true;
                Log.w(MapsBridge.TAG, "Could not tell Wend Maps' " + method + "; Wend works as without this module."
                        + " Logged once until a report gets through", error);
            }
        });
    }

    private void answer(BroadcastReceiver asked) {
        Saved saved = saved();
        if (saved != null) tellWend("trip", saved.trip.destination, saved.extras());
        if (guidance != null) guidance.report();
        if (!asked.isOrderedBroadcast()) return;
        if (saved == null) { asked.setResultCode(0); return; }
        asked.setResult(1, saved.trip.destination, saved.extras());
    }

    private static final class Saved {
        final Trip trip;
        /** When Maps wrote it, in wall-clock ms. */
        final long at;

        Saved(Trip trip, long at) { this.trip = trip; this.at = at; }

        Bundle extras() {
            Bundle extras = new Bundle();
            if (!Double.isNaN(trip.latitude)) {
                extras.putDouble(LATITUDE, trip.latitude);
                extras.putDouble(LONGITUDE, trip.longitude);
            }
            extras.putLong(SAVED_AT, at);
            return extras;
        }
    }
}
