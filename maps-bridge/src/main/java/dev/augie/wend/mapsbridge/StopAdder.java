package dev.augie.wend.mapsbridge;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs inside Google Maps. Adds a stop to the trip Maps guides, before its destination, through
 * Maps' own navigation ({@link MapsNavigation}), as Maps' "Add stop" does: Maps keeps guiding, finds
 * the place from its text itself, and reroutes through it. Nothing shows on screen but the new route.
 *
 * <p>Wend asks with the ordered broadcast {@link #ASK} (extras {@link #PLACE} and {@link #REQUEST})
 * from a sender that may manage activity stacks; it gets result code 1 if this module will answer.
 * The answer is a call of {@code stop} on Wend's provider with the extras {@link #REQUEST},
 * {@link #ADDED}, and {@link #PROBLEM} when not added.
 */
final class StopAdder {
    static final String ASK = "dev.augie.wend.mapsbridge.ADD_STOP";
    static final String PLACE = "place", REQUEST = "request", ADDED = "added", PROBLEM = "problem";
    private static final String ASKERS = "android.permission.MANAGE_ACTIVITY_STACKS";
    /** Maps answered within about 1 s on the emulator; it reroutes over the network. */
    private static final long ANSWER_WAIT_S = 20;

    private StopAdder() { }

    /**
     * @param navigation Maps' navigation, or null if it was not found in this Maps, which this
     *     then says to each ask
     * @param why what went wrong finding it, when {@code navigation} is null
     */
    static void install(Application maps, TripReporter reporter, MapsNavigation navigation, String why) {
        // Its own thread: waiting on Maps here holds up none of the other reports to Wend.
        HandlerThread thread = new HandlerThread("wend-maps-bridge-stops");
        thread.start();
        Handler worker = new Handler(thread.getLooper());
        maps.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (isOrderedBroadcast()) setResultCode(1);
                String place = intent.getStringExtra(PLACE);
                int request = intent.getIntExtra(REQUEST, 0);
                PendingResult pending = goAsync();
                worker.post(() -> {
                    try { reporter.tellWend("stop", null, answer(navigation, why, place, request)); }
                    finally { pending.finish(); }
                });
            }
        }, new IntentFilter(ASK), ASKERS, worker);
    }

    private static Bundle answer(MapsNavigation navigation, String why, String place, int request) {
        Bundle extras = new Bundle();
        extras.putInt(REQUEST, request);
        String problem = null;
        if (navigation == null) problem = "Wend's Maps bridge could not find Maps' Add stop in this version of Maps: " + why;
        else if (place == null || place.trim().isEmpty()) problem = "No place was given to add";
        else {
            try {
                Future<?> added = navigation.addStop(place.trim());
                if (!Boolean.TRUE.equals(added.get(ANSWER_WAIT_S, TimeUnit.SECONDS))) problem = "Maps did not add \"" + place + "\" to its route";
            } catch (IllegalStateException notGuided) {
                problem = "Maps is not guiding a trip to add a stop to: " + notGuided.getMessage();
            } catch (TimeoutException slow) {
                problem = "Maps did not answer within " + ANSWER_WAIT_S + " s; it may still add the stop";
            } catch (ExecutionException failed) {
                problem = "Maps could not add \"" + place + "\": " + failed.getCause();
            } catch (ReflectiveOperationException | RuntimeException | InterruptedException broken) {
                problem = "Wend's Maps bridge could not call Maps' Add stop: " + broken;
            }
        }
        extras.putBoolean(ADDED, problem == null);
        if (problem != null) {
            extras.putString(PROBLEM, problem);
            Log.w(MapsBridge.TAG, "Added no stop for Wend: " + problem);
        } else {
            Log.i(MapsBridge.TAG, "Added a stop for Wend: " + place);
        }
        return extras;
    }
}
